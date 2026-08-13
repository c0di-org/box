package dev.localagent.workstation.agent

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import dev.localagent.runtime.qemu.IAgentSession
import dev.localagent.runtime.qemu.IRuntimeControl
import dev.localagent.workstation.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Connecting this box to GitHub.
 *
 * The same brokering as [GuestAuth], and for the same reason: the exchange runs inside the guest,
 * so the credential is written on this device by the program that obtained it and never reaches
 * the app. Box carries a code outward and an approval back, and that is all it ever holds.
 *
 * It reads better than the Claude sign-in, though, and the difference is structural rather than
 * cosmetic. Claude's handshake ends with the user carrying a string back into Box, which is a step
 * that can be got wrong — half a code pasted, and the flow fails at an API. GitHub's device flow
 * sends the code the *other* way: Box shows eight characters, the person approves at GitHub, and
 * nothing has to come back through this app at all. There is no paste to diagnose.
 *
 * Two steps, because Box is a GitHub App rather than an OAuth app. Authorising says who the user
 * is; installing says which repositories this box may touch. The second step is not overhead — it
 * is the repository picker, and it is the reason the consent screen can say "three repositories"
 * where an OAuth app would have had to ask for full control of every private repository the person
 * can see. That trade is the whole argument for the extra trip.
 */
class GitHubAuth {

    sealed interface State {
        data object Unknown : State
        data object Checking : State

        /**
         * Connected, and what that reaches.
         *
         * [stale] means the token is on disk and GitHub could not be asked about it — a phone in a
         * tunnel, not a revoked credential. Saying "not connected" there would put a Connect button
         * in front of somebody who is already connected and send them round a flow that cannot
         * complete without a network.
         */
        data class Connected(
            val login: String,
            val repositories: Int?,
            val stale: Boolean = false,
            val needsRepositories: Boolean = false,
        ) : State

        data object Disconnected : State
        data object Starting : State

        /** The person is at GitHub, entering [userCode]. */
        data class AwaitingApproval(
            val userCode: String,
            val url: String,
            val expiresAtElapsedRealtime: Long,
            val reason: String? = null,
        ) : State

        /**
         * Authorised, and now choosing which repositories this box can reach.
         *
         * [adding] separates the second half of a first connection from somebody widening a box
         * that already works — the commonest way to arrive here, since an agent's 403 on a
         * private repository usually means "not that one" rather than "no credential". The two
         * need different words: telling a connected person to "now pick what this box can see"
         * reads as though the connection they already have did not happen.
         */
        data class ChoosingRepositories(
            val url: String,
            val login: String,
            val adding: Boolean = false,
        ) : State

        /** This build has no GitHub App, so the only way in is a token made by hand. */
        data object Unconfigured : State

        data class Failed(val message: String, val detail: String? = null) : State
    }

    private val stateFlow = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = stateFlow.asStateFlow()

    private var live: IAgentSession? = null
    private var pendingReason: String? = null

    /** Whether the box already holds a credential, asked without troubling anybody. */
    fun check(control: IRuntimeControl) {
        // A connection already under way is a better answer than starting a second one, and this
        // can be called at any time — `:computer` reconnects while the user is still at GitHub.
        if (live != null) return
        val before = stateFlow.value
        stateFlow.value = State.Checking
        val events = LineBuffer()
        runCatching {
            control.openEphemeralSession(
                STATUS_SESSION,
                command(status = true),
                WORKSPACE,
                environment(),
                object : GuestSessionCallback() {
                    override fun onData(offset: Long, chunk: ByteArray) {
                        events.absorb(chunk.toString(Charsets.UTF_8)) { handleEvent(it) }
                    }

                    override fun onClosed(exitCode: Int, error: String?) {
                        // A connection may have started while this was still asking; on a cold box
                        // the check outlives the user's patience. Answering now would replace a
                        // live code with "not connected" and strand them mid-flow.
                        if (stateFlow.value == State.Checking) stateFlow.value = unanswered(before)
                    }
                },
            )
        }.onFailure { stateFlow.value = unanswered(before) }
    }

    /**
     * What to believe when the box did not answer at all.
     *
     * Not "disconnected", which is what this used to say and is a claim nothing here is entitled
     * to make. A box that is asleep, still booting, or running an image built before any of this
     * existed produces a session that opens and closes having said nothing — and the program is
     * careful to distinguish a revoked token from an unreachable network precisely so that the app
     * does not have to guess. Guessing "not connected" put a Connect button in front of somebody
     * who was connected, which is the same false negative in a different place.
     *
     * A real disconnection always arrives as an event saying so.
     */
    private fun unanswered(before: State): State = when (before) {
        // Still connected as far as anything knows; just not confirmed this time.
        is State.Connected -> before.copy(stale = true)
        else -> State.Unknown
    }

    /**
     * Starts the device flow and holds it open.
     *
     * [reason] is the agent's own words for what it needs GitHub for, when the request came from a
     * session rather than from the box sheet. It is carried through to the screen because it is the
     * only explanation the person gets before deciding.
     */
    fun connect(control: IRuntimeControl, reason: String? = null) {
        cancel()
        stateFlow.value = State.Starting
        pendingReason = reason
        val events = LineBuffer()
        val diagnostics = StringBuilder()
        runCatching {
            control.openEphemeralSession(
                CONNECT_SESSION,
                command(status = false),
                WORKSPACE,
                environment(),
                object : GuestSessionCallback() {
                    override fun onAttached(session: IAgentSession?, logPath: String) {
                        live = session
                    }

                    override fun onData(offset: Long, chunk: ByteArray) {
                        events.absorb(chunk.toString(Charsets.UTF_8)) { handleEvent(it) }
                    }

                    override fun onDiagnostic(text: String) {
                        // Kept for the failure message and never logged: this stream belongs to a
                        // process that is handling a credential.
                        diagnostics.append(text)
                    }

                    override fun onClosed(exitCode: Int, error: String?) {
                        live = null
                        when (stateFlow.value) {
                            is State.Connected, is State.Failed, State.Disconnected -> Unit
                            else -> stateFlow.value = State.Failed(
                                error ?: "The connection stopped before it finished.",
                                diagnostics.toString().takeLast(MAX_DETAIL).ifBlank { null },
                            )
                        }
                    }
                },
            )
        }.onFailure { stateFlow.value = State.Failed("Could not reach your box.") }
    }

