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
 * phone's browser, and the code that comes back is handed to the still-running handshake.
 *
 * **This does not drive `claude auth login`, and must not be changed back to.** That command prints
 * the URL but then waits only on a loopback HTTP listener meant for a browser on the same machine;
 * the pasted code goes to a different entry point it never wires to stdin, so a code written to its
 * stdin is read by nobody and the process hangs until killed. The harness uses the SDK's control
 * protocol, the same handshake Claude Code's own login screen uses — see `runAuth` in
 * `box-claude-harness.mjs`.
 *
 * Three things this deliberately does not do: **nothing is persisted** (an ephemeral session, so
 * the exchange is never logged; the credential is written by Claude Code itself inside the guest);
 * **nothing is parsed too precisely** (the URL is a field in a structured event, not scraped from
 * prose, so CLI wording cannot break it); and **Box never handles the credential** — it carries a
 * code to a process and forgets it.
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

    /**
     * Whether the guest already holds a usable credential.
     *
     * Safe to call again, and meant to be: an ask that goes unanswered leaves the state
     * [State.Unknown] rather than asserting a signed-out guest, so somebody has to come back. See
     * [SignInStatus.unanswered] and [SignInStatus.retryAfterMillis].
     */
    fun check(control: IRuntimeControl) {
        // `:computer` can reconnect at any time, including while the user is in their browser
        // holding a code. A sign-in already under way is the better answer than asking again.
        if (live != null) return
        // What was known before the question, because an unanswered question must leave it
        // standing. Read before `Checking` overwrites it.
        val before = stateFlow.value
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
                        val answer = SignInStatus.read(output.toString())
                        if (answer == null) {
                            // `error` is the app's own account of why the session could not run —
                            // `AgentSessionHost` reports a session that never started as
                            // `onClosed(-1, …)`. It is the difference between "the guest says no"
                            // and "nobody asked the guest", and it used to be discarded here.
                            Log.w(TAG, "auth status went unanswered (exit $exitCode): $error")
                        }
                        stateFlow.value = answer ?: SignInStatus.unanswered(before)
                    }
                },
            )
        }.onFailure {
            // Not `Failed`: a binder that has gone away says nothing about a credential, and
            // `Failed` raises the sign-in banner just as `SignedOut` does.
            Log.w(TAG, "could not ask the computer about the credential", it)
            stateFlow.value = SignInStatus.unanswered(before)
        }
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
