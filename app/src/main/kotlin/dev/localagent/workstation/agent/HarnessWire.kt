package dev.localagent.workstation.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * The harness wire format, translated into Box's event model.
 *
 * A harness in the guest writes one JSON object per line describing what it is doing, in Box's
 * vocabulary rather than its own. This is the whole harness-agnostic boundary: a Cursor harness is
 * a different program in the guest emitting these same lines, and nothing here — or above here —
 * learns a second dialect.
 *
 * Two properties the rest of the app leans on:
 *
 *  - **Unknown is ignorable.** A line this build does not recognise returns null rather than
 *    throwing. A guest image is upgraded independently of the APK, so a newer harness emitting a
 *    newer event must degrade to silence, never to a crash.
 *  - **Ids are positional.** [eventId] is derived from the line's ordinal in the log, so replaying
 *    the same log produces the same ids. `AgentBackend.events` promises that collecting twice
 *    yields the same prefix, and that promise is what restores a transcript after process death.
 */
internal object HarnessWire {

    /** What the log cannot tell us: which session this is, and what to call it. */
    data class Context(
        val sessionId: String,
        val harnessId: String,
        val title: String,
        val workingDirectory: String = "/workspace",
    )

    /**
     * One log line to one event, or null when the line is empty, malformed, or of a kind this
     * build does not model.
     */
    fun parse(line: String, context: Context, ordinal: Long): AgentEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        val eventId = "${context.sessionId}#$ordinal"
        val at = json.optLong("at", System.currentTimeMillis())
        val session = context.sessionId
        // Absent on everything the session's own agent does, which is most lines. A harness that
        // has never heard of sub-agents therefore keeps working unchanged: no field, one author.
        val agent = json.optStringOrNull("subAgentId")

