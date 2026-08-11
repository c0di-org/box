package dev.localagent.workstation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.localagent.runtime.qemu.RuntimeService

/**
 * Attached-device test hook, compiled only into debug APKs.
 *
 * It exists because `am startservice` is refused for a background start on modern Android, so a
 * foreground service has to be asked for by something with an Activity to stand behind it.
 */
class VmProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedAction = intent.getStringExtra(EXTRA_RUNTIME_ACTION) ?: RuntimeService.ACTION_START
        val service = Intent(this, RuntimeService::class.java).setAction(requestedAction)
        // Forwarded so a probe can ask the guest something specific. RuntimeService ignores this
        // unless the build is debuggable, which is what keeps it out of a released app.
        intent.getStringExtra(RuntimeService.EXTRA_PROBE_COMMAND)?.let {
            service.putExtra(RuntimeService.EXTRA_PROBE_COMMAND, it)
        }
        startForegroundService(service)
        finish()
    }

    companion object {
        const val EXTRA_RUNTIME_ACTION = "runtime_action"
    }
}
