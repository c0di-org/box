package dev.localagent.workstation.agent

import androidx.compose.runtime.Immutable

/**
 * What the conversation view draws. Produced by folding an [AgentEvent] log through
 * [TranscriptBuilder]; never constructed by a backend directly.
 *
 * The split matters: the event log is append-only and replayable, while the transcript is a
 * *collapsed* view where a tool call and its result are one card, a streamed message is one
 * bubble, and a checklist that ticked four times is one block.
 */
@Immutable
data class Transcript(
    val sessionId: String,
    val items: List<TranscriptItem> = emptyList(),
    val activity: AgentActivity = AgentActivity.Idle,
    val pendingPermission: PendingPermission? = null,
    val outcome: SessionOutcome? = null,
    val workingDirectory: String = "/workspace",
) {
    val isBusy: Boolean
        get() = activity is AgentActivity.Thinking || activity is AgentActivity.Working
}

@Immutable
data class PendingPermission(val requestId: String, val ask: PermissionAsk, val askedAt: Long)

@Immutable
sealed interface TranscriptItem {
    /** Stable across rebuilds — used as the LazyColumn key. */
    val key: String
    val at: Long

    data class User(override val key: String, override val at: Long, val text: String) : TranscriptItem

    data class Agent(
        override val key: String,
        override val at: Long,
        val text: String,
        val streaming: Boolean,
    ) : TranscriptItem

    data class Thinking(
        override val key: String,
        override val at: Long,
        val text: String,
        val streaming: Boolean,
    ) : TranscriptItem

    /** A tool call folded together with its streamed output and final outcome. */
    data class Tool(
        override val key: String,
        override val at: Long,
        val callId: String,
        val call: ToolCall,
        val output: String,
        val outcome: ToolOutcome?,
    ) : TranscriptItem {
        val running: Boolean get() = outcome == null
    }

    data class Diff(
        override val key: String,
        override val at: Long,
        val diff: FileDiff,
    ) : TranscriptItem

    data class Checklist(
        override val key: String,
        override val at: Long,
        val items: List<TaskItem>,
    ) : TranscriptItem

    /** Inline record of a permission moment. The live prompt is the sheet, not this. */
    data class Permission(
        override val key: String,
        override val at: Long,
        val requestId: String,
        val ask: PermissionAsk,
        val decision: PermissionDecision?,
    ) : TranscriptItem

    data class Artifacts(
        override val key: String,
        override val at: Long,
        val artifacts: List<Artifact>,
    ) : TranscriptItem

    data class Error(
        override val key: String,
        override val at: Long,
        val message: String,
        val detail: String?,
        val recoverable: Boolean,
    ) : TranscriptItem

    data class Ended(
        override val key: String,
        override val at: Long,
        val outcome: SessionOutcome,
    ) : TranscriptItem
}

/**
 * Incremental fold. Feed it events in order; call [build] whenever the UI needs a snapshot.
 *
 * Late-arriving events for unknown ids are tolerated rather than dropped — a reconnect can
 * deliver a `ToolCallFinished` whose `ToolCallStarted` was lost, and showing an orphaned result
 * beats showing nothing.
 */
class TranscriptBuilder(private val sessionId: String) {
    private val items = LinkedHashMap<String, TranscriptItem>()
    private val toolKeys = HashMap<String, String>()
    private val messageKeys = HashMap<String, String>()
    private val planKeys = HashMap<String, String>()
    private val permissionKeys = HashMap<String, String>()
    private var activity: AgentActivity = AgentActivity.Idle
    private var pending: PendingPermission? = null
    private var outcome: SessionOutcome? = null
    private var workingDirectory: String = "/workspace"
    private var lastArtifactKey: String? = null

