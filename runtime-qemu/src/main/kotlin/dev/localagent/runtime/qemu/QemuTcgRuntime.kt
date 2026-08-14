package dev.localagent.runtime.qemu

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import dev.localagent.runtime.api.ComputerRuntime
import dev.localagent.runtime.api.DesktopSession
import dev.localagent.runtime.api.ExecEvent
import dev.localagent.runtime.api.ExecRequest
import dev.localagent.runtime.api.ExecResult
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.GuestSession
import dev.localagent.runtime.api.PortForward
import dev.localagent.runtime.api.PortForwardRequest
import dev.localagent.runtime.api.PtyRequest
import dev.localagent.runtime.api.PtySession
import dev.localagent.runtime.api.RuntimeFailure
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.runtime.api.SessionRequest
import dev.localagent.runtime.api.SnapshotId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** App-private QEMU/TCG runtime. Guest operations are never substituted with Android shell work. */
class QemuTcgRuntime(context: Context) : ComputerRuntime {
    private val appContext = context.applicationContext
    private val storage = RuntimeStorage(appContext)

    /**
     * Where a process that has not run anything yet starts from: an installed box is *closed*, and
     * only a device with no box at all is unprovisioned.
     *
     * This used to open at `NotProvisioned` regardless, on the assumption that nothing would ask
     * before a start had been requested. Two things ask. Android recreates `:computer` for a
     * client that still holds a binding after the process retires, and the UI now binds a stopped
     * computer on purpose — to read a finished session's log back. Either one broadcast "no box
     * yet" over a phone with a box on it, and the home screen offered to set up what the user
     * already had.
     */
    private val runtimeState = MutableStateFlow<RuntimeState>(
        if (storage.hasHeadlessBootSet() || storage.hasUefiBootSet()) {
            RuntimeState.Stopped
        } else {
            RuntimeState.NotProvisioned
        },
    )
    private val agentd = AgentdClient(storage.agentSocket)
    private val lifecycleMutex = Mutex()
    private val processLifetime = QemuProcessLifetime()
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generation = AtomicLong()

    @Volatile private var startingJob: Job? = null
    private var exitMonitor: Job? = null
    private var serialLogger: SerialConsoleLogger? = null

    /**
     * How big the next machine is built. Set by [RuntimeService] from the user's preference before
     * a start, and only read by a start — a running guest cannot be resized, and a box that is up
     * keeps whatever it was opened with.
     */
    @Volatile var sizing: GuestSizing = GuestSizing.DEFAULT

    /**
     * The sizing the *running* guest was actually launched with.
     *
     * Kept apart from [sizing] because the preference can change under a box that is already open,
     * and the fingerprint written beside a saved guest has to describe the machine its memory came
     * out of. Recording the requested value instead would stamp a snapshot with a machine nobody
     * ever built, and the next open would restore it into the wrong one.
     */
    @Volatile private var launchedSizing: GuestSizing = GuestSizing.DEFAULT

    init {
        // A box that was put away is not a box that was closed, and this process is not the one
        // that put it away — that process ended with the QEMU it was hosting. The note it left is
        // the only thing that can tell the user their box is paused rather than shut.
        if (storage.suspendedVm() != null) runtimeState.value = RuntimeState.Suspended
    }

    override fun state(): StateFlow<RuntimeState> = runtimeState.asStateFlow()

    /**
     * True when the image this APK carries is already installed, exactly.
     *
     * Not merely "some boot set exists", which is what this used to mean and why a rebuilt guest
     * image never reached a device that had one. A newer version of the same image now reads as
     * not provisioned, so the ordinary start path installs it — and because only the image-owned
     * payloads are replaced, the user's `/workspace` comes through untouched.
     */
    fun isProvisioned(): Boolean = storage.isImageUpToDate() || storage.hasUefiBootSet()

    /** What is installed and what the APK would install, for logs and eventually for the UI. */
    fun installedImage(): GuestImageIdentity? = storage.installedIdentity()

    fun bundledImage(): GuestImageIdentity? = storage.bundledIdentity()

    /**
     * True once this process has run its one VM and settled, so it can no longer start another.
     * See [QemuProcessLifetime]; the caller is expected to end the process.
     */
    fun isSpent(state: RuntimeState): Boolean = processLifetime.isSpent(state)

