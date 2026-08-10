package dev.localagent.runtime.qemu

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
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
        if (intent?.action == ACTION_START) scope.launch {
            Log.i(TAG, "Starting QEMU runtime")
            runtime.start()
                .onSuccess { Log.i(TAG, "QEMU runtime launch accepted") }
                .onFailure { Log.e(TAG, "QEMU failed to start", it) }
        }
        if (intent?.action == ACTION_STOP) scope.launch {
            runtime.stop().onFailure { Log.e(TAG, "QEMU failed to stop", it) }
        }
        return START_NOT_STICKY
    }

    inner class RuntimeBinder : Binder() {
        fun runtime(): QemuTcgRuntime = runtime
    }

    companion object {
        const val ACTION_START = "dev.localagent.runtime.qemu.START"
        const val ACTION_STOP = "dev.localagent.runtime.qemu.STOP"
        private const val TAG = "LocalAgentRuntime"
    }
}
