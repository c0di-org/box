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
 * The guest cannot open a browser and the phone cannot run the CLI, so the sign-in is brokered:
 * Claude Code's own login runs in the VM, Box lifts the URL out of its output and opens it in the
 * phone's browser, and the code the user gets back is typed into the still-running process.
 *
 * Three things this deliberately does *not* do:
 *
 *  - **Nothing is persisted.** It runs as an ephemeral session, so the exchange is never written
 *    to a log. The resulting credential is written by Claude Code itself, inside the guest
 *    filesystem, on this device. It never touches the app's storage or the event stream.
 *  - **Nothing is parsed too precisely.** The URL is found by looking for a URL, not by matching
 *    an expected sentence. Output wording changes between versions; a regex over `https://` does
 *    not care, and the raw text is shown to the user regardless.
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
        stateFlow.value = State.Checking
        val output = StringBuilder()
        runCatching {
            control.openEphemeralSession(
                STATUS_SESSION,
                arrayOf(CLAUDE, "auth", "status", "--json"),
                WORKSPACE,
                guestEnvironment(),
                object : Callback() {
                    override fun onData(offset: Long, chunk: ByteArray) {
                        output.append(chunk.toString(Charsets.UTF_8))
                    }

                    override fun onClosed(exitCode: Int, error: String?) {
                        stateFlow.value = readStatus(output.toString(), exitCode)
                    }
                },
            )
        }.onFailure { stateFlow.value = State.Failed("Could not reach the computer") }
    }

    /**
     * Starts Claude Code's own login and waits for it to produce a URL.
     *
     * The session stays open across the whole exchange — the process is sitting on a blocking read
     * of stdin while the user is in their browser, which is exactly the shape the guest session
     * was built for.
     */
    fun beginSignIn(control: IRuntimeControl) {
        stateFlow.value = State.Starting
        val transcript = StringBuilder()
        runCatching {
            control.openEphemeralSession(
                LOGIN_SESSION,
                arrayOf(CLAUDE, "auth", "login", "--claudeai"),
                WORKSPACE,
                guestEnvironment(),
                object : Callback() {
                    override fun onAttached(session: IAgentSession?, logPath: String) {
                        live = session
                    }

                    override fun onData(offset: Long, chunk: ByteArray) {
                        absorb(transcript, chunk.toString(Charsets.UTF_8))
                    }

                    override fun onDiagnostic(text: String) {
                        // Interactive prompts often arrive on stderr, and the URL may be the only
                        // thing on it. Never logged — this stream can carry credential material.
                        absorb(transcript, text)
                    }

                    override fun onClosed(exitCode: Int, error: String?) {
                        live = null
                        stateFlow.value = when {
                            error != null -> State.Failed(error)
                            exitCode == 0 -> State.SignedIn(null)
                            else -> State.Failed("Sign-in did not complete.")
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
        runCatching { session.write((code.trim() + "\n").toByteArray()) }
            .onFailure { stateFlow.value = State.Failed("Could not send the code.") }
    }

    fun cancel() {
        runCatching { live?.cancel() }
        live = null
        stateFlow.value = State.SignedOut
    }

    private fun absorb(transcript: StringBuilder, text: String) {
        transcript.append(text)
        val whole = transcript.toString()
        val url = URL_PATTERN.find(whole)?.value ?: return
        val current = stateFlow.value
        if (current is State.AwaitingCode && current.url == url) return
        stateFlow.value = State.AwaitingCode(url, whole.takeLast(MAX_TRANSCRIPT))
    }

    private fun readStatus(output: String, exitCode: Int): State {
        if (exitCode != 0 && output.isBlank()) return State.SignedOut
        val json = runCatching { JSONObject(output.trim()) }.getOrElse {
            Log.w(TAG, "auth status was not JSON; treating as signed out")
            return State.SignedOut
        }
        // Field names have moved between versions, so accept any of the shapes that mean "yes"
        // and treat everything else as a signed-out state the user can act on.
        val signedIn = json.optBoolean("authenticated", false) ||
            json.optBoolean("loggedIn", false) ||
            json.optString("status").equals("authenticated", ignoreCase = true) ||
            json.has("account")
        return if (signedIn) {
            State.SignedIn(json.optJSONObject("account")?.optString("email")?.ifBlank { null })
        } else {
            State.SignedOut
        }
    }

    private fun guestEnvironment() = android.os.Bundle().apply {
        // Claude Code writes its credential under HOME. Pinning it keeps that inside the guest's
        // own home directory rather than wherever the session happened to start.
        putString("HOME", GUEST_HOME)
    }

    /** Defaults so each use only overrides what it cares about. */
    private abstract class Callback : IAgentSessionCallback.Stub() {
        override fun onAttached(session: IAgentSession?, logPath: String) = Unit
        override fun onData(offset: Long, chunk: ByteArray) = Unit
        override fun onDiagnostic(text: String) = Unit
        override fun onClosed(exitCode: Int, error: String?) = Unit
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

        val URL_PATTERN = Regex("""https://[^\s"'<>]+""")
    }
}
