package dev.localagent.workstation.agent

import android.util.Log
import dev.localagent.runtime.qemu.IAgentSession
import dev.localagent.runtime.qemu.IAgentSessionCallback
import dev.localagent.runtime.qemu.IRuntimeControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Signing in to Claude, from a phone, to a Linux box with no browser.
 *
 * The guest cannot open a browser and the phone cannot run the CLI, so the sign-in is brokered: the
 * harness runs Claude Code's own OAuth handshake in the VM and reports the URL, Box opens it in the
 * phone's browser, and the code the user gets back is handed to the still-running handshake.
 *
 * **This does not drive `claude auth login`, and must not be changed back to.** That command prints
 * the URL but then waits only on a loopback HTTP listener meant for a browser on the same machine;
 * the pasted code goes to a different entry point it never wires to stdin. A code written to its
 * stdin is read by nobody and the process hangs until it is killed — the flow cannot complete. The
 * harness uses the SDK's control protocol instead, which is the same handshake Claude Code's own
 * login screen uses. See `runAuth` in `box-claude-harness.mjs`.
 *
 * Three things this deliberately does *not* do:
 *
 *  - **Nothing is persisted.** It runs as an ephemeral session, so the exchange is never written
 *    to a log. The resulting credential is written by Claude Code itself, inside the guest
 *    filesystem, on this device. It never touches the app's storage or the event stream.
 *  - **Nothing is parsed too precisely.** The URL arrives as a field in a structured event rather
 *    than being scraped out of prose, so a change in CLI wording cannot break it.
 *  - **Box never handles the credential.** The user authorises in their own browser and pastes a
 *    code. Box carries that code to a process and forgets it.
 */
class GuestAuth {

    sealed interface State {
        data object Unknown : State
        data object Checking : State
        data class SignedIn(val account: String?) : State
        data object SignedOut : State
        data object Starting : State

        /** The user must authorise at [url] and bring back a code. */
        data class AwaitingCode(val url: String, val transcript: String) : State
        data class Failed(val message: String) : State
    }

    private val stateFlow = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = stateFlow.asStateFlow()

    private var live: IAgentSession? = null

    /** Whether the guest already holds a usable credential. */
    fun check(control: IRuntimeControl) {
        // `:computer` can reconnect at any time, including while the user is in their browser
        // holding a code. A sign-in already under way is the better answer than asking again.
        if (live != null) return
        stateFlow.value = State.Checking
        val output = StringBuilder()
        runCatching {
            control.openEphemeralSession(
                STATUS_SESSION,
                arrayOf(CLAUDE, "auth", "status", "--json"),
                WORKSPACE,
                guestEnvironment(),
                object : GuestSessionCallback() {
                    override fun onData(offset: Long, chunk: ByteArray) {
                        output.append(chunk.toString(Charsets.UTF_8))
                    }

                    override fun onClosed(exitCode: Int, error: String?) {
                        // A sign-in may have started while this was still asking — on a cold
                        // computer the check can take longer than the user's patience. Answering
                        // now would replace a live URL with "signed out" and strand them.
                        if (stateFlow.value != State.Checking) return
                        stateFlow.value = readStatus(output.toString(), exitCode)
                    }
                },
            )
        }.onFailure { stateFlow.value = State.Failed("Could not reach the computer") }
    }

    /**
     * Starts the harness's sign-in handshake and waits for it to produce a URL.
     *
     * The session stays open across the whole exchange — the handshake is holding an OAuth flow
     * open while the user is in their browser, which is exactly the shape the guest session was
     * built for.
     */
    fun beginSignIn(control: IRuntimeControl) {
        stateFlow.value = State.Starting
        val diagnostics = StringBuilder()
        val events = LineBuffer()
        runCatching {
            control.openEphemeralSession(
                LOGIN_SESSION,
                AUTH_COMMAND,
                WORKSPACE,
                guestEnvironment(),
                object : GuestSessionCallback() {
                    override fun onAttached(session: IAgentSession?, logPath: String) {
                        live = session
                    }

                    override fun onData(offset: Long, chunk: ByteArray) {
                        events.absorb(chunk.toString(Charsets.UTF_8)) { line ->
                            handleEvent(line, diagnostics)
                        }
                    }

                    override fun onDiagnostic(text: String) {
                        // The harness's own stderr. Shown as "what the computer said" so a version
                        // this screen has no template for still leaves the user something to act
                        // on. Never logged — this stream can carry credential material.
                        diagnostics.append(text)
                        val current = stateFlow.value
                        if (current is State.AwaitingCode) {
                            stateFlow.value = current.copy(transcript = transcriptOf(diagnostics))
                        }
                    }

                    override fun onClosed(exitCode: Int, error: String?) {
                        live = null
                        // The harness reports its own outcome as an event, and that is the one to
                        // trust. Exit status only matters when it stopped without saying anything.
                        when (val current = stateFlow.value) {
                            is State.SignedIn, is State.Failed -> Unit
                            else -> stateFlow.value = State.Failed(
                                error
                                    ?: if (current is State.AwaitingCode) {
                                        "The sign-in stopped before it finished."
                                    } else {
                                        "The sign-in did not start."
                                    },
                            )
                        }
                    }
                },
            )
        }.onFailure { stateFlow.value = State.Failed("Could not reach the computer") }
    }