    override suspend fun provision(): Result<Unit> = lifecycleMutex.withLock {
        if (NativeQemu.isRunning()) return@withLock Result.failure(
            IllegalStateException("Stop the Linux workspace before provisioning"),
        )
        try {
            runtimeState.value = RuntimeState.Provisioning(0f)
            withContext(Dispatchers.IO) {
                storage.provisionBundledAssets { progress ->
                    runtimeState.value = RuntimeState.Provisioning(progress.coerceIn(0f, 1f))
                }
            }
            runtimeState.value = RuntimeState.Stopped
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            runtimeState.value = RuntimeState.NotProvisioned
            throw cancelled
        } catch (error: Exception) {
            fail("Guest provisioning failed", error)
        }
    }

    /**
     * Reinstalls the image over itself, keeping the user's `/workspace`.
     *
     * Separate from [provision] because it is the destructive one: provisioning asks "what is
     * missing or out of date", this asserts "replace it regardless". The system disk and the
     * workspace have always been two qcow2 files on two virtio-blk devices, so keeping one while
     * discarding the other costs nothing structural — it only ever needed to be sayable.
     */
    suspend fun reprovisionImage(): Result<Unit> = lifecycleMutex.withLock {
        if (NativeQemu.isRunning()) return@withLock Result.failure(
            IllegalStateException("Stop the Linux workspace before reinstalling its image"),
        )
        try {
            runtimeState.value = RuntimeState.Provisioning(0f)
            withContext(Dispatchers.IO) {
                storage.reprovisionImage { progress ->
                    runtimeState.value = RuntimeState.Provisioning(progress.coerceIn(0f, 1f))
                }
            }
            runtimeState.value = RuntimeState.Stopped
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            runtimeState.value = RuntimeState.NotProvisioned
            throw cancelled
        } catch (error: Exception) {
            fail("Reinstalling the guest image failed", error)
        }
    }

    override suspend fun start(): Result<Unit> = lifecycleMutex.withLock {
        if (runtimeState.value == RuntimeState.Ready && NativeQemu.isRunning()) {
            return@withLock Result.success(Unit)
        }

        // Refused before anything is announced, and deliberately not through [fail]. This process
        // has spent its one QEMU run and is on its way out; the next startService lands in a fresh
        // one that can serve this. Publishing Failed here would turn an ordinary retirement into
        // "your box didn't open" over a box that is sitting saved on disk, unharmed — and saving
        // boxes is exactly what makes a start arrive during a retirement in the first place.
        if (!processLifetime.canStart()) {
            return@withLock Result.failure(
                IllegalStateException("This computer process has already been used once and must be replaced"),
            )
        }

        val callerJob = currentCoroutineContext()[Job]
        startingJob = callerJob
        var nativeStarted = false
        val launchedAt = SystemClock.elapsedRealtime()
        try {
            storage.ensureDirectories()
            // QEMU's own data files, refreshed from the APK every start. Not part of provisioning:
            // a device that already has its disks never provisions again, and would keep whatever
            // the build it was first installed from happened to ship.
            storage.installQemuData()
            check(storage.hasHeadlessBootSet() || storage.hasUefiBootSet()) {
                "No complete verified guest image is installed yet"
            }
            runtimeState.value = RuntimeState.Starting

            // A box that was put away rather than closed. The note is spent here, before QEMU is
            // handed the snapshot rather than after it has loaded it, so a saved guest is loaded
            // at most once — see [RuntimeStorage.clearSuspendedVm] for why that direction.
            val launching = sizing.clamped()
            val resuming = pendingResume(launching)
            if (resuming != null) {
                Log.i(TAG, "Reopening a box saved ${System.currentTimeMillis() - resuming.savedAtMillis}ms ago")
                storage.clearSuspendedVm()
            }

            if (!NativeQemu.isRunning()) {
                storage.removeStaleSockets()
                NativeQemu.start(
                    QemuCommand.boot(storage, resuming?.tag, launching).toTypedArray(),
                    storage.privateRoot.absolutePath,
                )?.let { error(it) }
                nativeStarted = true
                launchedSizing = launching
                Log.i(
                    TAG,
                    "Machine is ${launching.processors} processors and ${launching.memoryMb} MB",
                )
            }

            runtimeState.value = RuntimeState.Connecting
            val qmpStatus = awaitQmp()
            check(NativeQemu.isRunning() && qmpStatus.running) {
                "QEMU status is ${qmpStatus.status}"
            }
            Log.i(TAG, "QMP confirmed running guest in ${SystemClock.elapsedRealtime() - launchedAt}ms")
            // Either the snapshot that was just loaded, or one left behind by a box that was saved
            // and then started cold. Both are dead weight from here: nothing holds a note pointing
            // at it any more, and it is a copy of the guest's memory sitting inside the system disk.
            deleteSnapshot(SuspendedVm.TAG)
            startDebugSerialLogger()
            awaitAgent()
            Log.i(
                TAG,
                "Guest agent confirmed ready ${SystemClock.elapsedRealtime() - launchedAt}ms after launch " +
                    if (resuming != null) "(resumed)" else "(cold boot)",
            )

            runtimeState.value = RuntimeState.Ready
            startExitMonitor(generation.incrementAndGet())
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            if (nativeStarted || NativeQemu.isRunning()) cleanupAfterFailedStart()
            runtimeState.value = RuntimeState.Stopped
            throw cancelled
        } catch (error: Exception) {
            if (nativeStarted || NativeQemu.isRunning()) cleanupAfterFailedStart()
            fail("Linux workspace failed to start", error)
        } finally {
            if (startingJob === callerJob) startingJob = null
        }
    }

