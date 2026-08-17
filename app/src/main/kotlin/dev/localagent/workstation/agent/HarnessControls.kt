package dev.localagent.workstation.agent

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import dev.localagent.runtime.qemu.IAgentSession
import dev.localagent.runtime.qemu.IRuntimeControl
import dev.localagent.runtime.qemu.RuntimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Product capabilities a harness can expose without teaching Android a provider protocol. */
data class HarnessCapabilities(
    val account: Boolean = false,
    val models: Boolean = false,
    val boxTools: Boolean = false,
    val externalServices: Set<String> = emptySet(),
    /** Optional short-lived guest helper used for account/model controls. */
    val controlCommand: List<String> = emptyList(),
) {
    val hasSettings: Boolean get() = (account || models) && controlCommand.isNotEmpty()
}

data class HarnessModelOption(
    val id: String,
    val label: String,
    val summary: String,
    val isDefault: Boolean,
)

sealed interface HarnessAccountState {
    data object Unknown : HarnessAccountState
    data object SignedOut : HarnessAccountState
    data class SignedIn(val account: String?, val plan: String?) : HarnessAccountState
    data class DeviceCode(val verificationUrl: String, val userCode: String) : HarnessAccountState
    data class Failed(val message: String) : HarnessAccountState
}

data class HarnessControlsState(
    val visible: Boolean = false,
    val harnessId: String? = null,
    val harnessName: String? = null,
    val loading: Boolean = false,
    val account: HarnessAccountState = HarnessAccountState.Unknown,
    val models: List<HarnessModelOption> = emptyList(),
    val selectedModel: String? = null,
    val error: String? = null,
)

/**
 * Explicit, process-scoped bridge to a harness's product controls.
 *
 * It never runs while history/settings are merely rendered. [show] is a user action; only then does
 * it bind the already-running computer and launch one ephemeral guest helper. A harness chooses the
 * helper through [HarnessCapabilities.controlCommand], so this class never branches on provider.
 */
class HarnessControls(private val application: Application) {
    private val mutable = MutableStateFlow(HarnessControlsState())
    val state: StateFlow<HarnessControlsState> = mutable.asStateFlow()

