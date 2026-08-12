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

    /**
     * Asks the harness to change how much it asks about.
     *
     * A request, not a setting: the mode lives in the running harness, and the UI only believes it
     * once [AgentEvent.PermissionModeChanged] comes back. A control that flipped locally would keep
     * claiming edits are being accepted long after the process that agreed to it died.
     */
    suspend fun setPermissionMode(sessionId: String, mode: PermissionMode)

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
