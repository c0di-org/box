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

    suspend fun startSession(harnessId: String, prompt: String?): String

    suspend fun send(sessionId: String, text: String)

    /** Answers the outstanding [AgentEvent.PermissionRequested]. Idempotent per request id. */
    suspend fun resolvePermission(sessionId: String, requestId: String, decision: PermissionDecision)

    /** Ctrl-C equivalent: stop what the agent is doing, keep the session. */
    suspend fun interrupt(sessionId: String)

    /** Tear the session down and forget it. */
    suspend fun closeSession(sessionId: String)
}

/**
 * Ask, or don't.
 *
 * The three values are the Claude Agent SDK's own permission modes under Box's names, and [wire]
 * is what the harness passes through to it — so this is a translation, not a policy Box invents on
 * top of one. That matters for what it does *not* promise: [Everything] is the agent's own bypass,
 * so Box stops seeing permission requests rather than answering them, and the tool cards in the
 * transcript become the only record of what was done.
 *
 * Whichever is chosen, the choice is visible while it is in force — see the banner in
 * `ConversationPane`. A box that is silently approving everything looks exactly like a box that
 * has nothing to approve, and that is the one confusion this setting must never cause.
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
    fun approves(ask: PermissionAsk): Boolean = when (this) {
        Ask -> false
        AcceptEdits -> ask is PermissionAsk.EditFile
        Everything -> true
    }
}
