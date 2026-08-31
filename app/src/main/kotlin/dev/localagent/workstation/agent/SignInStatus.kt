package dev.localagent.workstation.agent

import org.json.JSONObject

/**
 * Reading the guest's answer about the Claude credential — and what to believe when there is no
 * answer to read.
 *
 * Separated from [GuestAuth] because it is the part that can be tested. [GuestAuth] is a guest
 * process and an AIDL callback; this is a string and a decision, and the decision is the one that
 * was wrong: "the box did not answer" was being reported to the user as "you are signed out".
 *
 * The command behind it is `claude auth status --json`, and its two answers were checked against
 * the CLI the guest actually runs (the `claude` binary bundled with
 * `@anthropic-ai/claude-agent-sdk-linux-arm64`, pinned in `guest/harness/package.json`):
 *
 * ```
 * signed in   exit 0   {"loggedIn": true,  "authMethod": "claude.ai", "email": "…", …}
 * signed out  exit 1   {"loggedIn": false, "authMethod": "none", …}
 * ```
 *
 * **The JSON is the whole answer, and the exit status is not part of it.** Signing out is a
 * non-zero exit *by design*, so an exit code cannot tell a refusal apart from a failure to run —
 * which is exactly the mistake this file exists to stop being made again.
 */
internal object SignInStatus {

    /**
     * What the guest said, or null when it said nothing this can read.
     *
     * Null covers every way the question fails to get an answer: a session that could not be
     * started at all, a `claude` that died before writing anything, output that is not the JSON
     * document this asked for. None of them are evidence about a credential.
     */
    fun read(output: String): GuestAuth.State? {
        val text = output.trim()
        if (text.isEmpty()) return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        // Field names have moved between versions, so accept any of the shapes that mean "yes".
        // Everything else in a document that did parse is a real "no" — the CLI answered.
        val signedIn = json.optBoolean("loggedIn", false) ||
            json.optBoolean("authenticated", false) ||
            json.optString("status").equals("authenticated", ignoreCase = true)
        return if (signedIn) GuestAuth.State.SignedIn(accountOf(json)) else GuestAuth.State.SignedOut
    }

    /**
     * What to believe when the guest did not answer at all, given what was believed [before].
     *
     * Never [GuestAuth.State.SignedOut], and never [GuestAuth.State.Failed]. Both of those raise
     * the sign-in banner, and this is precisely the case where Box has learned nothing: a box that
     * is still waking up, an image whose harness is not up yet, a `:computer` that went away
     * between the ask and the answer. Claiming a signed-out guest here strands a signed-in user in
     * front of a sign-in they do not need and cannot complete.
     *
     * [GuestAuth.State.Unknown] is the honest state, and it is already understood downstream:
     * `BoxUiState.needsSignIn` stays false, and `signInWanted` falls back to [SignInHistory]'s
     * hint, which is right in the one case that matters — a fresh install has never signed in.
     *
     * The same reasoning, and the same conclusion, as `GitHubAuth.unanswered`.
     */
    fun unanswered(before: GuestAuth.State): GuestAuth.State = when (before) {
        // Still signed in as far as anything knows; just not confirmed this time.
        is GuestAuth.State.SignedIn -> before
        else -> GuestAuth.State.Unknown
    }

    /**
     * How long to wait before asking again, or null once there is no point.
     *
     * Asking once was the second half of the bug. The check fires from `greetReadyComputer`, which
     * runs the moment `:computer` says Ready and the binding exists — and Ready is the VM, not the
     * guest's userland. A box resuming from a snapshot can be Ready a beat before `agentd` will
     * open a session, so the one ask lands in a window where nothing can answer it, and the wrong
     * answer it produced then stood for the rest of the app's life.
     *
     * Short first, because the ordinary case is a guest that is seconds away, then widening so a
     * box that is genuinely not going to answer is not interrogated. Roughly a minute in total,
     * after which the hint is what remains and the user still has the sheet.
     */
    fun retryAfterMillis(attempt: Int): Long? = RETRY_DELAYS_MILLIS.getOrNull(attempt)

    private val RETRY_DELAYS_MILLIS = listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)

    /** The email sits at the top level, but has been nested before; look in both. */
    private fun accountOf(json: JSONObject): String? =
        json.optString("email").ifBlank { null }
            ?: json.optJSONObject("account")?.optString("email")?.ifBlank { null }
}