    /** The person says they have finished choosing repositories. */
    fun repositoriesChosen() = send(JSONObject().put("type", "installed"))

    /** The escape hatch: a token they made themselves. Never logged, never held. */
    fun submitToken(token: String) =
        send(JSONObject().put("type", "token").put("token", token.trim()))

    fun cancel() {
        val session = live
        live = null
        runCatching { session?.cancel() }
        // Cancelling says nothing about whether a credential already exists, so this reverts to the
        // last thing actually known rather than asserting a disconnected box.
        stateFlow.value = when (val current = stateFlow.value) {
            is State.Connected -> current
            else -> State.Disconnected
        }
    }

    /**
     * Forgets the credential in the guest.
     *
     * Deliberately does not claim to have revoked anything — deleting a local copy is not a
     * revocation, and the screen offering this says so and links to GitHub, which is the only place
     * that can actually withdraw the grant.
     */
    fun disconnect(control: IRuntimeControl) {
        cancel()
        runCatching {
            control.openEphemeralSession(
                DISCONNECT_SESSION,
                command(status = false, disconnect = true),
                WORKSPACE,
                environment(),
                object : GuestSessionCallback() {
                    override fun onClosed(exitCode: Int, error: String?) {
                        stateFlow.value = State.Disconnected
                    }
                },
            )
        }.onFailure { stateFlow.value = State.Disconnected }
    }

    private fun send(command: JSONObject) {
        val session = live ?: return
        runCatching { session.write((command.toString() + "\n").toByteArray()) }
    }

    private fun handleEvent(line: String) {
        val event = runCatching { JSONObject(line) }.getOrElse {
            Log.w(TAG, "the connection emitted a line that was not an event")
            return
        }
        when (event.optString("type")) {
            "github_code" -> {
                val code = event.optString("userCode").ifBlank { return }
                stateFlow.value = State.AwaitingApproval(
                    userCode = code,
                    // The prefilled form when there is one, because the best version of this
                    // screen is the one where nobody types anything at all.
                    url = event.optString("verificationUriComplete")
                        .ifBlank { event.optString("verificationUri") }
                        .ifBlank { FALLBACK_DEVICE_URL },
                    expiresAtElapsedRealtime = SystemClock.elapsedRealtime() +
                        event.optLong("expiresInSeconds", 900L) * 1000L,
                    reason = pendingReason,
                )
            }

            "github_install" -> stateFlow.value = State.ChoosingRepositories(
                url = event.optString("url"),
                login = event.optString("login"),
                adding = event.optBoolean("adding"),
            )

            "github_connected" -> stateFlow.value = State.Connected(
                login = event.optString("login"),
                repositories = event.optionalInt("repositories"),
            )

            "github_status" -> stateFlow.value = if (event.optBoolean("connected")) {
                State.Connected(
                    login = event.optString("login").ifBlank { "Connected" },
                    repositories = event.optionalInt("repositories"),
                    stale = event.optBoolean("stale"),
                    needsRepositories = event.optBoolean("needsRepositories"),
                )
            } else {
                State.Disconnected
            }

            "github_unconfigured" -> stateFlow.value = State.Unconfigured

            // Declining at GitHub and cancelling from the sheet arrive here as the same thing, and
            // neither is a failure worth a red screen.
            "github_cancelled", "github_disconnected" -> stateFlow.value = State.Disconnected

            "github_failed" -> stateFlow.value = State.Failed(
                event.optString("message").ifBlank { "The connection did not finish." },
                event.optString("detail").ifBlank { null },
            )

            else -> Unit
        }
    }

    /** JSON's number-or-absent, kept apart from a real zero — "no repositories" is a real answer. */
    private fun JSONObject.optionalInt(name: String): Int? =
        if (isNull(name)) null else optInt(name, -1).takeIf { it >= 0 }

    private fun command(status: Boolean, disconnect: Boolean = false) = buildList {
        add(NODE)
        add(PROGRAM)
        if (status) add("--status")
        if (disconnect) add("--disconnect")
    }.toTypedArray()

    /**
     * What the guest program is told.
     *
     * The client id is public — a device flow has no secret, which is the entire reason Box can
     * use one from a phone — so nothing here is sensitive, and the credential it goes on to obtain
     * never comes back through this bundle.
     */
    private fun environment() = Bundle().apply {
        putString("BOX_GITHUB_CLIENT_ID", BuildConfig.GITHUB_CLIENT_ID)
        putString("BOX_GITHUB_APP_SLUG", BuildConfig.GITHUB_APP_SLUG)
    }

    private companion object {
        const val TAG = "BoxGitHubAuth"
        const val WORKSPACE = "/workspace"
        const val NODE = "/usr/bin/node"
        const val PROGRAM = "/opt/local-agent/bin/box-github-connect.mjs"
        const val STATUS_SESSION = "github-status"
        const val CONNECT_SESSION = "github-connect"
        const val DISCONNECT_SESSION = "github-disconnect"
        const val FALLBACK_DEVICE_URL = "https://github.com/login/device"
        const val MAX_DETAIL = 600
    }
}
