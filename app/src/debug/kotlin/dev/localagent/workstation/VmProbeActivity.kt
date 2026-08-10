package dev.localagent.workstation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.localagent.runtime.qemu.RuntimeService

/** Attached-device test hook. It is compiled only into debug APKs and never exposes a shell. */
class VmProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedAction = intent.getStringExtra(EXTRA_RUNTIME_ACTION) ?: RuntimeService.ACTION_START
        startForegroundService(Intent(this, RuntimeService::class.java).setAction(requestedAction))
        finish()
    }

    companion object {
        const val EXTRA_RUNTIME_ACTION = "runtime_action"
    }
}
