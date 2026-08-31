package dev.localagent.workstation.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The interface Box's UI needs from whatever runs agent harnesses in the guest.
 *
 * Deliberately narrow: the UI never learns how a harness is launched, how its stdout is framed,
 * or how permission prompts are intercepted. It sends text, answers permission requests, and
 * folds [AgentEvent]s. Implementations live behind the runtime boundary; [FakeAgentBackend]
 * satisfies it entirely in-process so the whole UI can be built and demoed without a VM.
 */
interface AgentBackend {
    /** Harnesses installed in the guest. Empty is a legitimate first-run state. */
    val harnesses: StateFlow<List<HarnessDescriptor>>

    /**
     * How permission is answered before anyone is asked. One setting for the whole box, not one
     * per session: it describes how much the user currently trusts the agents they are running,
     * and having a conversation quietly keep its own answer to that is how someone ends up
     * approving everything in a session they had forgotten was set that way.
     */
    val permissionMode: StateFlow<AgentPermissionMode>

    /** Applies to every session, running and future, and survives the app being killed. */
    suspend fun setPermissionMode(mode: AgentPermissionMode)

    /**
     * Which model the harnesses answer with.
     *
     * Shaped like [permissionMode] because it is the same kind of fact: one setting for the box
     * rather than one per conversation. A per-conversation model would be defensible — different
     * tasks deserve different models — but it is not what "only one agent" currently means, and a
     * setting that is box-wide until the first person asks otherwise is the cheaper thing to be
     * wrong about.
     */
    val agentModel: StateFlow<AgentModel>

    /** Applies to every session, running and future, and survives the app being killed. */
    suspend fun setAgentModel(model: AgentModel)

    /**
     * What Box is being read on right now, told to every session and re-told whenever it changes.
     *
     * Like [setPermissionMode] in shape and for the same reason: it is one fact about the box as a
     * whole, not a property of a conversation. Unlike it, nothing here is persisted — a window size
     * that outlived the window it described would be worse than not knowing.
     */
    suspend fun setViewport(viewport: AgentViewport)

    /** Every known session across every harness, newest activity first. */
    val sessions: StateFlow<List<SessionSummary>>

    /**
     * Replay of everything that already happened in [sessionId], followed by live events.
     * Collecting twice must produce the same prefix — the UI relies on it to restore a
     * transcript after process death.
     */
    fun events(sessionId: String): Flow<AgentEvent>

    /** Transport health for [sessionId]. Independent of whether the agent is busy. */
    fun connection(sessionId: String): StateFlow<SessionConnection>

    suspend fun startSession(
        harnessId: String,
        prompt: String?,
        attachments: List<Attachment> = emptyList(),
    ): String

    /**
     * The user's turn: what they typed, and anything they showed.
     *
     * [attachments] are already in the box by the time this is called — they are files in the
     * shared folder, not bytes to be carried here — so this only has to name them. A backend that
     * ignores them still delivers the text, which is the whole reason they are a field.
     */
    suspend fun send(sessionId: String, text: String, attachments: List<Attachment> = emptyList())

    /**
     * Hands a harness the secret it asked for in [AgentEvent.AgentError.credential].
     *
     * The one method here whose argument is credential material, and the rules that come with
     * that: it is never echoed, never written to the session log, and never turned into an
     * [AgentEvent]. It goes down the same non-echoed stdin channel the Claude sign-in uses for
     * `auth_code`, and for the same reason — a key that reached `send()` instead would be
     * mirrored into the log by the harness's own `user_message` echo and drawn in the transcript
     * from then on.
     *
     * Returns nothing. The receipt is [AgentEvent.CredentialAccepted], from the guest that wrote
     * the file.
     */
    suspend fun provideCredential(sessionId: String, credentialId: String, value: String)

    /** Answers the outstanding [AgentEvent.PermissionRequested]. Idempotent per request id. */
    suspend fun resolvePermission(sessionId: String, requestId: String, decision: PermissionDecision)

    /**
     * Answers the outstanding [AgentEvent.ConnectRequested].
     *
     * Carries the outcome, never the credential — the token was written inside the guest by the
     * program that obtained it, and this only tells the agent that it may now use git.
     */
    suspend fun resolveConnect(sessionId: String, requestId: String, outcome: ConnectOutcome)

    /** Ctrl-C equivalent: stop what the agent is doing, keep the session. */
    suspend fun interrupt(sessionId: String)

    /**
     * Stop one sub-agent — the one named by [subAgentId] — and leave the session running.
     *
     * Separate from [interrupt] rather than a nullable argument to it, because the two are not the
     * same act and confusing them is expensive: stopping the session throws away everything in
     * flight, while this asks one delegate to stand down and lets the agent that sent it carry on
     * with whatever it hears back. A backend that cannot single one out should do nothing at all
     * rather than fall back to interrupting the session.
     */
    suspend fun interruptSubAgent(sessionId: String, subAgentId: String)

    /** Tear the session down and forget it. */
    suspend fun closeSession(sessionId: String)
}

