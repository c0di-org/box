package dev.localagent.runtime.qemu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import dev.localagent.runtime.api.ExecRequest
import dev.localagent.runtime.api.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process boundary for QEMU. The manifest pins this service to `:computer`, so a native VM
 * failure cannot take down the Compose activity. It becomes a foreground service only after a
 * real user-visible VM is running.
 */
class RuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
    }

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

    /** Exception messages reach the UI verbatim, so never surface an empty one. */
    private fun Throwable.readableMessage(fallback: String): String =
        message?.takeIf(String::isNotBlank) ?: fallback

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
        private const val CHANNEL_ID = "local_agent_runtime"
        private const val NOTIFICATION_ID = 1001
    }
}
