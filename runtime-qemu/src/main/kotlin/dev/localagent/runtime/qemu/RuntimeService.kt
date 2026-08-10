package dev.localagent.runtime.qemu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import dev.localagent.runtime.api.ExecRequest
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
    private val binder = RuntimeBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtime by lazy { QemuTcgRuntime(applicationContext) }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (intent?.action == ACTION_START) scope.launch {
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

    inner class RuntimeBinder : Binder() {
        fun runtime(): QemuTcgRuntime = runtime
    }

    companion object {
        const val ACTION_START = "dev.localagent.runtime.qemu.START"
        const val ACTION_STOP = "dev.localagent.runtime.qemu.STOP"
        const val ACTION_EXEC_PROBE = "dev.localagent.runtime.qemu.EXEC_PROBE"
        private const val TAG = "LocalAgentRuntime"
        private const val CHANNEL_ID = "local_agent_runtime"
        private const val NOTIFICATION_ID = 1001
    }
}
