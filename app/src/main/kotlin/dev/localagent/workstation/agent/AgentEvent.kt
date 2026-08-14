package dev.localagent.workstation.agent

import androidx.compose.runtime.Immutable

/**
 * Everything the conversation view can render, as an append-only event log.
 *
 * The contract a harness driver conforms to. Two rules keep it honest:
 *
 *  1. **Append-only.** Nothing is mutated in place: a finished tool call emits a second event
 *     referencing the first by [ToolCallStarted.callId], and an advancing checklist re-emits the
 *     whole list under the same [TaskProgress.planId]. So a backend can be a dumb pipe and the UI
 *     can replay a session from cold storage by folding the same events.
 *  2. **Structured, never stringly.** Tool calls arrive as [ToolCall] variants and edits as
 *     [FileDiff], not JSON the UI must guess at. Anything unmodelled lands in [ToolCall.Generic],
 *     a labelled key/value card — degraded, never a raw dump.
 *
 * Sub-agents ride this same log: a [ToolCall.Task] names one and everything it then does carries
 * its [subAgentId], so a replay reconstructs the nesting without the log ever being a tree.
 *
 * [TranscriptReducer] folds this into the [TranscriptItem] list the UI draws.
 */
@Immutable
sealed interface AgentEvent {
    /** Unique within a session. Used as the fold key, so it must be stable across replays. */
    val eventId: String
    val sessionId: String

    /** Epoch millis, guest clock. Only used for grouping and display. */
    val at: Long

