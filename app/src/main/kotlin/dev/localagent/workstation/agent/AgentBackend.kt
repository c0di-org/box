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

    /** Ctrl-C equivalent: stop what the agent is doing, keep the session. */
    suspend fun interrupt(sessionId: String)

    /** Tear the session down and forget it. */
    suspend fun closeSession(sessionId: String)
}