    fun accept(event: AgentEvent) {
        // Any event other than another artifact offer breaks the artifact run.
        if (event !is AgentEvent.ArtifactOffered) lastArtifactKey = null

        when (event) {
            is AgentEvent.SessionStarted -> {
                workingDirectory = event.workingDirectory
            }

            is AgentEvent.SessionEnded -> {
                outcome = event.outcome
                activity = AgentActivity.Ended
                pending = null
                // Nothing can arrive for a call once the session that owned it has ended, so a
                // card still spinning at this point spins forever — after a crash, an interrupt,
                // or a log that was truncated mid-run. `HarnessWire.toolOutcome` settles the same
                // argument for a finish it cannot read: a call that admits it does not know how it
                // went beats one that pretends to still be working. Cancelled rather than failed,
                // because what stopped it was the session ending, not the tool.
                items.entries.forEach { entry ->
                    val tool = entry.value as? TranscriptItem.Tool ?: return@forEach
                    if (tool.running) entry.setValue(tool.copy(outcome = ToolOutcome.Cancelled))
                }
                put(TranscriptItem.Ended("end:${event.eventId}", event.at, event.outcome))
            }

            is AgentEvent.UserMessage ->
                put(TranscriptItem.User("user:${event.eventId}", event.at, event.text))

            is AgentEvent.AgentMessage -> {
                val key = messageKeys.getOrPut(event.messageId) { "msg:${event.messageId}" }
                val at = items[key]?.at ?: event.at
                put(TranscriptItem.Agent(key, at, event.text, streaming = !event.complete))
            }

            is AgentEvent.AgentThinking -> {
                val key = messageKeys.getOrPut(event.messageId) { "think:${event.messageId}" }
                val at = items[key]?.at ?: event.at
                put(TranscriptItem.Thinking(key, at, event.text, streaming = !event.complete))
            }

            is AgentEvent.ToolCallStarted -> {
                val key = toolKeys.getOrPut(event.callId) { "tool:${event.callId}" }
                put(TranscriptItem.Tool(key, event.at, event.callId, event.call, output = "", outcome = null))
            }

            is AgentEvent.ToolCallProgress -> {
                val existing = existingTool(event.callId, event.at)
                put(existing.copy(output = existing.output + event.chunk))
            }

            is AgentEvent.ToolCallFinished -> {
                val existing = existingTool(event.callId, event.at)
                val merged = when (val result = event.outcome) {
                    is ToolOutcome.Success ->
                        if (result.output.isNotEmpty()) existing.output + result.output else existing.output
                    is ToolOutcome.Failure ->
                        if (result.output.isNotEmpty()) existing.output + result.output else existing.output
                    else -> existing.output
                }
                put(existing.copy(output = merged, outcome = event.outcome))
            }

            is AgentEvent.FileChanged ->
                put(TranscriptItem.Diff("diff:${event.eventId}", event.at, event.diff))

            is AgentEvent.PermissionRequested -> {
                val key = permissionKeys.getOrPut(event.requestId) { "perm:${event.requestId}" }
                pending = PendingPermission(event.requestId, event.ask, event.at)
                activity = AgentActivity.AwaitingPermission(event.requestId)
                put(TranscriptItem.Permission(key, event.at, event.requestId, event.ask, decision = null))
            }

            is AgentEvent.PermissionResolved -> {
                val key = permissionKeys[event.requestId]
                val existing = key?.let { items[it] as? TranscriptItem.Permission }
                if (existing != null) {
                    put(existing.copy(decision = event.decision))
                }
                if (pending?.requestId == event.requestId) {
                    pending = null
                    if (activity is AgentActivity.AwaitingPermission) activity = AgentActivity.Idle
                }
            }

            is AgentEvent.TaskProgress -> {
                val key = planKeys.getOrPut(event.planId) { "plan:${event.planId}" }
                val at = items[key]?.at ?: event.at
                put(TranscriptItem.Checklist(key, at, event.items))
            }

            is AgentEvent.ActivityChanged -> {
                activity = event.activity
                if (event.activity !is AgentActivity.AwaitingPermission) pending = null
            }

            is AgentEvent.ArtifactOffered -> {
                val run = lastArtifactKey?.let { items[it] as? TranscriptItem.Artifacts }
                if (run != null) {
                    put(run.copy(artifacts = run.artifacts + event.artifact))
                } else {
                    val key = "artifact:${event.eventId}"
                    lastArtifactKey = key
                    put(TranscriptItem.Artifacts(key, event.at, listOf(event.artifact)))
                }
            }

            is AgentEvent.AgentError ->
                put(
                    TranscriptItem.Error(
                        key = "err:${event.eventId}",
                        at = event.at,
                        message = event.message,
                        detail = event.detail,
                        recoverable = event.recoverable,
                    ),
                )
        }
    }

    private fun existingTool(callId: String, at: Long): TranscriptItem.Tool {
        val key = toolKeys.getOrPut(callId) { "tool:$callId" }
        return items[key] as? TranscriptItem.Tool
            ?: TranscriptItem.Tool(key, at, callId, ToolCall.Generic("Tool"), output = "", outcome = null)
    }

    private fun put(item: TranscriptItem) {
        items[item.key] = item
    }

    fun build(): Transcript = Transcript(
        sessionId = sessionId,
        items = items.values.toList(),
        activity = activity,
        pendingPermission = pending,
        outcome = outcome,
        workingDirectory = workingDirectory,
    )
}

fun List<AgentEvent>.toTranscript(sessionId: String): Transcript =
    TranscriptBuilder(sessionId).apply { forEach(::accept) }.build()