/**
 * What became of a [AgentEvent.ConnectRequested], in the only terms the agent needs.
 *
 * [login] and [repositories] are here because they change what the agent should do next: knowing
 * the box can reach four repositories and not a fifth is the difference between trying the clone
 * and explaining why it will not work. The credential itself is deliberately absent and there is
 * nowhere in this type to put one.
 */
data class ConnectOutcome(
    val connected: Boolean,
    val login: String? = null,
    val repositories: Int? = null,
)

/**
 * Ask, or don't.
 *
 * The three values are the Claude Agent SDK's own permission modes under Box's names, and [wire] is
 * what the harness passes through — a translation, not a policy invented on top of one. That is
 * what [Everything] does *not* promise: it is the agent's own bypass, so Box stops seeing
 * permission requests rather than answering them, and the tool cards become the only record.
 *
 * The choice is visible while in force — the composer's mode control carries a caution sign for
 * anything but [Ask]. A box silently approving everything looks exactly like one with nothing to
 * approve, and that is the confusion this must never cause.
 */
enum class AgentPermissionMode(val wire: String) {
    /** The default, and the reason the permission sheet exists. */
    Ask("default"),

    /** Edits inside the workspace go through; commands and network access still stop. */
    AcceptEdits("acceptEdits"),

    /** Nothing stops. */
    Everything("bypassPermissions");

    /**
     * Whether an ask would be approved without reaching a person.
     *
     * Only the in-process fake needs this — with a real harness the agent never asks in the first
     * place. It is here rather than there so both sides answer the question the same way.
     */
    fun approves(ask: PermissionAsk): Boolean = when {
        // A question is never approved, in any mode. There is nothing here to permit, and a
        // setting that answered it without a person would be the original bug wearing a
        // preference: the agent told that someone chose, when choosing is the exact step skipped.
        // The guest keeps the same rule from the other side, by pinning the question tool to
        // "ask" no matter what the mode says.
        ask is PermissionAsk.Questions -> false
        this == Ask -> false
        this == AcceptEdits -> ask is PermissionAsk.EditFile
        else -> true
    }
}

/**
 * Which model answers, named exactly rather than by tier.
 *
 * [wire] is a full model id and not an alias like `opus`, which is the correction that matters:
 * an alias resolves to one model per family, so a person who wants *this* Opus rather than the
 * newest one has no way to say so, and — worse — cannot see which one they are getting. The whole
 * question this control exists to answer is "am I on 4.5 or 5", and an alias is precisely the
 * thing that hides it.
 *
 * The usual objection to pinned ids is that they go stale. They cannot drift *silently* here: the
 * Claude Code that resolves them is baked into the guest image, and the image is built from this
 * same tree, so a rebuild that teaches the guest a new model is a build that also ships this list.
 * They rot together or not at all.
 *
 * Ordered as offered, most capable first. Cost falls with the family and not within it —
 * [Opus45] bills at the same rate as [Opus5], which is worth saying on the control rather than
 * leaving someone to pick the older model as a saving it is not.
 */
enum class AgentModel(val wire: String, val label: String, val summary: String) {
    /** The default: the current Opus, and what Box is designed around. */
    Opus5("claude-opus-5", "Opus 5", "Best at long, complicated work"),

    /** Kept because "the one I had yesterday" is a real thing to want after a model changes. */
    Opus45("claude-opus-4-5", "Opus 4.5", "The older Opus. Costs the same as Opus 5"),

    /** The first step that actually costs less: near-Opus on code, at Sonnet's rate. */
    Sonnet5("claude-sonnet-5", "Sonnet 5", "Nearly as capable, and cheaper"),

    /** For short, well-specified things where waiting is the cost that matters. */
    Haiku45("claude-haiku-4-5", "Haiku 4.5", "Cheapest and fastest, for simple work");

    companion object {
        val DEFAULT = Opus5

        /**
         * Tolerant of a name written by an older or newer Box than this one.
         *
         * Falls back to [DEFAULT] rather than to whatever was stored, because a model this build
         * cannot name is one it cannot draw either, and a picker showing nothing selected is a
         * worse answer than a picker showing the default.
         */
        fun ofName(name: String?): AgentModel =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The window Box is being read in, as a fact an agent can write for.
 *
 * Derived from the *window*, never from the device — the same discipline `BoxWindowSize` holds to,
 * and for a sharper reason here. A layout that goes stale is corrected by the next frame; an agent
 * told once that it is "on a phone" believes it for the rest of the session, and will still believe
 * it after the fold opens or the DeX window is dragged wider. So there is no device type in here,
 * and every field is re-sent when it changes.
 *
 * [hardwareKeyboard] is carried separately from [widthDp] because it answers a different question:
 * not how much can be shown, but what it is reasonable to ask the *person* to type.
 */
data class AgentViewport(
    val layout: ViewportLayout,
    val widthDp: Int,
    val hardwareKeyboard: Boolean,
)

/**
 * How much room there is, in the two sizes worth writing differently for.
 *
 * Named for the reading rather than for the panes — `BoxWindowSize.BoxLayout` calls the narrow one
 * `Single` because one pane is what fits, which is a fact about Box's own layout and means nothing
 * to an agent choosing how long an answer should be.
 */
enum class ViewportLayout(val wire: String) {
    Compact("compact"),
    Wide("wide"),
}