    override suspend fun stop(graceful: Boolean): Result<Unit> {
        startingJob?.cancel(CancellationException("Linux workspace stop requested"))
        return lifecycleMutex.withLock {
            if (!NativeQemu.isRunning()) {
                closeGuestChannels()
                runtimeState.value = RuntimeState.Stopped
                return@withLock Result.success(Unit)
            }

            runtimeState.value = RuntimeState.Stopping
            return@withLock try {
                generation.incrementAndGet()
                exitMonitor?.cancelAndJoin()
                exitMonitor = null
                closeGuestChannels()
                NativeQemu.stop()?.let { error(it) }
                check(awaitNativeExit(STOP_TIMEOUT_MILLIS)) {
                    "QEMU did not stop within ${STOP_TIMEOUT_MILLIS / 1_000} seconds"
                }
                runtimeState.value = RuntimeState.Stopped
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail("Linux workspace failed to stop", error)
            }
        }
    }

    /**
     * The buffered form of [execStream], kept for callers that only want the result. Output is
     * accumulated as bytes and decoded once, so a multi-byte character split across two frames is
     * still decoded correctly.
     */
    override suspend fun exec(request: ExecRequest): ExecResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exitCode = -1
        // The guest owns the deadline and always answers; this is only a transport backstop, and
        // it wraps the collection rather than the emission so the flow stays in one coroutine.
        withTimeout((request.timeoutSeconds + EXEC_TRANSPORT_GRACE_SECONDS) * 1_000L) {
            execStream(request).collect { event ->
                when (event) {
                    is ExecEvent.Stdout -> stdout.write(event.bytes)
                    is ExecEvent.Stderr -> stderr.write(event.bytes)
                    is ExecEvent.Exited -> exitCode = event.exitCode
                }
            }
        }
        return ExecResult(
            exitCode,
            stdout.toString(Charsets.UTF_8.name()),
            stderr.toString(Charsets.UTF_8.name()),
        )
    }

    override fun execStream(request: ExecRequest): Flow<ExecEvent> {
        require(request.command.isNotEmpty()) { "Command must not be empty" }
        require(request.timeoutSeconds in 1..MAX_EXEC_TIMEOUT_SECONDS) {
            "Command timeout must be between 1 and $MAX_EXEC_TIMEOUT_SECONDS seconds"
        }
        return flow {
            ensureReady()
            emitAll(agentd.exec(request))
        }
    }

    override suspend fun openSession(request: SessionRequest): GuestSession {
        ensureReady()
        require(request.command.isNotEmpty()) { "Command must not be empty" }
        // 0 is the unbounded case and the default; anything else is still held to the guest's cap.
        require(request.timeoutSeconds == 0 || request.timeoutSeconds in 1..MAX_EXEC_TIMEOUT_SECONDS) {
            "Session timeout must be 0 or between 1 and $MAX_EXEC_TIMEOUT_SECONDS seconds"
        }
        return agentd.openSession(request)
    }

    override suspend fun createPty(request: PtyRequest): PtySession {
        ensureReady()
        require(request.command.isNotEmpty()) { "Command must not be empty" }
        return agentd.openPty(request)
    }

    override suspend fun readFile(path: String): ByteArray {
        ensureReady()
        // v2 streams the file itself, so there is no base64 body and no whole-response cap.
        return agentd.call(
            "read_file",
            JSONObject().put("path", path),
            timeoutMillis = AgentdClient.FILE_CALL_TIMEOUT_MILLIS,
            maxResultBytes = AgentdClient.FILE_MAX_RESULT_BYTES,
        )
    }

    override suspend fun writeFile(path: String, data: ByteArray) {
        ensureReady()
        require(data.size <= MAX_FILE_BYTES) { "File exceeds ${MAX_FILE_BYTES / (1024 * 1024)} MiB limit" }
        agentd.call(
            "write_file",
            JSONObject().put("path", path),
            timeoutMillis = AgentdClient.FILE_CALL_TIMEOUT_MILLIS,
            body = data,
        )
    }

    override suspend fun listFiles(path: String): List<FileEntry> {
        ensureReady()
        val entries = JSONObject(
            agentd.callJson("list_files", JSONObject().put("path", path)),
        ).getJSONArray("items")
        check(entries.length() <= MAX_LIST_ENTRIES) { "Guest returned too many directory entries" }
        return List(entries.length()) { index ->
            entries.getJSONObject(index).let { entry ->
                FileEntry(
                    entry.getString("path"),
                    entry.getString("name"),
                    entry.getBoolean("directory"),
                    entry.getLong("size"),
                )
            }
        }
    }

    override suspend fun desktopStart(): DesktopSession = unavailable("Desktop")
    override suspend fun desktopStop(): Unit = unavailable("Desktop")

    /**
     * Resize the guest's screen to [width] x [height], from inside the guest.
     *
     * See [GuestDisplayMode] for why this is an `xrandr` rather than RFB's `SetDesktopSize`: QEMU
     * 5.1's VNC server can announce a resize but cannot be asked for one.
     *
     * It is retried, because the usual reason it fails is that it was early. The first size is
     * asked for as the computer opens, often before the desktop exists —
     * `local-agent-desktop.service` restarts every five seconds and under TCG can lose a race with
     * udev settling the GPU — and a half-started X does not refuse quickly: it accepts the
     * connection and then does not answer, so the first attempt spends its whole budget. Giving up
     * there would leave the guest at 1280x800 until the window next changed shape, which on a
     * phone is never. A wrong size fails the same way each time and costs a bounded amount; a slow
     * one succeeds on the second or third go.
     *
     * A non-zero exit is still reported, because the failures worth knowing about — no X server at
     * all, or a mode the driver refused — are otherwise silent and look exactly like a screen that
     * simply stayed the wrong shape.
     */
    suspend fun setDisplaySize(width: Int, height: Int) {
        require(width in MIN_DISPLAY_SIDE..MAX_DISPLAY_SIDE && height in MIN_DISPLAY_SIDE..MAX_DISPLAY_SIDE) {
            "A ${width}x$height screen is outside what the guest's display can be"
        }
        var last = ""
        repeat(DISPLAY_RESIZE_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(DISPLAY_RESIZE_RETRY_MILLIS)
            val result = runCatching {
                exec(
                    ExecRequest(
                        command = GuestDisplayMode.command(width, height),
                        // The default, `/workspace`, and left as the default deliberately: nothing
                        // here reads or writes a file, but agentd refuses any working directory
                        // outside /workspace and /home/agent — a "/" that looked harmless was
                        // rejected with the message a real escape attempt gets, and the screen
                        // just stayed the wrong shape.
                        timeoutSeconds = DISPLAY_RESIZE_TIMEOUT_SECONDS,
                    ),
                )
            }
            result.onSuccess { if (it.exitCode == 0) return }
            last = result.fold(
                { it.stderr.trim().ifEmpty { "exit ${it.exitCode}" } },
                { it.message ?: it::class.java.simpleName },
            )
        }
        error("the guest would not take a ${width}x$height screen: $last")
    }
    /**
     * Opens a loopback port on the phone that reaches [request]'s port inside the guest.
     *
     * QEMU's user-mode network stack does this itself — no proxy process, no root, no change to the
     * guest — but only through the human monitor: `hostfwd_add` has no QMP equivalent, so success
     * is an empty line and failure is *printed text*. Anything the monitor says is the error.
     *
     * The host port is chosen by asking the OS for a free one and giving it straight back. That is
     * a race in principle and still the right trade: the alternative is picking from a fixed range
     * and colliding with whatever else on the phone had the same idea, which fails the same way
     * and less legibly.
     *
     * Bound to 127.0.0.1 on purpose. A dev server the agent started is for the person holding the
     * phone; binding it to the phone's wifi address would publish it to the network they are on.
     */
    override suspend fun forwardPort(request: PortForwardRequest): PortForward =
        withContext(Dispatchers.IO) {
            check(NativeQemu.isRunning()) { "The computer is not running" }
            forwards[request.guestPort]?.let { return@withContext it }

            val localPort = ServerSocket(0).use { it.localPort }
            val monitor = QmpClient(storage.qmpSocket)
            val complaint = monitor.monitorCommand(
                "hostfwd_add $NETDEV tcp:$LOOPBACK:$localPort-:${request.guestPort}",
            )
            check(complaint.isEmpty()) { "Could not forward port ${request.guestPort}: $complaint" }

            val forward = QemuPortForward(request.guestPort, localPort) {
                // Removing is best-effort by design: the common way a forward ends is the VM
                // stopping, and by then there is no monitor to tell. A failure here must not
                // propagate into a UI action whose whole job was to tidy up.
                runCatching {
                    QmpClient(storage.qmpSocket)
                        .monitorCommand("hostfwd_remove $NETDEV tcp:$LOOPBACK:$localPort")
                }
                forwards.remove(request.guestPort)
            }
            forwards[request.guestPort] = forward
            forward
        }

    /**
     * Live forwards, so asking twice for the same guest port is the same forward.
     *
     * Without this a second ask would add a second host forward to the same guest port, and the
     * first one's local port would be a number nothing ever closes.
     */
    private val forwards = ConcurrentHashMap<Int, QemuPortForward>()

    private class QemuPortForward(
        val guestPort: Int,
        override val localPort: Int,
        private val onClose: suspend () -> Unit,
    ) : PortForward {
        private val closed = AtomicBoolean(false)

        override suspend fun close() {
            if (closed.compareAndSet(false, true)) onClose()
        }
    }


    /**
     * Puts the box away without throwing it away.
     *
     * The whole product cost of a fully emulated guest is here: a cold boot measured 86 s and 116 s
     * on a Fold 7 to a ready agent, nearly all of it waiting on emulated udev, and that was the
     * price of the box not already running — which put "close it when idle" in direct opposition to
     * "have it there when you want it".
     *
     * `savevm` writes the guest's memory into its own qcow2 and QEMU exits; the next start hands
     * the snapshot to a fresh QEMU with `-loadvm` and the guest carries on from the instruction it
     * was on. Same device: 0.6–3.6 s to save, 0.94–1.12 s to reopen.
     *
     * What does *not* survive is any agent that was mid-task. See [quiesceGuest].
     */
    override suspend fun suspendRuntime() {
        startingJob?.cancel(CancellationException("Linux workspace suspend requested"))
        lifecycleMutex.withLock {
            check(NativeQemu.isRunning()) { "Your box is not open" }
            val image = storage.installedIdentity()?.toString()
                ?: error("Cannot suspend a guest whose image is unknown")

            // Announced before any of the work, so everything else that talks to the guest —
            // the shared-folder pump above all — stops on its own. Otherwise it can reconnect the
            // agent channel in the gap between the disconnect below and the snapshot, and what
            // gets written out is a guest mid-transfer with a host that will not exist.
            runtimeState.value = RuntimeState.Suspending
            var saved = false
            try {
                quiesceGuest()
                generation.incrementAndGet()
                exitMonitor?.cancelAndJoin()
                exitMonitor = null

                val started = SystemClock.elapsedRealtime()
                withContext(Dispatchers.IO) {
                    QmpClient(storage.qmpSocket).open().use { qmp ->
                        // Stop the CPUs first. `savevm` would do it anyway, but doing it here means
                        // the guest is definitely not writing to the disks that are about to be
                        // snapshotted alongside its memory.
                        qmp.command("stop")
                        saveSnapshot(qmp, SuspendedVm.TAG)
                        val elapsed = SystemClock.elapsedRealtime() - started
                        Log.i(TAG, "Saved the guest in ${elapsed}ms")
                        // Recorded before the quit, so a process killed between the two still
                        // leaves a note pointing at a snapshot that is complete on disk.
                        storage.writeSuspendedVm(
                            SuspendedVm(
                                SuspendedVm.TAG,
                                image,
                                System.currentTimeMillis(),
                                elapsed,
                                machine = QemuCommand.machine(storage, launchedSizing),
                            ),
                        )
                        qmp.quit()
                    }
                }
                check(awaitNativeExit(STOP_TIMEOUT_MILLIS)) {
                    "QEMU did not exit within ${STOP_TIMEOUT_MILLIS / 1_000} seconds"
                }
                runtimeState.value = RuntimeState.Suspended
                saved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException(
                    error.message?.takeIf(String::isNotBlank)?.let { "Could not pause your box: $it" }
                        ?: "Could not pause your box",
                    error,
                )
            } finally {
                // Every way out that is not a saved box, cancellation included. Leaving the state
                // at Suspending is the one outcome that must not happen: the VM is still there and
                // still costing battery, and the user is looking at a box that says it is closing
                // and never will. A cancelled save is exactly how that was reached.
                if (!saved) withContext(NonCancellable) { recoverFromFailedSave() }
            }
        }
    }

    /**
     * Reopening a suspended box is an ordinary [start]; this only names it.
     *
     * It cannot be anything else. The saved guest is loaded by a *new* QEMU in a new process —
     * this one has either never run QEMU or can never run it again — so there is no live VM here
     * to un-pause, and the resume is decided by the note on disk that [start] already reads.
     */
    override suspend fun resumeRuntime() {
        start().getOrThrow()
    }

    /**
     * Writes the running guest's memory into its own disk, and leaves it running.
     *
     * The primitive under [suspendRuntime], exposed because it is the same operation with the
     * ending left off. Note the cost of the two things it does not do: the guest's agent channel
     * is left connected, so a guest restored from this snapshot will see that host disappear and
     * kill whatever it was running, exactly as it does across a suspend.
     */
    override suspend fun snapshot(): SnapshotId = lifecycleMutex.withLock {
        check(NativeQemu.isRunning()) { "Your box is not open" }
        withContext(Dispatchers.IO) {
            QmpClient(storage.qmpSocket).open().use { qmp ->
                saveSnapshot(qmp, SuspendedVm.TAG)
            }
        }
        SnapshotId(SuspendedVm.TAG)
    }

    /**
     * Puts the running guest back to a snapshot, in place.
     *
     * The agent channel is dropped first and rebuilt afterwards, because the guest coming back has
     * no idea it went anywhere: its agentd holds a connection to a host that no longer exists, and
     * the protocol on it cannot be resumed from either end. Dropping the connection first is what
     * turns that into the case the guest already handles — the host went away — rather than a
     * silent desync.
     */
    override suspend fun restore(snapshot: SnapshotId): Unit = lifecycleMutex.withLock {
        check(NativeQemu.isRunning()) { "Your box is not open" }
        val previous = runtimeState.value
        runtimeState.value = RuntimeState.Starting
        try {
            closeGuestChannels()
            withContext(Dispatchers.IO) {
                QmpClient(storage.qmpSocket).open().use { qmp ->
                    qmp.command("stop")
                    val output = qmp.monitor("loadvm ${snapshot.value}", SNAPSHOT_TIMEOUT_MILLIS)
                    check(output.isBlank()) { "QEMU could not load ${snapshot.value}: $output" }
                    qmp.command("cont")
                }
            }
            startDebugSerialLogger()
            awaitAgent()
            runtimeState.value = RuntimeState.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runtimeState.value = previous
            throw error
        }
    }

    /**
     * Puts a box back on its feet after a save that did not finish.
     *
     * A box that could not be saved is still a working box, so the CPUs go back on rather than the
     * machine going away: closing it would turn "we could not save you three minutes" into "we
     * threw away what you were doing". What it cannot put back is anything agentd was running —
     * [quiesceGuest] has already happened by this point, and that is not undoable.
     */
    private suspend fun recoverFromFailedSave() {
        if (!NativeQemu.isRunning()) {
            // The quit landed after all, or QEMU died on its own. Which of those it was decides
            // what the user is looking at, and the note on disk is the only thing that knows.
            closeGuestChannels()
            runtimeState.value =
                if (storage.suspendedVm() != null) RuntimeState.Suspended else RuntimeState.Stopped
            return
        }
        runCatching {
            withContext(Dispatchers.IO) {
                QmpClient(storage.qmpSocket).open().use { it.command("cont") }
            }
        }.onFailure { Log.e(TAG, "Could not restart the guest after a failed save", it) }
        runtimeState.value = RuntimeState.Ready
        startExitMonitor(generation.incrementAndGet())
    }

    /**
     * Leaves the guest in the state a snapshot should catch it in: nothing in flight.
     *
     * The disconnect is deliberate and not free. agentd kills every child when its host goes away,
     * so an agent working when the box was put away does not come back with it — and that happens
     * either way, because QEMU tells a restored guest the host it remembers is gone. Doing it here
     * runs the teardown on a healthy guest with a real clock, rather than on a restored one waking
     * up days later.
     *
     * The `sync` is insurance for the path where the snapshot is never loaded — a failed resume, or
     * an update that replaces the disks. The snapshot captures the page cache itself.
     */
    private suspend fun quiesceGuest() {
        runCatching {
            withTimeoutOrNull(SYNC_TIMEOUT_MILLIS) {
                agentd.exec(ExecRequest(listOf("/bin/sh", "-lc", "sync"), timeoutSeconds = 20)).collect { }
            }
        }.onFailure { Log.w(TAG, "Could not flush the guest before saving it", it) }
        closeGuestChannels()
        // Long enough for the guest to notice the closed port and reap its children, so what is
        // written out is an idle agentd waiting for a connection rather than a teardown mid-flight.
        delay(QUIESCE_SETTLE_MILLIS)
    }

    /** `savevm` has no QMP form in QEMU 5.1, and reports failure by printing it. See [QmpClient]. */
    private fun saveSnapshot(qmp: QmpClient.Session, tag: String) {
        val output = qmp.monitor("savevm $tag", SNAPSHOT_TIMEOUT_MILLIS)
        check(output.isBlank()) { "QEMU could not save the guest: $output" }
    }

    /**
     * Drops a snapshot that has been loaded, or was left behind by a box that never reopened.
     *
     * Best effort on purpose: the snapshot holds a copy of the guest's memory inside the system
     * disk and is worth reclaiming, but failing to reclaim it is not a reason to refuse a box the
     * user is already looking at.
     */
    private suspend fun deleteSnapshot(tag: String) = withContext(Dispatchers.IO) {
        runCatching {
            QmpClient(storage.qmpSocket).open().use { qmp ->
                qmp.monitor("delvm $tag", SNAPSHOT_TIMEOUT_MILLIS)
            }
        }.onSuccess { output ->
            // `delvm` succeeds silently when it finds nothing, so a blank answer says only that no
            // saved guest is left in the disks — not that one was there to remove. Anything else is
            // worth a warning: it means a copy of the guest's memory is still sitting in the system
            // disk with nothing left pointing at it.
            if (output.isBlank()) Log.i(TAG, "No saved guest left in the disks")
            else Log.w(TAG, "Could not discard the saved guest: $output")
        }.onFailure { Log.w(TAG, "Could not reach QEMU to discard the saved guest", it) }
    }

    /**
     * The saved guest this device may reopen, or null to boot cold.
     *
     * The image check is what keeps a note from outliving what it describes. The snapshot lives
     * inside the guest's own qcow2 disks, so an app update carrying a newer image installs new
     * ones and the note is left pointing at memory that belongs to a Debian this device no longer
     * has. Booting cold is always safe; loading the wrong snapshot is not.
     */
    private fun pendingResume(launching: GuestSizing): SuspendedVm? {
        val saved = storage.suspendedVm() ?: return null
        val installed = storage.installedIdentity()?.toString()
        if (saved.image != installed) {
            Log.w(
                TAG,
                "Discarding a box saved from ${saved.image}; this device now runs ${installed ?: "no image"}",
            )
            storage.clearSuspendedVm()
            return null
        }
        // The same argument one level down: the guest's memory has to go back into the machine it
        // came out of, and an app update can change that machine without changing its Debian.
        val machine = QemuCommand.machine(storage, launching)
        if (saved.machine != machine) {
            Log.w(
                TAG,
                "Discarding a box saved from machine ${saved.machine.ifBlank { "(unrecorded)" }}; " +
                    "this build builds $machine",
            )
            storage.clearSuspendedVm()
            return null
        }
        return saved
    }

    private suspend fun awaitQmp(): QmpClient.Status = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(QMP_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            check(NativeQemu.isRunning()) { "QEMU exited during startup" }
            try {
                return@withContext QmpClient(storage.qmpSocket).queryStatus()
            } catch (error: Exception) {
                lastError = error
                delay(QMP_RETRY_MILLIS)
            }
        }
        throw IllegalStateException("QEMU did not become reachable through QMP", lastError)
    }

    private suspend fun awaitAgent() = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(AGENT_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            check(NativeQemu.isRunning()) { "QEMU exited while the guest was booting" }
            try {
                val health = agentd.health()
                check(health.optBoolean("ready")) { "Guest agent is not ready" }
                check(health.optInt("protocol", -1) == AgentdClient.PROTOCOL_VERSION) {
                    "Guest agent protocol mismatch"
                }
                return@withContext
            } catch (timeout: TimeoutCancellationException) {
                // The v2 handshake bounds itself with withTimeout, so a guest that has not started
                // agentd yet fails with a TimeoutCancellationException — a CancellationException
                // subclass. Rethrowing it as cancellation would abandon the boot on the very first
                // attempt, which is exactly what the guest needs ~90 seconds of retries to survive.
                // ensureActive still lets a genuine stop() cancel us.
                currentCoroutineContext().ensureActive()
                lastError = timeout
                if (attempt == 0 || (attempt + 1) % 20 == 0) {
                    Log.i(TAG, "Waiting for guest agent (attempt ${attempt + 1})")
                }
                delay(AGENT_RETRY_MILLIS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (attempt == 0 || (attempt + 1) % 20 == 0) {
                    Log.i(TAG, "Waiting for guest agent (attempt ${attempt + 1})")
                }
                delay(AGENT_RETRY_MILLIS)
            }
        }
        throw IllegalStateException("Guest agent did not become ready", lastError)
    }

    private fun startExitMonitor(expectedGeneration: Long) {
        exitMonitor?.cancel()
        exitMonitor = monitorScope.launch {
            while (isActive && generation.get() == expectedGeneration) {
                delay(EXIT_POLL_MILLIS)
                if (!NativeQemu.isRunning()) {
                    closeGuestChannels()
                    if (generation.get() == expectedGeneration && runtimeState.value == RuntimeState.Ready) {
                        runtimeState.value = RuntimeState.Failed(
                            RuntimeFailure("The Linux workspace stopped unexpectedly", recoverable = true),
                        )
                    }
                    return@launch
                }
            }
        }
    }

    private fun startDebugSerialLogger() {
        if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        serialLogger?.close()
        serialLogger = SerialConsoleLogger.launch(storage.serialSocket)
    }

    private suspend fun cleanupAfterFailedStart() = withContext(NonCancellable) {
        generation.incrementAndGet()
        exitMonitor?.cancelAndJoin()
        exitMonitor = null
        closeGuestChannels()
        if (NativeQemu.isRunning()) {
            NativeQemu.stop()
            awaitNativeExit(STOP_TIMEOUT_MILLIS)
        }
    }

    private suspend fun closeGuestChannels() {
        serialLogger?.close()
        serialLogger = null
        agentd.close()
    }

    private suspend fun awaitNativeExit(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (NativeQemu.isRunning()) delay(EXIT_POLL_MILLIS)
            true
        } ?: false

    private fun ensureReady() {
        check(runtimeState.value == RuntimeState.Ready && NativeQemu.isRunning()) {
            "Linux workspace is not ready"
        }
    }

    private fun fail(prefix: String, error: Exception): Result<Unit> {
        val message = error.message?.takeIf(String::isNotBlank)?.let { "$prefix: $it" } ?: prefix
        runtimeState.value = RuntimeState.Failed(RuntimeFailure(message, recoverable = true))
        return Result.failure(error)
    }

    private fun <T> unavailable(feature: String): T =
        throw UnsupportedOperationException("$feature is not available in this runtime yet")

    private companion object {
        /** The id given to the user-mode netdev in [QemuCommand]. `hostfwd_add` names it. */
        const val NETDEV = "net0"

        /** Forwards are for the person holding the phone, not for the network it is on. */
        const val LOOPBACK = "127.0.0.1"

        const val TAG = "BoxRuntime"
        const val QMP_ATTEMPTS = 80
        const val QMP_RETRY_MILLIS = 250L
        const val AGENT_ATTEMPTS = 180
        const val AGENT_RETRY_MILLIS = 1_000L
        const val HEALTH_TIMEOUT_MILLIS = 2_500L
        const val EXEC_TRANSPORT_GRACE_SECONDS = 5
        const val MAX_EXEC_TIMEOUT_SECONDS = 900

        /**
         * A mode set is a real piece of work on an emulated GPU — X reallocates the screen and
         * every client redraws into it — and 15s, which looked generous, was not: it expired on a
         * freshly booted guest and left the desktop at its built-in size.
         */
        const val DISPLAY_RESIZE_TIMEOUT_SECONDS = 60
        const val DISPLAY_RESIZE_ATTEMPTS = 3
        const val DISPLAY_RESIZE_RETRY_MILLIS = 6_000L

        /** What the guest's `virtio-gpu` will accept; it reports `maximum 8192 x 8192`. */
        const val MIN_DISPLAY_SIDE = 320
        const val MAX_DISPLAY_SIDE = 8192
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        const val MAX_LIST_ENTRIES = 2_000
        const val EXIT_POLL_MILLIS = 250L
        const val STOP_TIMEOUT_MILLIS = 15_000L

        /**
         * How long `savevm` and `loadvm` are given. Generous because the work is real — the
         * guest's memory, written to or read from phone flash — and because QEMU's main loop is
         * held for the whole of it, so nothing arrives on the monitor until it is finished.
         */
        const val SNAPSHOT_TIMEOUT_MILLIS = 300_000

        const val SYNC_TIMEOUT_MILLIS = 30_000L

        /** Time for the guest to notice its host has gone and reap what it was running. */
        const val QUIESCE_SETTLE_MILLIS = 1_500L
    }
}
