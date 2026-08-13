package dev.localagent.runtime.qemu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ApplicationInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.localagent.runtime.api.ExecRequest
import dev.localagent.runtime.api.PortForward
import dev.localagent.runtime.api.PortForwardRequest
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.runtime.qemu.shared.SharedFolderBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Process boundary for QEMU. The manifest pins this service to `:computer`, so a native VM
 * failure cannot take down the Compose activity. It becomes a foreground service only after a
 * real user-visible VM is running.
 */
class RuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Forwards handed out to the UI process, so a later release can close the right one.
     *
     * Held here rather than in the UI because the UI process is the one Android kills: a forward
     * whose only handle died with a Compose process would stay open until the VM stopped.
     */
    private val forwards = java.util.concurrent.ConcurrentHashMap<Int, PortForward>()

    /** A process retires once; a second settled state must not queue another kill. */
    private val retiring = AtomicBoolean(false)

    /**
     * When the guest was last asked for anything, and the watcher that acts on the answer.
     *
     * An emulated ARM64 machine runs until something tells it not to, and until now nothing did:
     * a box opened for one question stayed open all day. The reason there was no idle timer is
     * that the only thing one could have done was *stop* the box — which buys battery by selling
     * the user a boot, and the boot was the expensive thing. Saving the guest instead is what
     * makes this worth having: see [QemuTcgRuntime.suspendRuntime].
     */
    private val lastGuestActivity = AtomicLong(SystemClock.elapsedRealtime())
    private var idleWatch: Job? = null
    private val runtime by lazy { QemuTcgRuntime(applicationContext) }

    /**
     * The shared folder, kept level with the guest's copy of it.
     *
     * Driven from here because this is the process that is alive whenever the VM is — the UI is
     * routinely killed while an agent keeps working, and that is precisely when a file the agent
     * produced needs to reach the phone.
     */
    private val sharedFolder by lazy { SharedFolderBridge(applicationContext, runtime, scope) }

    /**
     * The UI process holds only this interface. A local Binder cannot cross `:computer`, so guest
     * work is dispatched here and results are handed back through oneway callbacks.
     */
    private val control = object : IRuntimeControl.Stub() {
        override fun exec(
            command: Array<out String>,
            workingDirectory: String,
            timeoutSeconds: Int,
            callback: IExecCallback,
        ) {
            touch()
            scope.launch {
                try {
                    val result = runtime.exec(
                        ExecRequest(command.toList(), workingDirectory, timeoutSeconds),
                    )
                    val stdout = result.stdout.takeLast(MAX_STREAM_CHARS)
                    val stderr = result.stderr.takeLast(MAX_STREAM_CHARS)
                    callback.onResult(
                        result.exitCode,
                        stdout,
                        stderr,
                        stdout.length < result.stdout.length || stderr.length < result.stderr.length,
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Guest command failed", error)
                    runCatching { callback.onError(error.readableMessage("Guest command failed")) }
                }
            }
        }

        override fun listFiles(path: String, callback: IFileListCallback) {
            touch()
            scope.launch {
                try {
                    val entries = runtime.listFiles(path)
                    callback.onResult(
                        entries.map { it.path }.toTypedArray(),
                        entries.map { it.name }.toTypedArray(),
                        entries.map { it.isDirectory }.toBooleanArray(),
                        entries.map { it.size }.toLongArray(),
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Guest listing failed", error)
                    runCatching { callback.onError(error.readableMessage("Could not list $path")) }
                }
            }
        }

        override fun readFile(path: String, callback: IFileReadCallback) {
            touch()
            scope.launch {
                try {
                    val bytes = runtime.readFile(path)
                    val text = bytes.toString(Charsets.UTF_8)
                    check(!text.contains('\u0000')) { "This looks like a binary file" }
                    val preview = text.take(MAX_PREVIEW_CHARS)
                    callback.onResult(
                        path,
                        path.substringAfterLast('/').ifBlank { path },
                        preview,
                        preview.length < text.length,
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Guest file read failed", error)
                    runCatching { callback.onError(error.readableMessage("Could not open $path")) }
                }
            }
        }

        override fun writeFile(path: String, data: ByteArray, callback: IWriteCallback) {
            touch()
            scope.launch {
                try {
                    runtime.writeFile(path, data)
                    callback.onResult(data.size.toLong())
                } catch (error: Exception) {
                    // Deliberately not logging the path's contents: this is how credentials reach
                    // the guest, and a stack trace is the one place they must never appear.
                    Log.e(TAG, "Guest file write failed")
                    runCatching { callback.onError(error.readableMessage("Could not write $path")) }
                }
            }
        }

        override fun forwardPort(guestPort: Int, callback: IPortForwardCallback) {
            touch()
            scope.launch {
                try {
                    val forward = runtime.forwardPort(
                        PortForwardRequest(guestPort, purpose = "preview"),
                    )
                    forwards[guestPort] = forward
                    callback.onForwarded(guestPort, "http://127.0.0.1:${forward.localPort}/")
                } catch (error: Exception) {
                    Log.w(TAG, "Could not forward guest port $guestPort", error)
                    runCatching {
                        callback.onError(error.readableMessage("Could not open a preview"))
                    }
                }
            }
        }

        override fun releasePort(guestPort: Int) {
            touch()
            scope.launch {
                // Nothing is reported back. The caller is tidying up, and the honest answer to a
                // failure here is that the forward dies with the VM anyway.
                runCatching { forwards.remove(guestPort)?.close() }
            }
        }

        override fun openAgentSession(
            sessionId: String,
            command: Array<out String>,
            workingDirectory: String,
            environment: Bundle?,
            callback: IAgentSessionCallback,
        ) {
            touch()
            val existing = sessions[sessionId]
            if (existing != null && existing.isRunning) {
                // Re-opening a live session is a resumed UI, not a second agent.
                existing.attach(callback)
                return
            }
            val host = AgentSessionHost(sessionId, logFileFor(sessionId), scope, ::notifySession)
            sessions[sessionId] = host
            host.start(
                runtime,
                command.toList(),
                workingDirectory,
                environment.toStringMap(),
                callback,
            )
        }

        override fun attachAgentSession(sessionId: String, callback: IAgentSessionCallback) {
            touch()
            val host = sessions[sessionId]
            if (host != null) {
                host.attach(callback)
                return
            }
            // Nothing running under that id. The log still holds everything the agent did, so an
            // agent that finished while the UI was dead is read back rather than lost.
            val log = logFileFor(sessionId)
            runCatching {
                callback.onAttached(null, log.absolutePath)
                callback.onClosed(-1, null)
            }
        }

        override fun openEphemeralSession(
            sessionId: String,
            command: Array<out String>,
            workingDirectory: String,
            environment: Bundle?,
            callback: IAgentSessionCallback,
        ) {
            touch()
            // Any previous attempt is torn down rather than resumed: a half-finished sign-in
            // should be restarted, never re-entered.
            sessions.remove(sessionId)?.cancel()
            val host = AgentSessionHost(sessionId, logFile = null, scope = scope)
            sessions[sessionId] = host
            host.start(runtime, command.toList(), workingDirectory, environment.toStringMap(), callback)
        }

        override fun closeAgentSession(sessionId: String) {
            touch()
            sessions.remove(sessionId)?.cancel()
        }

        /**
         * Deliberately not `touch()`ed. Every other call here is the user doing something, and
         * touching keeps the box awake for it. This one is the window being measured — it fires on
         * rotations and folds, including ones nobody is watching, and an idle box should be
         * allowed to go to sleep while its screen is on a display that changed shape.
         */
        override fun setDisplaySize(width: Int, height: Int) {
            scope.launch {
                runCatching { runtime.setDisplaySize(width, height) }
                    // Nothing above can act on this. The desktop is a courtesy on a machine whose
                    // real work is the agent, and a guest too early in its boot to have an X
                    // server is the ordinary case, not a fault.
                    .onFailure { Log.i(TAG, "guest kept its screen size: ${it.message}") }
            }
        }
    }

    /** Live sessions, keyed by Box's own session id so a restarted UI can find them again. */
    private val sessions = ConcurrentHashMap<String, AgentSessionHost>()

    private fun logFileFor(sessionId: String): File {
        val directory = File(filesDir, AGENT_SESSION_DIRECTORY).apply { mkdirs() }
        // Box generates these ids, but a path separator arriving here would escape the directory.
        return File(directory, sessionId.replace(UNSAFE_ID, "_") + ".ndjson")
    }

    private fun Bundle?.toStringMap(): Map<String, String> =
        this?.keySet().orEmpty().mapNotNull { key ->
            this?.getString(key)?.let { key to it }
        }.toMap()

    override fun onBind(intent: Intent?): IBinder = control

    /**
     * Answers a restarted UI process. This receiver is registered at runtime rather than in the
     * manifest, so a query cannot resurrect `:computer`: silence means no VM is running.
     */
    private val queryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = publishState(runtime.state().value)
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            queryReceiver,
            IntentFilter(ACTION_QUERY_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Where the runtime is sitting before anything has been asked of it. Read here, on the
        // main thread, so it is settled before any `onStartCommand` can move it.
        val resting = runtime.state().value
        // The VM lives in `:computer`; the Compose process cannot observe this StateFlow directly.
        scope.launch {
            var first = true
            runtime.state().collect { state ->
                // A process created by a *bind* has done nothing and has nothing to announce — and
                // the UI binds a stopped computer on purpose, to read a closed box's session logs.
                // Broadcasting where it happens to be sitting would answer the Open the user
                // pressed a second ago with "the box is closed", and take the progress they are
                // watching away with it. A state the runtime actually entered is always published;
                // only this one resting value, and only once, is kept quiet.
                val settling = first && state == resting
                first = false
                if (!settling) publishState(state)
            }
        }
    }

    override fun onDestroy() {
        sharedFolder.stop()
        unregisterReceiver(queryReceiver)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (intent?.action == ACTION_START) scope.launch {
            // Starting before the APK assets are installed is what "no verified guest image"
            // means; provisioning is part of the same user gesture.
            if (!runtime.isProvisioned()) {
                // Which image, and which one it is replacing. On a device that already had a box
                // this line is the difference between "a new version is being installed" and the
                // old silence, where a rebuilt image was skipped with nothing said about it.
                Log.i(TAG, "Provisioning guest image ${runtime.bundledImage()} " +
                    "(installed: ${runtime.installedImage() ?: "none"})")
                val provisioned = runtime.provision()
                if (provisioned.isFailure) {
                    Log.e(TAG, "Guest provisioning failed", provisioned.exceptionOrNull())
                    return@launch
                }
                Log.i(TAG, "Guest image provisioned")
            }
            Log.i(TAG, "Starting QEMU runtime")
            runtime.start()
                .onSuccess { Log.i(TAG, "QEMU runtime launch accepted") }
                .onFailure { Log.e(TAG, "QEMU failed to start", it) }
        }
        if (intent?.action == ACTION_STOP) scope.launch {
            runtime.stop()
                .onFailure { Log.e(TAG, "QEMU failed to stop", it) }
                .onSuccess { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        if (intent?.action == ACTION_SUSPEND) scope.launch {
            runCatching { runtime.suspendRuntime() }
                .onSuccess {
                    Log.i(TAG, "The box was put away")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                // Deliberately not falling back to a stop. Putting the box away failed, so the
                // box is still open and still working; closing it would turn a saved three
                // minutes into a lost session, which is the trade this whole path exists to undo.
                .onFailure { error ->
                    Log.e(TAG, "Could not put the box away", error)
                    // Unless there is no box here to keep alive. Asking a saved box to save again
                    // arrives in a fresh process that never ran a VM, and leaving that one up
                    // would put "Your box is open" in the shade over nothing at all.
                    if (runtime.state().value != RuntimeState.Ready) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
        }
        if (intent?.action == ACTION_REPROVISION_IMAGE) scope.launch {
            // Debuggable builds only, on the same reasoning as the exec probe below: the service
            // is not exported, so only this UID can reach it either way, and the build check is
            // what keeps a released app from carrying a "throw away the system disk" Intent.
            //
            // It cannot reach `/workspace` even here — that is refused in GuestImageInstall, not
            // gated on the build — so the worst this can cost a developer is a boot.
            if (!isDebuggable()) {
                Log.w(TAG, "Ignoring a reprovision request in a non-debuggable build")
                return@launch
            }
            Log.i(TAG, "Reinstalling guest image ${runtime.bundledImage()}, keeping the workspace")
            runtime.reprovisionImage()
                .onSuccess { Log.i(TAG, "Guest image reinstalled") }
                .onFailure { Log.e(TAG, "Reinstalling the guest image failed", it) }
        }
        if (intent?.action == ACTION_EXEC_PROBE) scope.launch {
            // A caller-supplied command, but only in a debuggable build. Verifying what the guest
            // actually did — whether a unit came up, whether a device node exists — otherwise means
            // reading a serial console that arrives in fragments and drops most of itself. The
            // service is not exported, so only this UID can reach it either way; the build check is
            // what keeps a released app from carrying a general-purpose guest shell on an Intent.
            val requested = if (isDebuggable()) intent.getStringExtra(EXTRA_PROBE_COMMAND) else null
            val command = requested ?: "printf device-agentd-ok"
            runtime.start()
                .onSuccess {
                    runCatching {
                        runtime.exec(ExecRequest(listOf("/bin/sh", "-lc", command)))
                    }.onSuccess { result ->
                        Log.i(TAG, "Guest command probe: exit=${result.exitCode} stdout=${result.stdout}")
                        if (result.stderr.isNotBlank()) Log.i(TAG, "Guest probe stderr: ${result.stderr}")
                    }.onFailure { Log.e(TAG, "Guest command probe failed", it) }
                }
                .onFailure { Log.e(TAG, "QEMU failed to start for command probe", it) }
        }
        return START_NOT_STICKY
    }

    private fun isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun touch() = lastGuestActivity.set(SystemClock.elapsedRealtime())

    /**
     * Puts an unused box away by itself.
     *
     * "Unused" is deliberately conservative, because the one thing a suspend cannot carry across
     * is an agent that is still working — agentd kills its children when its host goes away, and a
     * restored guest is told its host went away. So a running session is not idleness however
     * quiet it looks, and its presence keeps the clock reset rather than merely deferring a check.
     *
     * What this costs the user when it gets it wrong is the thing worth measuring: a saved box
     * comes back in about a second, against the 86–116 s a cold boot took on the same phone. That
     * ratio is the only reason this is allowed to act without asking.
     */
    private fun watchForIdle() {
        if (idleWatch?.isActive == true) return
        idleWatch = scope.launch {
            while (isActive) {
                delay(IDLE_POLL_MILLIS)
                // A live forward counts, and it is the one kind of use this timer cannot see any
                // other way: a preview the user is reading goes straight through QEMU's network
                // stack to the guest and never touches the binder, so every check below would
                // report a box nobody wants — while the user watches it close.
                if (sessions.values.any { it.isRunning } || forwards.isNotEmpty()) {
                    touch()
                    continue
                }
                val quietFor = SystemClock.elapsedRealtime() - lastGuestActivity.get()
                if (quietFor < IDLE_TIMEOUT_MILLIS) continue
                Log.i(TAG, "Nothing has needed the box for ${quietFor / 1_000}s; putting it away")
                // Deliberately not awaited here, and this is not a detail. Saving the box moves it
                // out of Ready, [publishState] answers that by cancelling this watcher, and if the
                // save were running *inside* this job that cancellation would land on the save —
                // which is exactly what it did: the box stopped half way, quiesced but unsaved.
                idleWatch = null
                scope.launch {
                    runCatching { runtime.suspendRuntime() }
                        .onFailure { Log.w(TAG, "Could not put the idle box away", it) }
                }
                return@launch
            }
        }
    }

    private fun publishState(state: RuntimeState) {
        if (state == RuntimeState.Ready) {
            touch()
            watchForIdle()
        } else {
            idleWatch?.cancel()
            idleWatch = null
        }
        sharedFolder.onRuntimeState(state)
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, RuntimeStateCodec.encode(state)),
        )
        if (runtime.isSpent(state) && retiring.compareAndSet(false, true)) retire()
    }

    /**
     * Ends `:computer` once its single QEMU run is over.
     *
     * QEMU cannot be initialised twice in a process, so a process that has hosted a VM is of no
     * further use — keeping it alive only guarantees that the user's next "start" lands on a
     * process that must refuse it. `START_NOT_STICKY` is what makes this safe: Android will not
     * resurrect the service on its own, and the next start arrives as a fresh `startService`.
     *
     * The pause before the kill is the whole trick. The UI holds a binding while the computer is
     * meant to be alive, so it is told about this process dying — and it has to be able to tell an
     * ordinary retirement from a VM abort. The only thing separating them is that a retirement
     * announces a settled state first, so the broadcast is given time to be delivered before the
     * process that sent it disappears. Losing that race costs a false "the computer stopped
     * unexpectedly"; it never costs correctness, because the computer really has stopped.
     */
    private fun retire() {
        Log.i(TAG, "The VM has exited; retiring this computer process")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        scope.launch {
            delay(RETIRE_GRACE_MILLIS)
            Log.i(TAG, "Computer process retired")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * The one notification the user cannot dismiss, so it has to be Box's.
     *
     * It used to introduce itself as "Local Agent VM" under a warning triangle — a name nobody
     * installed, wearing the icon Android uses for something going wrong, permanently in the shade
     * of a phone whose owner had only ever seen the word Box.
     *
     * It also carries the way out. An emulated ARM64 machine costs real battery and runs until it
     * is told not to, and the only other route to closing it is two menus deep inside the app —
     * which is the wrong place for a decision someone makes while looking at their battery screen.
     */
    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            "Your box",
            NotificationManager.IMPORTANCE_LOW,
        ))
        val close = serviceAction(ACTION_STOP, requestCode = 0)
        val putAway = serviceAction(ACTION_SUSPEND, requestCode = 1)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_box_notification)
            .setContentTitle("Your box is open")
            .setContentText("Debian is running on this phone.")
            .setOngoing(true)
            .apply { openAppIntent()?.let(::setContentIntent) }
            // Two ways out, because they are not the same offer and the shade is where the
            // choice gets made. Putting the box away saves the guest as it stands and reopens in
            // seconds; closing it ends the machine, and the next box has to boot from nothing —
            // about three minutes of an emulated CPU waiting on emulated hardware. The cheap one
            // goes first because it is almost always the one that was meant.
            .addAction(Notification.Action.Builder(null, "Pause your box", putAway).build())
            .addAction(Notification.Action.Builder(null, "Close your box", close).build())
            .build()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RuntimeService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The other half of "start work and pocket the phone".
     *
     * Posted from `:computer` because this process is the one that survives: the Compose process is
     * routinely killed while an agent keeps working, and a notification it was supposed to post
     * would die with it.
     */
    private fun notifySession(sessionId: String, signal: SessionSignals.Signal) {
        // An agent that has stopped is an agent whose files are finished being written. This is
        // the trigger that carries anything it left in `/workspace/shared` out to the phone; see
        // [SharedFolderBridge] for why this moment and not a poll.
        if (signal is SessionSignals.Signal.Finished) sharedFolder.onSessionFinished()

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                SESSION_CHANNEL_ID,
                "Agent sessions",
                // Higher than the runtime's own channel on purpose. This one is the product
                // promise; the ongoing "VM is running" notice is furniture.
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )

        val (title, body) = when (signal) {
            is SessionSignals.Signal.NeedsYou -> "Box needs you" to signal.label
            is SessionSignals.Signal.Finished -> if (signal.failed) {
                "The agent stopped" to (signal.summary ?: "It could not finish.")
            } else {
                "The agent finished" to (signal.summary ?: "Your task is done.")
            }
        }

        val pending = openAppIntent(sessionId.hashCode())

        val notification = Notification.Builder(this, SESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_box_notification)
            .setContentTitle(title)
            .setContentText(body.take(MAX_NOTIFICATION_CHARS))
            .setStyle(Notification.BigTextStyle().bigText(body.take(MAX_NOTIFICATION_CHARS)))
            .setAutoCancel(true)
            // The summary is the user's own work, but a lock screen is a shoulder-surfing surface.
            // Respect whatever they chose for private content rather than deciding for them.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .apply { pending?.let(::setContentIntent) }
            .build()

        // Keyed by session, so "needs you" is replaced by "finished" rather than stacking up.
        runCatching { manager.notify(sessionId.hashCode(), notification) }
            .onFailure { Log.w(TAG, "Could not post a session notification", it) }
    }

    /**
     * The way back into Box from the shade.
     *
     * Launching by package keeps the dependency pointing one way: `:app` knows about the runtime,
     * and the runtime never learns the name of an Activity.
     */
    private fun openAppIntent(requestCode: Int = 0): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?.let {
                PendingIntent.getActivity(
                    this,
                    requestCode,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

    companion object {
        /** Binder transactions are capped near 1 MB; keep well clear for text the UI can show. */
        private const val MAX_STREAM_CHARS = 128 * 1024
        private const val MAX_PREVIEW_CHARS = 128 * 1024

        const val ACTION_START = "dev.localagent.runtime.qemu.START"
        const val ACTION_STOP = "dev.localagent.runtime.qemu.STOP"

        /**
         * Save the guest and end the VM, so the next start is seconds rather than minutes.
         *
         * The difference from [ACTION_STOP] is what the user gets back afterwards, not what they
         * give up now: both end the emulated machine and both stop costing battery.
         */
        const val ACTION_SUSPEND = "dev.localagent.runtime.qemu.SUSPEND"
        const val ACTION_EXEC_PROBE = "dev.localagent.runtime.qemu.EXEC_PROBE"

        /**
         * Reinstall the guest image, keeping `/workspace`. Debuggable builds only.
         *
         * Reachable through the debug-only VmProbeActivity, which forwards whatever action it is
         * given, so this needs no new surface:
         *
         * ```
         * adb shell am start -n dev.localagent.workstation.stock/dev.localagent.workstation.VmProbeActivity          *   --es runtime_action dev.localagent.runtime.qemu.REPROVISION_IMAGE
         * ```
         */
        const val ACTION_REPROVISION_IMAGE = "dev.localagent.runtime.qemu.REPROVISION_IMAGE"

        /** Debuggable builds only. See the probe branch in [onStartCommand]. */
        const val EXTRA_PROBE_COMMAND = "probe_command"

        /** App-private state broadcast. Always sent with an explicit package, never exported. */
        const val ACTION_STATE = "dev.localagent.runtime.qemu.STATE"

        /** Asks a live runtime process to re-announce its state to a restarted UI process. */
        const val ACTION_QUERY_STATE = "dev.localagent.runtime.qemu.QUERY_STATE"
        const val EXTRA_STATE = "state"

        private const val TAG = "LocalAgentRuntime"

        /**
         * How long the final state broadcast is given to reach the UI before this process ends.
         * Long enough for an ordinary Binder round trip, short enough that a user pressing start
         * again immediately still lands on a fresh process.
         */
        private const val RETIRE_GRACE_MILLIS = 750L

        /**
         * How long a box may sit untouched before it is saved and closed.
         *
         * A policy number rather than a technical one, and the first honest guess at it: long
         * enough that stepping away from a task does not close the box behind you, short enough
         * that a box opened for one question is not still emulating an ARM64 machine at bedtime.
         */
        private const val IDLE_TIMEOUT_MILLIS = 15 * 60 * 1_000L
        private const val IDLE_POLL_MILLIS = 30_000L
        private const val CHANNEL_ID = "local_agent_runtime"
        private const val NOTIFICATION_ID = 1001

        /** Separate from the runtime's ongoing notice so the user can silence one and not both. */
        private const val SESSION_CHANNEL_ID = "box_agent_sessions"
        private const val MAX_NOTIFICATION_CHARS = 480

        /** Session logs live beside the VM, in `:computer`'s half of the app's private storage. */
        private const val AGENT_SESSION_DIRECTORY = "agent-sessions"
        private val UNSAFE_ID = Regex("[^A-Za-z0-9_-]")
    }
}

/** Exception messages reach the UI verbatim, so never surface an empty one. */
internal fun Throwable.readableMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: fallback