    private var bound = false
    private var control: IRuntimeControl? = null
    private var live: IAgentSession? = null
    private var operation: String? = null
    private var command: List<String> = emptyList()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            control = IRuntimeControl.Stub.asInterface(binder)
            if (mutable.value.visible) refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            control = null
            live = null
            if (mutable.value.visible) mutable.value = mutable.value.copy(
                loading = false,
                error = "The computer disconnected.",
            )
        }
    }

    fun show(harness: HarnessDescriptor, computerReady: Boolean) {
        if (!harness.capabilities.hasSettings) return
        command = harness.capabilities.controlCommand
        mutable.value = HarnessControlsState(
            visible = true,
            harnessId = harness.id,
            harnessName = harness.name,
            loading = computerReady,
            error = if (computerReady) null else "Open your box to manage ${harness.name}.",
        )
        if (!computerReady) return
        if (bound) refresh() else {
            bound = application.bindService(
                Intent(application, RuntimeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) mutable.value = mutable.value.copy(loading = false, error = "Could not reach the computer.")
        }
    }

    fun dismiss() {
        cancelLogin(silent = true)
        mutable.value = mutable.value.copy(visible = false, error = null)
        if (bound) runCatching { application.unbindService(connection) }
        bound = false
        control = null
        command = emptyList()
    }

    fun refresh() = runOperation("overview")
    fun beginSignIn() = runOperation("account-login", longLived = true)
    fun signOut() = runOperation("account-logout")
    fun selectModel(id: String?) = runOperation(if (id == null) "model-clear" else "model-set", argument = id)

    fun cancelLogin() = cancelLogin(silent = false)

    private fun cancelLogin(silent: Boolean) {
        val session = live ?: return
        val line = JSONObject().put("type", "cancel").toString() + "\n"
        runCatching { session.write(line.toByteArray()) }.onFailure { runCatching { session.cancel() } }
        if (!silent) mutable.value = mutable.value.copy(loading = true, error = null)
    }

    private fun runOperation(name: String, argument: String? = null, longLived: Boolean = false) {
        val runtime = control ?: return mutable.let {
            it.value = it.value.copy(loading = false, error = "The computer is not ready.")
        }
        if (live != null) return
        operation = name
        mutable.value = mutable.value.copy(loading = true, error = null)
        val events = LineBuffer()
        runCatching {
            runtime.openEphemeralSession(
                "harness-control-${System.nanoTime()}",
                buildList {
                    addAll(command)
                    add(name)
                    argument?.let(::add)
                }.toTypedArray(),
                WORKSPACE,
                Bundle().apply {
                    putString("HOME", GUEST_HOME)
                    putString("BOX_SESSION_CWD", WORKSPACE)
                },
                object : GuestSessionCallback() {
                    override fun onAttached(session: IAgentSession?, logPath: String) {
                        if (longLived) live = session
                    }

                    override fun onData(offset: Long, chunk: ByteArray) {
                        events.absorb(chunk.toString(Charsets.UTF_8), ::handleEvent)
                    }

                    // Credential-bearing helper diagnostics are never Android logs or transcripts.
                    override fun onDiagnostic(text: String) = Unit

                    override fun onClosed(exitCode: Int, error: String?) {
                        if (longLived) live = null
                        operation = null
                        val current = mutable.value
                        if (!current.visible) return
                        mutable.value = if (exitCode == 0) {
                            current.copy(loading = false)
                        } else {
                            current.copy(
                                loading = false,
                                error = current.error ?: error ?: "${current.harnessName ?: "Harness"} settings request failed.",
                            )
                        }
                    }
                },
            )
        }.onFailure {
            live = null
            operation = null
            mutable.value = mutable.value.copy(loading = false, error = "Could not start the harness settings helper.")
        }
    }

    private fun handleEvent(line: String) {
        val event = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (event.optString("type")) {
            "account_state" -> mutable.value = mutable.value.copy(
                account = if (event.optString("state") == "signed_in") {
                    HarnessAccountState.SignedIn(
                        event.optString("account").ifBlank { null },
                        event.optString("plan").ifBlank { null },
                    )
                } else HarnessAccountState.SignedOut,
                error = null,
            )
            "auth_device_code" -> mutable.value = mutable.value.copy(
                loading = false,
                account = HarnessAccountState.DeviceCode(
                    event.optString("verificationUrl"),
                    event.optString("userCode"),
                ),
                error = null,
            )
            "auth_cancelled" -> mutable.value = mutable.value.copy(
                loading = false,
                account = HarnessAccountState.SignedOut,
                error = null,
            )
            "auth_failed" -> {
                val message = event.optString("message").ifBlank { "Sign-in failed." }
                mutable.value = mutable.value.copy(loading = false, account = HarnessAccountState.Failed(message), error = message)
            }
            "control_failed" -> mutable.value = mutable.value.copy(
                loading = false,
                error = event.optString("message").ifBlank { "Harness settings request failed." },
            )
            "model_catalog" -> {
                val array = event.optJSONArray("models")
                val models = buildList {
                    if (array != null) for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optString("id")
                        if (id.isBlank()) continue
                        add(HarnessModelOption(
                            id = id,
                            label = item.optString("label").ifBlank { id },
                            summary = item.optString("summary"),
                            isDefault = item.optBoolean("isDefault"),
                        ))
                    }
                }
                mutable.value = mutable.value.copy(
                    loading = false,
                    models = models,
                    selectedModel = event.optString("selected").ifBlank { null },
                    error = null,
                )
            }
            "model_selected" -> mutable.value = mutable.value.copy(
                loading = false,
                selectedModel = event.optString("selected").ifBlank { null },
                error = null,
            )
        }
    }

    private companion object {
        const val WORKSPACE = "/workspace"
        const val GUEST_HOME = "/home/agent"
    }
}
