package dev.localagent.workstation.agent

import androidx.compose.runtime.Immutable

/**
 * Everything the conversation view can render, as an append-only event log.
 *
 * This is the contract a harness driver has to conform to. Two rules keep it honest:
 *
 *  1. **Append-only.** Nothing is ever mutated in place. A tool call that finishes emits a
 *     second event referencing the first by [ToolCallStarted.callId]; a checklist that advances
 *     re-emits the whole list under the same [TaskProgress.planId]. A backend can therefore be a
 *     dumb pipe, and the UI can replay a session from cold storage by folding the same events.
 *  2. **Structured, never stringly.** Tool calls arrive as [ToolCall] variants and file edits as
 *     [FileDiff], not as JSON blobs the UI has to guess at. If a harness emits something Box does
 *     not model yet it lands in [ToolCall.Generic], which renders as a labelled key/value card —
 *     degraded, but never a raw dump.
 *
 * [TranscriptReducer] folds this log into the [TranscriptItem] list the UI actually draws.
 */
@Immutable
sealed interface AgentEvent {
    /** Unique within a session. Used as the fold key, so it must be stable across replays. */
    val eventId: String
    val sessionId: String

    /** Epoch millis, guest clock. Only used for grouping and display. */
    val at: Long

    // ---- session lifecycle -------------------------------------------------

    data class SessionStarted(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val harnessId: String,
        val title: String,
        val workingDirectory: String,
    ) : AgentEvent

    data class SessionEnded(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val outcome: SessionOutcome,
    ) : AgentEvent

    // ---- turns -------------------------------------------------------------

    data class UserMessage(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val text: String,
    ) : AgentEvent

    /**
     * A span of agent prose. Streaming harnesses emit the same [messageId] repeatedly with a
     * growing [text] and `complete = false`; the reducer keeps the newest and renders a caret.
     */
    data class AgentMessage(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val messageId: String,
        val text: String,
        val complete: Boolean = true,
    ) : AgentEvent

    /** Chain-of-thought or plan narration. Rendered collapsed; never shown by default. */
    data class AgentThinking(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val messageId: String,
        val text: String,
        val complete: Boolean = true,
    ) : AgentEvent

    // ---- tools -------------------------------------------------------------

    data class ToolCallStarted(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val callId: String,
        val call: ToolCall,
    ) : AgentEvent

    /** Incremental output for a running call — streamed stdout, progress lines, etc. */
    data class ToolCallProgress(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val callId: String,
        val chunk: String,
    ) : AgentEvent

    data class ToolCallFinished(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val callId: String,
        val outcome: ToolOutcome,
    ) : AgentEvent

    /**
     * A change the agent made to a file. Separate from [ToolCallFinished] because a diff is a
     * first-class thing the user reviews, and it outlives the call that produced it.
     */
    data class FileChanged(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val callId: String?,
        val diff: FileDiff,
    ) : AgentEvent

    // ---- permission --------------------------------------------------------

    /**
     * The agent is blocked until the user answers. Exactly one request is outstanding at a time;
     * the UI raises [PermissionSheet] and refuses to send new input until it resolves.
     */
    data class PermissionRequested(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val requestId: String,
        val ask: PermissionAsk,
    ) : AgentEvent

    data class PermissionResolved(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val requestId: String,
        val decision: PermissionDecision,
    ) : AgentEvent

    // ---- progress ----------------------------------------------------------

    /**
     * The mockup's "cloned repo ✓ / installed dependencies ✓ / starting dev server ○" block.
     * Re-emitted in full whenever any item changes; [planId] identifies which block to replace.
     */
    data class TaskProgress(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val planId: String,
        val items: List<TaskItem>,
    ) : AgentEvent

    /** What the agent is doing right now. Drives the session-list dot and the composer state. */
    data class ActivityChanged(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val activity: AgentActivity,
    ) : AgentEvent

    /** "Open computer" / "Open preview" affordances the agent offers mid-transcript. */
    data class ArtifactOffered(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val artifact: Artifact,
    ) : AgentEvent

    data class AgentError(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val message: String,
        val detail: String? = null,
        val recoverable: Boolean = true,
    ) : AgentEvent
}

// ---------------------------------------------------------------------------
// Tool calls
// ---------------------------------------------------------------------------

/**
 * A tool invocation in the terms the *user* cares about, not the terms the model emitted it in.
 * Rendering reads these fields directly — no JSON ever reaches the screen.
 */
@Immutable
sealed interface ToolCall {
    /** One-line summary shown on the collapsed card. */
    val label: String

