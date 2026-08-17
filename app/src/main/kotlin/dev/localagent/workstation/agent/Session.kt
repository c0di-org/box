package dev.localagent.workstation.agent

import androidx.compose.runtime.Immutable

/**
 * A CLI coding agent Box knows how to launch. Box does not implement the agent; it wraps whatever
 * binary the user installed in the guest, so this is descriptive only.
 */
@Immutable
data class HarnessDescriptor(
    val id: String,
    val name: String,
    val command: String,
    val mark: HarnessMarkKind,
    val installed: Boolean = true,
    /** Product affordances this harness can actually back. The UI never infers provider APIs. */
    val capabilities: HarnessCapabilities = HarnessCapabilities(),
)

/** Which geometric mark to draw for a harness. Deliberately abstract, not vendor artwork. */
enum class HarnessMarkKind { Burst, Knot, Prism, Generic }

@Immutable
data class SessionSummary(
    val id: String,
    val harnessId: String,
    val title: String,
    val status: SessionStatus,
    val updatedAt: Long,
    val workingDirectory: String = "/workspace",
    /** Last line of agent prose, for the two-pane list subtitle. */
    val preview: String? = null,
)

/**
 * Coarse session state, as shown in the session list. Finer detail lives in [AgentActivity];
 * this is what the list dot and label report.
 */
@Immutable
sealed interface SessionStatus {
    /** The agent is doing something right now. */
    data object Active : SessionStatus

    /** Blocked on the user — a permission request or a direct question. */
    data class NeedsYou(val reason: String) : SessionStatus

    /** Nothing running; the user can type. */
    data object Idle : SessionStatus
    data object Finished : SessionStatus
    data class Failed(val message: String) : SessionStatus
}

/**
 * Health of the pipe between Box and the harness process. Orthogonal to [SessionStatus]: a
 * finished session can be disconnected, and an active one can survive a reconnect. The VM takes
 * ~90s to boot and Android can kill it, so [Disconnected] is a normal state, not an error.
 */
@Immutable
sealed interface SessionConnection {
    data object Connecting : SessionConnection
    data object Live : SessionConnection
    data class Disconnected(val reason: String, val retrying: Boolean) : SessionConnection
    data object Ended : SessionConnection
}
