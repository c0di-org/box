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
    /**
     * Every request still waiting on the user, oldest first.
     *
     * A list, not a slot. One turn can ask for two commands and block on both, and each is answered
     * on its own — so the sheet works through them in order and the inline cards can be answered in
     * any order at all.
     */
    val pendingPermissions: List<PendingPermission> = emptyList(),
    val outcome: SessionOutcome? = null,
    val workingDirectory: String = "/workspace",
) {
    val isBusy: Boolean
        get() = activity is AgentActivity.Thinking || activity is AgentActivity.Working

    /** The one the sheet raises: whatever has been waiting longest. */
    val pendingPermission: PendingPermission? get() = pendingPermissions.firstOrNull()
}

@Immutable
data class PendingPermission(val requestId: String, val ask: PermissionAsk, val askedAt: Long)

@Immutable
sealed interface TranscriptItem {
    /** Stable across rebuilds — used as the LazyColumn key. */
    val key: String
    val at: Long

    data class User(
        override val key: String,
        override val at: Long,
        val text: String,
        val attachments: List<Attachment> = emptyList(),
    ) : TranscriptItem

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

    /**
     * A sub-agent and everything it did, folded into one card.
     *
     * The nesting is the point. Letting a sub-agent's messages and tool calls land in the parent's
     * transcript in arrival order — which is what Box did before this existed — reads as one agent
     * talking over itself: two voices, four interleaved tool cards, and no way to tell which piece
     * of work is the one you can stop. [items] is that sub-agent's own transcript, folded by
     * exactly the same rules, so a card can be opened and read as the conversation it is.
     */
    data class SubAgent(
        override val key: String,
        override val at: Long,
        val subAgentId: String,
        val task: ToolCall.Task,
        val items: List<TranscriptItem>,
        val outcome: ToolOutcome?,
    ) : TranscriptItem {
        val running: Boolean get() = outcome == null

        /** Stopped by the user, as opposed to having finished or failed on its own. */
        val stopped: Boolean get() = outcome is ToolOutcome.Cancelled

        /** The last thing it did, for the card that is still closed. */
        val latest: String? get() = items.asReversed().firstNotNullOfOrNull { it.headline() }
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
 * One line of "what is happening in there", for a collapsed sub-agent card.
 *
 * Null for the items that would say nothing useful on one line — reasoning it was asked to hide,
 * a permission note that is already a line of its own in the parent.
 */
private fun TranscriptItem.headline(): String? = when (this) {
    is TranscriptItem.Agent -> text.lineSequence().firstOrNull { it.isNotBlank() }?.take(140)
    is TranscriptItem.Tool -> call.label
    is TranscriptItem.SubAgent -> task.description
    is TranscriptItem.Diff -> "Changed ${diff.path.substringAfterLast('/')}"
    is TranscriptItem.Checklist ->
        items.firstOrNull { it.state == TaskState.Running }?.text ?: items.lastOrNull()?.text
    is TranscriptItem.Error -> message
    is TranscriptItem.User, is TranscriptItem.Thinking, is TranscriptItem.Permission,
    is TranscriptItem.Artifacts, is TranscriptItem.Ended,
    -> null
}

/**
 * Incremental fold. Feed it events in order; call [build] whenever the UI needs a snapshot.
 *
 * Late-arriving events for unknown ids are tolerated rather than dropped — a reconnect can
 * deliver a `ToolCallFinished` whose `ToolCallStarted` was lost, and showing an orphaned result
 * beats showing nothing.
 */
class TranscriptBuilder(
    private val sessionId: String,
    /**
     * Set only on a nested fold: the sub-agent whose transcript this instance is building.
     *
     * Without it the routing below never terminates. Events keep their [AgentEvent.subAgentId] all
     * the way down — nothing rewrites an event on its way into a card — so a nested builder handed
     * one of its own events would forward it to itself forever. Knowing its own name is how a fold
     * recognises "this one is mine, fold it flat".
     */
    private val owner: String? = null,
) {
    private val items = LinkedHashMap<String, TranscriptItem>()
    private val toolKeys = HashMap<String, String>()
    private val messageKeys = HashMap<String, String>()
    private val planKeys = HashMap<String, String>()
    private val permissionKeys = HashMap<String, String>()

    /** One nested fold per sub-agent, keyed by the [ToolCall.Task] call that spawned it. */
    private val subAgents = LinkedHashMap<String, SubAgentFold>()
    private var activity: AgentActivity = AgentActivity.Idle

    /** Keyed by request id and insertion-ordered: several can be outstanding, answered in any order. */
    private val pending = LinkedHashMap<String, PendingPermission>()
    private var outcome: SessionOutcome? = null
    private var workingDirectory: String = "/workspace"
    private var lastArtifactKey: String? = null

    fun accept(event: AgentEvent) {
        // Any event other than another artifact offer breaks the artifact run.
        if (event !is AgentEvent.ArtifactOffered) lastArtifactKey = null

        val author = event.subAgentId
        if (author != null && author != owner) {
            route(author, event)
            return
        }

        when (event) {
            is AgentEvent.SessionStarted -> {
                workingDirectory = event.workingDirectory
            }

            is AgentEvent.SessionEnded -> {
                outcome = event.outcome
                activity = AgentActivity.Ended
                pending.clear()
                settleOpenCalls(event.at)
                put(TranscriptItem.Ended("end:${event.eventId}", event.at, event.outcome))
            }

            is AgentEvent.UserMessage -> {
                put(TranscriptItem.User("user:${event.eventId}", event.at, event.text, event.attachments))
                /*
                 * Someone said something, so the agent is working — whether or not the harness
                 * gets around to saying so. Between a turn being handed over and the first thing
                 * back there can be a long silence, and drawing an idle conversation across it is
                 * the version that is actually wrong; a harness that does narrate overwrites this
                 * a moment later.
                 *
                 * Only from a standstill. `Working`, `Thinking` and `AwaitingPermission` all say
                 * more than this does, and a message typed while an agent is mid-task — which Box
                 * allows, and queues — must not flatten one of those.
                 */
                if (activity == AgentActivity.Idle || activity == AgentActivity.Ended) {
                    activity = AgentActivity.Thinking()
                }
                // A session that has been spoken to again is not a session that ended. Left
                // behind, the outcome would keep a finished banner over a live conversation.
                outcome = null
            }

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
                val task = event.call as? ToolCall.Task
                if (task != null) {
                    // The call that names a sub-agent is the sub-agent's card, not a tool card.
                    val fold = subAgent(event.callId)
                    fold.task = task
                    publish(event.callId, fold, event.at)
                } else {
                    val key = toolKeys.getOrPut(event.callId) { "tool:${event.callId}" }
                    put(TranscriptItem.Tool(key, event.at, event.callId, event.call, output = "", outcome = null))
                }
            }

            is AgentEvent.ToolCallProgress -> {
                // A sub-agent's own progress is its nested transcript; the card has no output line
                // of its own to append to, and inventing a tool card for it would double it up.
                if (subAgents.containsKey(event.callId)) return
                val existing = existingTool(event.callId, event.at)
                put(existing.copy(output = existing.output + event.chunk))
            }

            is AgentEvent.ToolCallFinished -> {
                subAgents[event.callId]?.let { fold ->
                    fold.outcome = event.outcome
                    publish(event.callId, fold, event.at)
                    return
                }
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
                pending[event.requestId] = PendingPermission(event.requestId, event.ask, event.at)
                activity = AgentActivity.AwaitingPermission(waitingOn())
                put(TranscriptItem.Permission(key, event.at, event.requestId, event.ask, decision = null))
            }

            is AgentEvent.PermissionResolved -> {
                val key = permissionKeys[event.requestId]
                val existing = key?.let { items[it] as? TranscriptItem.Permission }
                if (existing != null) {
                    put(existing.copy(decision = event.decision))
                }
                pending.remove(event.requestId)
                if (activity is AgentActivity.AwaitingPermission) {
                    // Still blocked if anything else is waiting. Going idle here is what made a
                    // second request look answered the moment the first one was.
                    activity = if (pending.isEmpty()) {
                        AgentActivity.Idle
                    } else {
                        AgentActivity.AwaitingPermission(waitingOn())
                    }
                }
            }

            is AgentEvent.TaskProgress -> {
                val key = planKeys.getOrPut(event.planId) { "plan:${event.planId}" }
                val at = items[key]?.at ?: event.at
                put(TranscriptItem.Checklist(key, at, event.items))
            }

            is AgentEvent.ActivityChanged -> {
                // Whatever the harness says it is doing, and nothing else. This used to drop every
                // outstanding request unless the line happened to be about a permission — so one
                // "working" line, which a parallel turn emits freely while blocked on something
                // else, silently threw away a request the agent was still waiting on. A request is
                // outstanding until it is *resolved*, or until the session ends.
                //
                // Idle is the exception, and only because it cannot be true: a run that still owes
                // the user an answer is waiting on them, whatever else it has finished doing.
                activity = if (event.activity == AgentActivity.Idle && pending.isNotEmpty()) {
                    AgentActivity.AwaitingPermission(waitingOn())
                } else {
                    event.activity
                }
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

            /**
             * Nothing in the transcript.
             *
             * A request for an account is a live thing, not a thing that happened: it is drawn
             * from the outstanding request on the box — see `BoxUiState.connectRequest` — so that
             * one card can be answered from wherever the person happens to be looking, and so a
             * transcript replayed a week later does not offer a button for a question already
             * settled. What it led to is in the agent's own next sentence, which is the account
             * that ages correctly.
             */
            is AgentEvent.ConnectRequested -> Unit

            /** Ditto, and its ending is drawn by the card ceasing to exist. */
            is AgentEvent.ConnectResolved -> Unit

            /** A marker about this reading of the log, not a thing that happened in it. */
            is AgentEvent.CaughtUp -> Unit

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

    /**
     * Closes everything still open, at every depth, because the session that owned it has ended.
     *
     * Nothing can arrive for these afterwards, so a card still spinning here spins forever — after
     * a crash, an interrupt, or a log truncated mid-run. `HarnessWire.toolOutcome` settles the same
     * argument for a finish it cannot read: a call that admits it does not know how it went beats
     * one that pretends to still be working. Cancelled rather than failed, because what stopped it
     * was the session ending rather than the tool — which is also what a sub-agent's card already
     * means by it, and a delegate outliving the session it was sent from is not a state to draw.
     */
    private fun settleOpenCalls(at: Long) {
        items.entries.forEach { entry ->
            val tool = entry.value as? TranscriptItem.Tool ?: return@forEach
            if (tool.running) entry.setValue(tool.copy(outcome = ToolOutcome.Cancelled))
        }
        // Depth first: a sub-agent's own calls are settled before its card is republished, so the
        // card it ends up with is the finished transcript rather than the one being closed.
        subAgents.forEach { (id, fold) ->
            fold.inner.settleOpenCalls(at)
            if (fold.outcome == null) fold.outcome = ToolOutcome.Cancelled
            publish(id, fold, at)
        }
    }

    // ---- sub-agents --------------------------------------------------------

    /** A sub-agent's card, and the fold that keeps its transcript. */
    private class SubAgentFold(val key: String, sessionId: String, id: String) {
        /** Replaced by the real [ToolCall.Task] when its start event arrives — or if it never does,
         *  this stands in: a result whose call was lost to a reconnect still has to render. */
        var task: ToolCall.Task = ToolCall.Task("Sub-agent")
        var outcome: ToolOutcome? = null
        val inner = TranscriptBuilder(sessionId, owner = id)
    }

    /**
     * Hands an attributed event to the fold that owns it, however deep that is.
     *
     * A sub-agent can spawn one of its own, and the id on the event names the innermost author, so
     * the owner may be a fold inside a fold. Anything nobody claims opens a card here: the same
     * orphan tolerance the tool cards have, for the same reason — a reconnect that lost the start
     * event should cost the attribution, not the work.
     */
    private fun route(id: String, event: AgentEvent) {
        val holder = subAgents.entries.firstOrNull { (key, fold) -> key == id || fold.inner.owns(id) }
        val key = holder?.key ?: id
        val fold = holder?.value ?: subAgent(id)
        fold.inner.accept(event)
        publish(key, fold, event.at)
    }

    /** True when [id] names a sub-agent this fold, or one of its own, started. */
    private fun owns(id: String): Boolean =
        subAgents.containsKey(id) || subAgents.values.any { it.inner.owns(id) }

    private fun subAgent(id: String): SubAgentFold =
        subAgents.getOrPut(id) { SubAgentFold("agent:$id", sessionId, id) }

    private fun publish(id: String, fold: SubAgentFold, at: Long) {
        val existing = items[fold.key]
        put(
            TranscriptItem.SubAgent(
                key = fold.key,
                // The card keeps the place it first appeared, so a sub-agent that talks for two
                // minutes does not walk down the transcript while the user is reading it.
                at = existing?.at ?: at,
                subAgentId = id,
                task = fold.task,
                items = fold.inner.build().items,
                outcome = fold.outcome,
            ),
        )
    }

    private fun existingTool(callId: String, at: Long): TranscriptItem.Tool {
        val key = toolKeys.getOrPut(callId) { "tool:$callId" }
        return items[key] as? TranscriptItem.Tool
            ?: TranscriptItem.Tool(key, at, callId, ToolCall.Generic("Tool"), output = "", outcome = null)
    }

    private fun put(item: TranscriptItem) {
        items[item.key] = item
    }

    /** The oldest unanswered request — the one the activity line and the sheet are about. */
    private fun waitingOn(): String = pending.keys.first()

    fun build(): Transcript = Transcript(
        sessionId = sessionId,
        items = items.values.toList(),
        activity = activity,
        pendingPermissions = pending.values.toList(),
        outcome = outcome,
        workingDirectory = workingDirectory,
    )
}

fun List<AgentEvent>.toTranscript(sessionId: String): Transcript =
    TranscriptBuilder(sessionId).apply { forEach(::accept) }.build()