    data class Shell(
        val command: String,
        val workingDirectory: String = "/workspace",
        override val label: String = command,
    ) : ToolCall

    data class ReadFile(val path: String, val range: IntRange? = null) : ToolCall {
        override val label: String get() = "Read ${path.substringAfterLast('/')}"
    }

    data class EditFile(val path: String, val summary: String? = null) : ToolCall {
        override val label: String get() = "Edit ${path.substringAfterLast('/')}"
    }

    data class WriteFile(val path: String, val bytes: Long? = null) : ToolCall {
        override val label: String get() = "Create ${path.substringAfterLast('/')}"
    }

    data class Search(val query: String, val scope: String? = null) : ToolCall {
        override val label: String get() = "Search “$query”"
    }

    data class Fetch(val url: String) : ToolCall {
        override val label: String get() = "Fetch ${url.substringAfter("://").substringBefore('/')}"
    }

    /** Anything Box does not model yet. Renders as a labelled key/value card, never raw JSON. */
    data class Generic(
        val name: String,
        val arguments: List<Pair<String, String>> = emptyList(),
    ) : ToolCall {
        override val label: String get() = name
    }
}

@Immutable
sealed interface ToolOutcome {
    data class Success(
        /** Human summary — "512 packages installed", "3 matches". Preferred over raw output. */
        val summary: String? = null,
        val output: String = "",
        val exitCode: Int = 0,
    ) : ToolOutcome

    data class Failure(
        val message: String,
        val output: String = "",
        val exitCode: Int? = null,
    ) : ToolOutcome

    data object Denied : ToolOutcome
    data object Cancelled : ToolOutcome
}

// ---------------------------------------------------------------------------
// Permission
// ---------------------------------------------------------------------------

/**
 * What the agent wants to do. Each variant carries everything the sheet needs to explain the
 * risk without a round trip — a diff, a command line, a hostname.
 */
@Immutable
sealed interface PermissionAsk {
    /** Short title for the sheet header. */
    val headline: String

    /** What "Always allow" would cover, in the user's words. Null hides that button. */
    val alwaysAllowScope: String?

    data class EditFile(
        val diff: FileDiff,
        val rationale: String? = null,
        override val alwaysAllowScope: String? = "edits in this project",
    ) : PermissionAsk {
        override val headline: String get() = "Edit ${diff.path.substringAfterLast('/')}"
    }

    data class RunCommand(
        val command: String,
        val workingDirectory: String = "/workspace",
        val rationale: String? = null,
        val destructive: Boolean = false,
        override val alwaysAllowScope: String? = null,
    ) : PermissionAsk {
        override val headline: String get() = "Run a command"
    }

    data class NetworkAccess(
        val host: String,
        val purpose: String? = null,
        override val alwaysAllowScope: String? = null,
    ) : PermissionAsk {
        override val headline: String get() = "Reach $host"
    }

    data class Generic(
        val title: String,
        val description: String,
        val details: List<Pair<String, String>> = emptyList(),
        override val alwaysAllowScope: String? = null,
    ) : PermissionAsk {
        override val headline: String get() = title
    }
}

@Immutable
sealed interface PermissionDecision {
    data object Allow : PermissionDecision

    /** [scope] echoes [PermissionAsk.alwaysAllowScope] so the log records what was widened. */
    data class AllowAlways(val scope: String) : PermissionDecision
    data object Deny : PermissionDecision

    /** The session ended or the user backed out without answering. */
    data object Abandoned : PermissionDecision
}

// ---------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------

@Immutable
data class TaskItem(val text: String, val state: TaskState)

enum class TaskState { Pending, Running, Done, Failed, Skipped }

@Immutable
sealed interface AgentActivity {
    /** Session exists but nothing is happening; the composer is live. */
    data object Idle : AgentActivity
    data class Thinking(val label: String? = null) : AgentActivity
    data class Working(val label: String) : AgentActivity
    data class AwaitingPermission(val requestId: String) : AgentActivity

    /** The agent asked a question and is parked until the user replies. */
    data object AwaitingInput : AgentActivity
    data object Ended : AgentActivity
}

@Immutable
sealed interface Artifact {
    /** The agent's live desktop. Inert until the display transport lands. */
    data object Computer : Artifact

    /** A forwarded guest port. Inert until port forwarding lands. */
    data class Preview(val url: String, val guestPort: Int) : Artifact
}

@Immutable
sealed interface SessionOutcome {
    data class Completed(val summary: String? = null) : SessionOutcome
    data class Failed(val message: String) : SessionOutcome
    data object Interrupted : SessionOutcome
}
