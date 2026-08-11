package dev.localagent.runtime.qemu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import dev.localagent.runtime.api.ExecRequest
import dev.localagent.runtime.api.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process boundary for QEMU. The manifest pins this service to `:computer`, so a native VM
 * failure cannot take down the Compose activity. It becomes a foreground service only after a
 * real user-visible VM is running.
 */
class RuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A process retires once; a second settled state must not queue another kill. */
    private val retiring = AtomicBoolean(false)
    private val runtime by lazy { QemuTcgRuntime(applicationContext) }

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

        override fun openAgentSession(
            sessionId: String,
            command: Array<out String>,
            workingDirectory: String,
            environment: Bundle?,
            callback: IAgentSessionCallback,
        ) {
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
            // Any previous attempt is torn down rather than resumed: a half-finished sign-in
            // should be restarted, never re-entered.
            sessions.remove(sessionId)?.cancel()
            val host = AgentSessionHost(sessionId, logFile = null, scope = scope)
            sessions[sessionId] = host
            host.start(runtime, command.toList(), workingDirectory, environment.toStringMap(), callback)
        }

        override fun closeAgentSession(sessionId: String) {
            sessions.remove(sessionId)?.cancel()
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
        // The VM lives in `:computer`; the Compose process cannot observe this StateFlow directly.
        scope.launch { runtime.state().collect(::publishState) }
    }

    override fun onDestroy() {
        unregisterReceiver(queryReceiver)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (intent?.action == ACTION_START) scope.launch {
            // Starting before the APK assets are installed is what "no verified guest image"
            // means; provisioning is part of the same user gesture.
            if (!runtime.isProvisioned()) {
                Log.i(TAG, "Provisioning guest image")
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
        if (intent?.action == ACTION_EXEC_PROBE) scope.launch {
            runtime.start()
                .onSuccess {
                    runCatching {
                        runtime.exec(ExecRequest(listOf("/bin/sh", "-lc", "printf device-agentd-ok")))
                    }.onSuccess { result ->
                        Log.i(TAG, "Guest command probe: exit=${result.exitCode} stdout=${result.stdout}")
                    }.onFailure { Log.e(TAG, "Guest command probe failed", it) }
                }
                .onFailure { Log.e(TAG, "QEMU failed to start for command probe", it) }
        }
        return START_NOT_STICKY
    }

    private fun publishState(state: RuntimeState) {
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

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            "Local Agent runtime",
            NotificationManager.IMPORTANCE_LOW,
        ))
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Local Agent VM")
            .setContentText("Private Linux workspace is running")
            .setOngoing(true)
            .build()
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * The other half of "start work and pocket the phone".
     *
     * Posted from `:computer` because this process is the one that survives: the Compose process is
     * routinely killed while an agent keeps working, and a notification it was supposed to post
     * would die with it.
     */
    private fun notifySession(sessionId: String, signal: SessionSignals.Signal) {
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

        // Launching by package keeps the dependency pointing one way: `:app` knows about the
        // runtime, and the runtime never learns the name of an Activity.
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = open?.let {
            PendingIntent.getActivity(
                this,
                sessionId.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = Notification.Builder(this, SESSION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
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

    companion object {
        /** Binder transactions are capped near 1 MB; keep well clear for text the UI can show. */
        private const val MAX_STREAM_CHARS = 128 * 1024
        private const val MAX_PREVIEW_CHARS = 128 * 1024

        const val ACTION_START = "dev.localagent.runtime.qemu.START"
        const val ACTION_STOP = "dev.localagent.runtime.qemu.STOP"
        const val ACTION_EXEC_PROBE = "dev.localagent.runtime.qemu.EXEC_PROBE"

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