        return when (json.optString("type")) {
            "session_started" -> AgentEvent.SessionStarted(
                eventId, session, at,
                harnessId = context.harnessId,
                title = context.title,
                workingDirectory = json.optStringOrNull("cwd") ?: context.workingDirectory,
            )

            "session_ended" -> AgentEvent.SessionEnded(
                eventId, session, at, outcome(json.optJSONObject("outcome")),
            )

            "user_message" -> AgentEvent.UserMessage(
                eventId, session, at, text = json.optString("text"),
            )

            "message" -> AgentEvent.AgentMessage(
                eventId, session, at,
                messageId = json.optString("messageId", eventId),
                text = json.optString("text"),
                complete = json.optBoolean("complete", true),
                subAgentId = agent,
            )

            "thinking" -> AgentEvent.AgentThinking(
                eventId, session, at,
                messageId = json.optString("messageId", eventId),
                text = json.optString("text"),
                complete = json.optBoolean("complete", true),
                subAgentId = agent,
            )

            "tool_started" -> AgentEvent.ToolCallStarted(
                eventId, session, at,
                callId = json.optString("callId"),
                call = toolCall(json.optJSONObject("tool"), context.workingDirectory),
                subAgentId = agent,
            )

            "tool_progress" -> AgentEvent.ToolCallProgress(
                eventId, session, at,
                callId = json.optString("callId"),
                chunk = json.optString("chunk"),
                subAgentId = agent,
            )

            "tool_finished" -> AgentEvent.ToolCallFinished(
                eventId, session, at,
                callId = json.optString("callId"),
                outcome = toolOutcome(json.optJSONObject("outcome")),
                subAgentId = agent,
            )

            "file_changed" -> {
                val path = json.optString("path")
                val patch = json.optStringOrNull("patch") ?: return null
                AgentEvent.FileChanged(
                    eventId, session, at,
                    callId = json.optStringOrNull("callId"),
                    diff = UnifiedDiff.parse(path, patch, changeKind(json.optString("changeKind"))),
                    subAgentId = agent,
                )
            }

            "permission_requested" -> AgentEvent.PermissionRequested(
                eventId, session, at,
                requestId = json.optString("requestId"),
                ask = permissionAsk(json.optJSONObject("ask"), context.workingDirectory),
            )

            "permission_resolved" -> AgentEvent.PermissionResolved(
                eventId, session, at,
                requestId = json.optString("requestId"),
                decision = decision(json.optString("decision")),
            )

            "task_progress" -> AgentEvent.TaskProgress(
                eventId, session, at,
                planId = json.optString("planId", eventId),
                items = json.optJSONArray("items").mapObjects { item ->
                    TaskItem(item.optString("text"), taskState(item.optString("state")))
                },
                subAgentId = agent,
            )

            "permission_mode" -> AgentEvent.PermissionModeChanged(
                eventId, session, at, mode = permissionMode(json.optString("mode")),
            )

            "activity" -> AgentEvent.ActivityChanged(
                eventId, session, at, activity(json.optJSONObject("activity")),
            )

            "error" -> AgentEvent.AgentError(
                eventId, session, at,
                message = json.optString("message", "The agent reported an error."),
                detail = json.optStringOrNull("detail"),
                recoverable = json.optBoolean("recoverable", true),
            )

            else -> null
        }
    }

    // ---- payloads ----------------------------------------------------------

    private fun toolCall(json: JSONObject?, defaultCwd: String): ToolCall {
        if (json == null) return ToolCall.Generic("Tool")
        return when (json.optString("kind")) {
            "shell" -> ToolCall.Shell(
                command = json.optString("command"),
                workingDirectory = json.optStringOrNull("workingDirectory") ?: defaultCwd,
            )
            "read_file" -> ToolCall.ReadFile(
                path = json.optString("path"),
                range = range(json),
            )
            "edit_file" -> ToolCall.EditFile(json.optString("path"), json.optStringOrNull("summary"))
            "write_file" -> ToolCall.WriteFile(
                path = json.optString("path"),
                bytes = if (json.isNull("bytes")) null else json.optLong("bytes"),
            )
            "search" -> ToolCall.Search(json.optString("query"), json.optStringOrNull("scope"))
            "fetch" -> ToolCall.Fetch(json.optString("url"))
            "task" -> ToolCall.Task(
                // A sub-agent with no description is still a sub-agent worth showing, and the
                // label is the one thing the card cannot do without.
                description = json.optStringOrNull("description") ?: "Sub-agent",
                prompt = json.optStringOrNull("prompt"),
                agentType = json.optStringOrNull("agentType"),
            )
            else -> ToolCall.Generic(
                name = json.optStringOrNull("name") ?: "Tool",
                arguments = json.optJSONArray("arguments").mapPairs(),
            )
        }
    }

    private fun range(json: JSONObject): IntRange? {
        if (json.isNull("from") || json.isNull("to")) return null
        val from = json.optInt("from")
        val to = json.optInt("to")
        return if (to >= from) from..to else null
    }

    private fun toolOutcome(json: JSONObject?): ToolOutcome = when (json?.optString("status")) {
        "success" -> ToolOutcome.Success(
            summary = json.optStringOrNull("summary"),
            output = json.optString("output"),
            exitCode = json.optInt("exitCode", 0),
        )
        "failure" -> ToolOutcome.Failure(
            message = json.optStringOrNull("message") ?: "The tool failed.",
            output = json.optString("output"),
            exitCode = if (json.isNull("exitCode")) null else json.optInt("exitCode"),
        )
        "denied" -> ToolOutcome.Denied
        "cancelled" -> ToolOutcome.Cancelled
        // A finish with no readable status still finishes the card: a call stuck spinning forever
        // is worse than one that admits it does not know how it went.
        else -> ToolOutcome.Success()
    }

    private fun permissionAsk(json: JSONObject?, defaultCwd: String): PermissionAsk {
        if (json == null) {
            return PermissionAsk.Generic("Permission needed", "The agent needs your decision.")
        }
        val scope = json.optStringOrNull("alwaysAllowScope")
        return when (json.optString("kind")) {
            "edit_file" -> {
                val path = json.optString("path")
                val patch = json.optStringOrNull("patch")
                if (patch == null) {
                    // No diff to show. Rather than a blank sheet, say plainly what is being asked.
                    PermissionAsk.Generic(
                        title = "Edit ${path.substringAfterLast('/')}",
                        description = json.optStringOrNull("rationale")
                            ?: "The agent wants to change this file.",
                        details = listOf("File" to path),
                        alwaysAllowScope = scope,
                    )
                } else {
                    PermissionAsk.EditFile(
                        diff = UnifiedDiff.parse(path, patch, changeKind(json.optString("changeKind"))),
                        rationale = json.optStringOrNull("rationale"),
                        alwaysAllowScope = scope,
                    )
                }
            }
            "run_command" -> PermissionAsk.RunCommand(
                command = json.optString("command"),
                workingDirectory = json.optStringOrNull("workingDirectory") ?: defaultCwd,
                rationale = json.optStringOrNull("rationale"),
                destructive = json.optBoolean("destructive", false),
                alwaysAllowScope = scope,
            )
            "network_access" -> PermissionAsk.NetworkAccess(
                host = json.optString("host"),
                purpose = json.optStringOrNull("purpose"),
                alwaysAllowScope = scope,
            )
            else -> PermissionAsk.Generic(
                title = json.optStringOrNull("title") ?: "Permission needed",
                description = json.optStringOrNull("description")
                    ?: "The agent needs your decision.",
                details = json.optJSONArray("details").mapPairs(),
                alwaysAllowScope = scope,
            )
        }
    }

    /**
     * Anything unrecognised is [PermissionMode.Ask], which is the one mode that cannot surprise
     * anybody: a newer harness naming a mode this build has never heard of must not leave the
     * composer claiming edits are being waved through.
     */
    private fun permissionMode(value: String): PermissionMode = when (value) {
        "accept_edits" -> PermissionMode.AcceptEdits
        "auto" -> PermissionMode.Auto
        else -> PermissionMode.Ask
    }

    private fun decision(value: String): PermissionDecision = when (value) {
        "allow" -> PermissionDecision.Allow
        "allow_always" -> PermissionDecision.AllowAlways("this session")
        "deny" -> PermissionDecision.Deny
        else -> PermissionDecision.Abandoned
    }

    private fun activity(json: JSONObject?): AgentActivity = when (json?.optString("kind")) {
        "thinking" -> AgentActivity.Thinking(json.optStringOrNull("label"))
        "working" -> AgentActivity.Working(json.optStringOrNull("label") ?: "Working")
        "awaiting_permission" -> AgentActivity.AwaitingPermission(json.optString("requestId"))
        "awaiting_input" -> AgentActivity.AwaitingInput
        "ended" -> AgentActivity.Ended
        else -> AgentActivity.Idle
    }

    private fun outcome(json: JSONObject?): SessionOutcome = when (json?.optString("status")) {
        "completed" -> SessionOutcome.Completed(json.optStringOrNull("summary"))
        "failed" -> SessionOutcome.Failed(
            json.optStringOrNull("message") ?: "The agent stopped.",
        )
        else -> SessionOutcome.Interrupted
    }

    private fun changeKind(value: String): ChangeKind = when (value) {
        "create" -> ChangeKind.Create
        "delete" -> ChangeKind.Delete
        "rename" -> ChangeKind.Rename
        else -> ChangeKind.Modify
    }

    private fun taskState(value: String): TaskState = when (value) {
        "in_progress", "running" -> TaskState.Running
        "completed", "done" -> TaskState.Done
        "failed" -> TaskState.Failed
        "skipped" -> TaskState.Skipped
        else -> TaskState.Pending
    }

    // ---- json helpers ------------------------------------------------------

    /** org.json turns a missing string into "", which is not the same thing as absent. */
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }.map(transform)
    }

    /** `[["key", "value"], …]` — the shape both generic tool arguments and ask details use. */
    private fun JSONArray?.mapPairs(): List<Pair<String, String>> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val pair = optJSONArray(index) ?: return@mapNotNull null
            if (pair.length() < 2) null else pair.optString(0) to pair.optString(1)
        }
    }
}
