package dev.localagent.runtime.qemu

import android.content.Context
import android.content.pm.ApplicationInfo
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
import java.util.concurrent.atomic.AtomicLong

/** App-private QEMU/TCG runtime. Guest operations are never substituted with Android shell work. */
class QemuTcgRuntime(context: Context) : ComputerRuntime {
    private val appContext = context.applicationContext
    private val runtimeState = MutableStateFlow<RuntimeState>(RuntimeState.NotProvisioned)
    private val storage = RuntimeStorage(appContext)
    private val agentd = AgentdClient(storage.agentSocket)
    private val lifecycleMutex = Mutex()
    private val processLifetime = QemuProcessLifetime()
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generation = AtomicLong()

    @Volatile private var startingJob: Job? = null
    private var exitMonitor: Job? = null
    private var serialLogger: SerialConsoleLogger? = null

    override fun state(): StateFlow<RuntimeState> = runtimeState.asStateFlow()

    /** True when a complete verified boot set is already installed in app-private storage. */
    fun isProvisioned(): Boolean = storage.hasHeadlessBootSet() || storage.hasUefiBootSet()

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

    override suspend fun start(): Result<Unit> = lifecycleMutex.withLock {
        if (runtimeState.value == RuntimeState.Ready && NativeQemu.isRunning()) {
            return@withLock Result.success(Unit)
        }

        val callerJob = currentCoroutineContext()[Job]
        startingJob = callerJob
        var nativeStarted = false
        try {
            storage.ensureDirectories()
            // QEMU's own data files, refreshed from the APK every start. Not part of provisioning:
            // a device that already has its disks never provisions again, and would keep whatever
            // the build it was first installed from happened to ship.
            storage.installQemuData()
            check(storage.hasHeadlessBootSet() || storage.hasUefiBootSet()) {
                "No complete verified guest image is installed yet"
            }
            // A process that has already hosted a VM run cannot host another; see
            // [QemuProcessLifetime]. `:computer` retires itself after a run, so reaching this is a
            // bug rather than a user's problem — but saying so beats aborting the process.
            check(processLifetime.canStart()) {
                "This computer process has already been used once and must be replaced"
            }
            runtimeState.value = RuntimeState.Starting

            if (!NativeQemu.isRunning()) {
                storage.removeStaleSockets()
                NativeQemu.start(
                    QemuCommand.boot(storage).toTypedArray(),
                    storage.privateRoot.absolutePath,
                )?.let { error(it) }
                nativeStarted = true
            }

            runtimeState.value = RuntimeState.Connecting
            val qmpStatus = awaitQmp()
            check(NativeQemu.isRunning() && qmpStatus.running) {
                "QEMU status is ${qmpStatus.status}"
            }
            Log.i(TAG, "QMP confirmed running guest")
            startDebugSerialLogger()
            awaitAgent()
            Log.i(TAG, "Guest agent confirmed ready")

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
    override suspend fun snapshot(): SnapshotId = unavailable("Snapshots")
    override suspend fun restore(snapshot: SnapshotId): Unit = unavailable("Snapshot restore")
    override suspend fun forwardPort(request: PortForwardRequest): PortForward = unavailable("Port forwarding")
    override suspend fun suspendRuntime(): Unit = unavailable("Suspend")
    override suspend fun resumeRuntime(): Unit = unavailable("Resume")

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
        const val TAG = "BoxRuntime"
        const val QMP_ATTEMPTS = 80
        const val QMP_RETRY_MILLIS = 250L
        const val AGENT_ATTEMPTS = 180
        const val AGENT_RETRY_MILLIS = 1_000L
        const val HEALTH_TIMEOUT_MILLIS = 2_500L
        const val EXEC_TRANSPORT_GRACE_SECONDS = 5
        const val MAX_EXEC_TIMEOUT_SECONDS = 900
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        const val MAX_LIST_ENTRIES = 2_000
        const val EXIT_POLL_MILLIS = 250L
        const val STOP_TIMEOUT_MILLIS = 15_000L
    }
}
