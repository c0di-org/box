package dev.localagent.runtime.qemu

import android.os.Bundle
import dev.localagent.runtime.api.RuntimeFailure
import dev.localagent.runtime.api.RuntimeState

/**
 * RuntimeService owns the VM in `:computer`, so its state cannot reach the Compose process as a
 * StateFlow. This encodes the state model for the private app-internal broadcast that carries it.
 */
object RuntimeStateCodec {
    private const val KEY_KIND = "kind"
    private const val KEY_PROGRESS = "progress"
    private const val KEY_MESSAGE = "message"
    private const val KEY_RECOVERABLE = "recoverable"

    fun encode(state: RuntimeState): Bundle = Bundle().apply {
        when (state) {
            RuntimeState.NotProvisioned -> putString(KEY_KIND, "not_provisioned")
            is RuntimeState.Provisioning -> {
                putString(KEY_KIND, "provisioning")
                putFloat(KEY_PROGRESS, state.progress)
            }
            RuntimeState.Stopped -> putString(KEY_KIND, "stopped")
            RuntimeState.Starting -> putString(KEY_KIND, "starting")
            RuntimeState.Connecting -> putString(KEY_KIND, "connecting")
            RuntimeState.Ready -> putString(KEY_KIND, "ready")
            RuntimeState.Stopping -> putString(KEY_KIND, "stopping")
            RuntimeState.Suspending -> putString(KEY_KIND, "suspending")
            RuntimeState.Suspended -> putString(KEY_KIND, "suspended")
            is RuntimeState.Failed -> {
                putString(KEY_KIND, "failed")
                putString(KEY_MESSAGE, state.reason.message)
                putBoolean(KEY_RECOVERABLE, state.reason.recoverable)
            }
        }
    }

    /** Returns null for an unrecognized payload so the UI keeps its last known state. */
    fun decode(bundle: Bundle): RuntimeState? = when (bundle.getString(KEY_KIND)) {
        "not_provisioned" -> RuntimeState.NotProvisioned
        "provisioning" -> RuntimeState.Provisioning(bundle.getFloat(KEY_PROGRESS))
        "stopped" -> RuntimeState.Stopped
        "starting" -> RuntimeState.Starting
        "connecting" -> RuntimeState.Connecting
        "ready" -> RuntimeState.Ready
        "stopping" -> RuntimeState.Stopping
        "suspending" -> RuntimeState.Suspending
        "suspended" -> RuntimeState.Suspended
        "failed" -> RuntimeState.Failed(
            RuntimeFailure(
                bundle.getString(KEY_MESSAGE).orEmpty().ifBlank { "The Linux workspace failed" },
                bundle.getBoolean(KEY_RECOVERABLE, true),
            ),
        )
        else -> null
    }
}