    /**
     * Who this is about: null for the session's own agent, otherwise the sub-agent that produced
     * it — named by the [ToolCallStarted.callId] of the [ToolCall.Task] that spawned it.
     *
     * A default rather than a member on every event, because most events cannot have a second
     * author. A session starts once. A permission is answered by the person, for the session, and
     * is deliberately *not* attributed: the sheet allows one outstanding request at a time, so
     * saying which sub-agent asked would offer a choice the sheet does not have. Only the events a
     * sub-agent genuinely produces carry an id, and the ones that do not are not main-agent events
     * by omission — they have exactly one author.
     */
    val subAgentId: String? get() = null

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
        val attachments: List<Attachment> = emptyList(),
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
        override val subAgentId: String? = null,
    ) : AgentEvent

    /** Chain-of-thought or plan narration. Rendered collapsed; never shown by default. */
    data class AgentThinking(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val messageId: String,
        val text: String,
        val complete: Boolean = true,
        override val subAgentId: String? = null,
    ) : AgentEvent

    // ---- tools -------------------------------------------------------------

    data class ToolCallStarted(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val callId: String,
        val call: ToolCall,
        override val subAgentId: String? = null,
    ) : AgentEvent

    /** Incremental output for a running call — streamed stdout, progress lines, etc. */
    data class ToolCallProgress(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val callId: String,
        val chunk: String,
        override val subAgentId: String? = null,
    ) : AgentEvent

    data class ToolCallFinished(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val callId: String,
        val outcome: ToolOutcome,
        override val subAgentId: String? = null,
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
        override val subAgentId: String? = null,
    ) : AgentEvent

    // ---- permission --------------------------------------------------------

    /**
     * The agent is blocked on this one until the user answers.
     *
     * **Several can be outstanding at once.** An agent that asks for two commands in one turn
     * blocks on both, and they are answered independently and in any order. Box used to assume one
     * — the transcript kept a single slot — so a second request evicted the first, and answering
     * the one on screen left the other blocked forever with nothing left that could raise it.
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
        override val subAgentId: String? = null,
    ) : AgentEvent

    /** What the agent is doing right now. Drives the session-list dot and the composer state. */
    data class ActivityChanged(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val activity: AgentActivity,
    ) : AgentEvent

    /** "Open computer" / "Open preview" affordances the agent offers mid-transcript. */
    /**
     * The agent asking for an account this box does not have, and waiting for an answer.
     *
     * A sibling of [PermissionRequested] rather than a kind of it, because the question is not
     * "may it do this" — it is "will you go and get something only you can get". Nothing is
     * blocked on a policy; the agent is holding a tool call open while a person visits GitHub, and
     * the transcript should say so rather than render a sheet nobody can answer from here.
     */
    data class ConnectRequested(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val requestId: String,
        val service: ConnectService,
        /** The agent's own half-line for what it needs the account for, if it gave one. */
        val reason: String?,
    ) : AgentEvent

    /**
     * A [ConnectRequested] that is over, however it ended.
     *
     * The sibling of [PermissionResolved], and it exists for the same reason: a session log is
     * replayed from the beginning every time the conversation is opened, so a request with no
     * recorded ending reads as one still waiting. An account connected last week would otherwise
     * come back as a live card carrying last week's reason, and nothing could dismiss it — the
     * harness has long since forgotten the id, so the answer goes nowhere.
     */
    data class ConnectResolved(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
        val requestId: String,
        val connected: Boolean,
    ) : AgentEvent

    /**
     * The end of history: everything already on disk has been delivered, and what follows is
     * happening now.
     *
     * Emitted once per subscription and never written to the log — it is a property of *this*
     * reading of it, not of the session. It exists because two events that look identical need
     * opposite handling: an agent asking for GitHub right now should raise a sheet with a code in
     * it, and the same line replayed from a log should raise nothing at all until the rest of the
     * log has had its say about how it ended. See `BoxViewModel.offerConnection`.
     */
    data class CaughtUp(
        override val eventId: String,
        override val sessionId: String,
        override val at: Long,
    ) : AgentEvent

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

    /**
     * A sub-agent, sent off to do something on its own.
     *
     * A tool call because that is what it is — the parent asked for it and waits for the report —
     * but the one tool whose card has a life of its own: it runs for minutes, says things, uses
     * tools, and can be stopped without stopping the session. Its [ToolCallStarted.callId] *is*
     * the sub-agent's name; everything it does arrives stamped with it in [AgentEvent.subAgentId].
     */
    data class Task(
        val description: String,
        val prompt: String? = null,
        /** The kind of sub-agent that was asked for — "Explore", "general-purpose". */
        val agentType: String? = null,
    ) : ToolCall {
        override val label: String get() = description
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

    /**
     * The agent is asking the user something, rather than asking to do something.
     *
     * It lives among the permission asks because that is literally where the answer travels: the
     * question tool's own input carries the field the answer goes in, and the host fills it in by
     * answering the permission request. Modelling it as a separate channel would have meant two
     * round trips racing over one tool call.
     *
     * The sheet owes this one a real answer or none at all. Denying and abandoning both stay
     * meaningful — "I would rather not say" and "I never saw it" are different, and the agent can
     * tell them apart — but there is no allow: nothing here is being permitted.
     */
    data class Questions(
        val questions: List<Question>,
        override val alwaysAllowScope: String? = null,
    ) : PermissionAsk {
        override val headline: String get() = when (questions.size) {
            0 -> "A question for you"
            1 -> questions.first().text
            else -> "${questions.size} questions for you"
        }
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

/**
 * One question, and the answers it will take.
 *
 * [text] is the question itself and doubles as its identity: the answer travels back keyed by it,
 * because that is the key the tool's own input uses. [header] is the two-or-three-word chip the
 * question is filed under — "Auth method", "Approach" — and is the label a card shows when there
 * is no room for the whole sentence.
 */
@Immutable
data class Question(
    val text: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiSelect: Boolean = false,
)

/**
 * One answer on offer.
 *
 * [description] is what the option means, and it is the reason this is a sheet rather than a row
 * of buttons: a choice between "Sonnet" and "Opus" with nothing said about either is not a choice
 * anyone can make from a phone.
 */
@Immutable
data class QuestionOption(val label: String, val description: String? = null)

@Immutable
sealed interface PermissionDecision {
    data object Allow : PermissionDecision

    /**
     * The user answered a [PermissionAsk.Questions], keyed by question text.
     *
     * Separate from [Allow] because it says something [Allow] cannot: that a person read the
     * question and chose. An answered question that came back as a bare allow would tell the agent
     * it may proceed while telling it nothing about what to proceed *with* — which is the exact
     * failure this whole path exists to end.
     *
     * A multi-select answer is its chosen labels joined by ", ", which is the shape the tool
     * documents for its own `answers` field. Box does not invent a richer one: the value is read by
     * a model, not parsed by a program, and matching the tool's own convention costs nothing.
     */
    data class Answered(val answers: Map<String, String>) : PermissionDecision

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

/**
 * Something the user showed the agent, alongside what they typed.
 *
 * A field on the turn rather than a sentence in it, because the UI draws a thumbnail and the
 * contract forbids doing that by parsing prose. On the wire it is one optional field, on
 * [subAgentId]'s precedent: a harness that has never heard of attachments ignores it and the agent
 * gets the text. Degraded, never wrong — unlike `interrupt`, where an ignored field would have
 * stopped the whole session.
 *
 * [guestPath] is where the file is inside the box, and the only address meaningful to the agent.
 * Nothing outside the box ever sees an Android `content://` uri, which would mean nothing in there
 * and be revoked by the time anything tried it.
 */
@Immutable
data class Attachment(
    val guestPath: String,
    val name: String,
    val mimeType: String,
    val bytes: Long,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

@Immutable
sealed interface Artifact {
    /** The agent's live desktop. */
    data object Computer : Artifact

    /** A forwarded guest port. */
    data class Preview(val url: String, val guestPort: Int) : Artifact

    /**
     * A file in the guest worth looking at — a report, a diagram, a screenshot.
     *
     * Separate from [Preview] because most of what an agent makes needs no server. Requiring one
     * to show a picture would mean the agent starting a web server to hand over a PNG, which is
     * absurd on a machine this size; this is the variant for everything that is just a file.
     *
     * It carries no bytes. The path is read when the user asks for it, through the same file
     * reader the Files panel uses — so a document artifact obeys the same size ceiling as
     * everything else Box shows from the guest, rather than inventing a second answer to
     * "too big".
     */
    data class Document(
        val guestPath: String,
        val name: String,
        val mimeType: String,
    ) : Artifact
}

@Immutable
sealed interface SessionOutcome {
    data class Completed(val summary: String? = null) : SessionOutcome
    data class Failed(val message: String) : SessionOutcome
    data object Interrupted : SessionOutcome
}


/**
 * Accounts an agent can ask Box to connect.
 *
 * An enum with one member, which is the point: the wire carries a name, and a name Box has never
 * heard of has to be droppable rather than drawable. A future harness asking for something this
 * version of the app cannot connect must not put an unanswerable button in the conversation.
 */
enum class ConnectService(val wire: String) {
    GitHub("github"),
    ;

    companion object {
        fun of(wire: String): ConnectService? = entries.firstOrNull { it.wire == wire }
    }
}