    /** The code the user brought back from their browser. */
    fun submitCode(code: String) {
        val session = live ?: run {
            stateFlow.value = State.Failed("The sign-in stopped before the code arrived.")
            return
        }
        val command = JSONObject()
            .put("type", "auth_code")
            .put("code", code.trim())
            .toString()
        runCatching { session.write((command + "\n").toByteArray()) }
            .onFailure { stateFlow.value = State.Failed("Could not send the code.") }
    }

    fun cancel() {
        runCatching { live?.cancel() }
        live = null
        // Cancelling says nothing about whether a credential already exists, so this reverts to the
        // last thing actually known rather than asserting a signed-out guest.
        stateFlow.value = when (val current = stateFlow.value) {
            is State.SignedIn -> current
            else -> State.SignedOut
        }
    }

    private fun handleEvent(line: String, diagnostics: StringBuilder) {
        val event = runCatching { JSONObject(line) }.getOrElse {
            Log.w(TAG, "sign-in emitted a line that was not an event")
            return
        }
        when (event.optString("type")) {
            "auth_url" -> {
                val url = event.optString("url").ifBlank { return }
                stateFlow.value = State.AwaitingCode(url, transcriptOf(diagnostics))
            }

            "auth_completed" -> {
                val account = event.optJSONObject("account")
                stateFlow.value = State.SignedIn(account?.optString("email")?.ifBlank { null })
            }

            "auth_failed" -> {
                val message = event.optString("message").ifBlank { "Sign-in did not complete." }
                val detail = event.optString("detail").ifBlank { null }
                stateFlow.value = State.Failed(listOfNotNull(message, detail).joinToString("\n\n"))
            }

            else -> Unit
        }
    }

    private fun transcriptOf(diagnostics: StringBuilder) =
        diagnostics.toString().takeLast(MAX_TRANSCRIPT)

    private fun readStatus(output: String, exitCode: Int): State {
        if (exitCode != 0 && output.isBlank()) return State.SignedOut
        val json = runCatching { JSONObject(output.trim()) }.getOrElse {
            Log.w(TAG, "auth status was not JSON; treating as signed out")
            return State.SignedOut
        }
        // Field names have moved between versions, so accept any of the shapes that mean "yes"
        // and treat everything else as a signed-out state the user can act on.
        val signedIn = json.optBoolean("loggedIn", false) ||
            json.optBoolean("authenticated", false) ||
            json.optString("status").equals("authenticated", ignoreCase = true)
        return if (signedIn) State.SignedIn(accountOf(json)) else State.SignedOut
    }

    /** The email sits at the top level, but has been nested before; look in both. */
    private fun accountOf(json: JSONObject): String? =
        json.optString("email").ifBlank { null }
            ?: json.optJSONObject("account")?.optString("email")?.ifBlank { null }

    private fun guestEnvironment() = android.os.Bundle().apply {
        // Claude Code writes its credential under HOME. Pinning it keeps that inside the guest's
        // own home directory rather than wherever the session happened to start.
        putString("HOME", GUEST_HOME)
        putString("BOX_SESSION_CWD", WORKSPACE)
    }

    private companion object {
        const val TAG = "BoxGuestAuth"
        const val WORKSPACE = "/workspace"
        const val GUEST_HOME = "/home/agent"
        const val STATUS_SESSION = "auth-status"
        const val LOGIN_SESSION = "auth-login"
        const val MAX_TRANSCRIPT = 2000

        const val CLAUDE =
            "/opt/local-agent/harness/node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64/claude"

        /** The same harness the agent runs, asked only to carry a sign-in. */
        val AUTH_COMMAND = arrayOf(
            "/usr/bin/node",
            "/opt/local-agent/harness/box-claude-harness.mjs",
            "--auth",
        )
    }
}
