package dev.localagent.workstation.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A transcript, written out as Markdown a person can read anywhere.
 *
 * Rendered from [Transcript] rather than from the session log, and deliberately so. The log is the
 * source of truth for *replay* — append-only, offset-stamped, and full of the intermediate states
 * that [TranscriptBuilder] folds away: eight `AgentMessage` events for one streamed paragraph, a
 * `ToolCallStarted` whose result arrives forty lines later. Exporting that would hand the user a
 * file that says everything four times. What is on screen is already the collapsed, deduplicated
 * version, and it is what "save this conversation" means.
 *
 * Pure, and in `agent/` rather than `ui/`, so the shape of the file is settled by a unit test
 * instead of by reading one on a phone.
 *
 * Tool output is *bounded* here ([OUTPUT_LIMIT]). A single `apt install` can carry half a megabyte
 * of progress bars, and a transcript that cannot be opened in a text editor is not a transcript.
 */
fun Transcript.toMarkdown(
    title: String,
    harnessName: String? = null,
    exportedAt: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val clock = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { this.timeZone = timeZone }
    val out = StringBuilder()

    out.append("# ").append(title.ifBlank { "Box task" }).append("\n\n")
    harnessName?.takeIf { it.isNotBlank() }?.let { out.append("**Agent:** ").append(it).append("  \n") }
    out.append("**Working directory:** `").append(workingDirectory).append("`  \n")
    out.append("**Exported:** ").append(clock.format(Date(exportedAt))).append("\n\n")
    when (val ended = outcome) {
        is SessionOutcome.Completed ->
            out.append("**Outcome:** finished")
                .append(ended.summary?.let { " — $it" } ?: "").append("\n\n")
        is SessionOutcome.Failed -> out.append("**Outcome:** failed — ").append(ended.message).append("\n\n")
        SessionOutcome.Interrupted -> out.append("**Outcome:** stopped by you\n\n")
        null -> Unit
    }
    out.append("---\n")

    items.forEach { item -> out.append('\n').append(render(item, clock, depth = 0)) }

    // A transcript saved mid-turn is the normal case — the whole point is pocketing the phone
    // while it works — so say so rather than letting the file just stop.
    if (isBusy) out.append("\n_Still working when this was saved._\n")
    return out.toString()
}

private fun render(item: TranscriptItem, clock: SimpleDateFormat, depth: Int): String {
    val head = "#".repeat((depth + 2).coerceAtMost(6))
    val at = clock.format(Date(item.at))
    return when (item) {
        is TranscriptItem.User -> buildString {
            append("$head You · $at\n\n")
            append(item.text.trim()).append('\n')
            item.attachments.forEach { append("\n_Attached: ${it.name} (`${it.guestPath}`)_\n") }
        }

        is TranscriptItem.Agent -> "$head Agent · $at\n\n${item.text.trim()}\n"

        // Kept, and marked. Reasoning the agent chose to show is part of what happened; silently
        // dropping it would make the export disagree with the screen it was taken from.
        is TranscriptItem.Thinking -> buildString {
            append("$head Thinking · $at\n\n")
            item.text.trim().lineSequence().forEach { append("> ").append(it).append('\n') }
        }

        is TranscriptItem.Tool -> buildString {
            append("$head Tool · ${item.call.label} · $at\n\n")
            append(callDetail(item.call))
            val body = (item.output.ifBlank { outcomeOutput(item.outcome) }).trim()
            if (body.isNotEmpty()) append("\n```\n").append(clip(body)).append("\n```\n")
            outcomeLine(item.outcome)?.let { append('\n').append(it).append('\n') }
        }

        is TranscriptItem.SubAgent -> buildString {
            append("$head Sub-agent · ${item.task.description} · $at\n\n")
            item.task.agentType?.let { append("_Type: ").append(it).append("_\n\n") }
            // Nested one level deeper, which is exactly how the card reads on screen.
            item.items.forEach { append(render(it, clock, depth + 1)).append('\n') }
            outcomeLine(item.outcome)?.let { append(it).append('\n') }
        }

        is TranscriptItem.Diff -> buildString {
            val d = item.diff
            append("$head Changed `${d.path}` · +${d.additions} −${d.deletions} · $at\n\n")
            append("```diff\n")
            d.hunks.forEach { hunk ->
                append("@@ -${hunk.oldStart} +${hunk.newStart} @@")
                    .append(hunk.heading?.let { " $it" } ?: "").append('\n')
                hunk.lines.forEach { line ->
                    val mark = when (line.kind) {
                        DiffLineKind.Added -> "+"
                        DiffLineKind.Removed -> "-"
                        DiffLineKind.Context -> " "
                    }
                    append(mark).append(line.text).append('\n')
                }
            }
            append("```\n")
        }

        is TranscriptItem.Checklist -> buildString {
            append("$head Plan · $at\n\n")
            item.items.forEach { task ->
                val box = when (task.state) {
                    TaskState.Done -> "[x]"
                    TaskState.Failed -> "[!]"
                    TaskState.Skipped -> "[~]"
                    TaskState.Running, TaskState.Pending -> "[ ]"
                }
                append("- ").append(box).append(' ').append(task.text).append('\n')
            }
        }

        is TranscriptItem.Permission -> buildString {
            append("$head Permission · ${item.ask.headline} · $at\n\n")
            append(askDetail(item.ask))
            append('\n').append(
                when (val decision = item.decision) {
                    PermissionDecision.Allow -> "**You allowed this.**"
                    is PermissionDecision.AllowAlways -> "**You allowed this always** (${decision.scope})."
                    PermissionDecision.Deny -> "**You declined this.**"
                    PermissionDecision.Abandoned -> "**Never answered.**"
                    is PermissionDecision.Answered -> buildString {
                        append("**You answered:**\n")
                        decision.answers.forEach { (k, v) -> append("- $k: $v\n") }
                    }.trimEnd()
                    null -> "**Waiting on you.**"
                },
            ).append('\n')
        }

        is TranscriptItem.Artifacts -> buildString {
            append("$head Offered · $at\n\n")
            item.artifacts.forEach { artifact ->
                append(
                    when (artifact) {
                        Artifact.Computer -> "- The computer's desktop"
                        is Artifact.Preview -> "- ${artifact.url} (guest port ${artifact.guestPort})"
                        is Artifact.Install -> "- ${artifact.name} (offered for install) — `${artifact.guestPath}`"
                        is Artifact.Document -> "- ${artifact.name} — `${artifact.guestPath}`"
                    },
                ).append('\n')
            }
        }

        is TranscriptItem.Error -> buildString {
            append("$head Error · $at\n\n")
            append("**").append(item.message).append("**\n")
            item.detail?.let { append('\n').append(clip(it)).append('\n') }
        }

        is TranscriptItem.Ended -> buildString {
            append("$head Ended · $at\n\n")
            append(
                when (val ended = item.outcome) {
                    is SessionOutcome.Completed -> "Finished" + (ended.summary?.let { ": $it" } ?: ".")
                    is SessionOutcome.Failed -> "Failed: ${ended.message}"
                    SessionOutcome.Interrupted -> "Stopped by you."
                },
            ).append('\n')
        }
    }
}

private fun callDetail(call: ToolCall): String = when (call) {
    is ToolCall.Shell -> "In `${call.workingDirectory}`:\n\n```sh\n${call.command}\n```\n"
    is ToolCall.ReadFile ->
        "Read `${call.path}`" + (call.range?.let { " lines ${it.first}–${it.last}" } ?: "") + "\n"
    is ToolCall.EditFile -> "Edited `${call.path}`" + (call.summary?.let { " — $it" } ?: "") + "\n"
    is ToolCall.WriteFile -> "Wrote `${call.path}`" + (call.bytes?.let { " ($it bytes)" } ?: "") + "\n"
    is ToolCall.Search -> "Searched for “${call.query}”" + (call.scope?.let { " in ${it}" } ?: "") + "\n"
    is ToolCall.Fetch -> "Fetched ${call.url}\n"
    is ToolCall.Task -> (call.prompt?.let { "$it\n" } ?: "")
    is ToolCall.Generic -> buildString {
        call.arguments.forEach { (name, value) -> append("- **$name:** $value\n") }
    }
}

private fun askDetail(ask: PermissionAsk): String = when (ask) {
    is PermissionAsk.RunCommand -> buildString {
        ask.rationale?.let { append(it).append("\n\n") }
        append("In `${ask.workingDirectory}`:\n\n```sh\n${ask.command}\n```\n")
    }
    is PermissionAsk.EditFile ->
        (ask.rationale?.let { "$it\n\n" } ?: "") + "Edit `${ask.diff.path}`\n"
    is PermissionAsk.NetworkAccess ->
        "Reach ${ask.host}" + (ask.purpose?.let { " — $it" } ?: "") + "\n"
    is PermissionAsk.Questions -> buildString {
        ask.questions.forEach { append("- ").append(it.text).append('\n') }
    }
    is PermissionAsk.Generic -> buildString {
        append(ask.description).append('\n')
        ask.details.forEach { (name, value) -> append("- **$name:** $value\n") }
    }
}

private fun outcomeOutput(outcome: ToolOutcome?): String = when (outcome) {
    is ToolOutcome.Success -> outcome.output
    is ToolOutcome.Failure -> outcome.output
    else -> ""
}

private fun outcomeLine(outcome: ToolOutcome?): String? = when (outcome) {
    null -> "_Still running when this was saved._"
    is ToolOutcome.Success -> outcome.summary?.let { "_${it}_" }
    is ToolOutcome.Failure ->
        "**Failed:** ${outcome.message}" + (outcome.exitCode?.let { " (exit $it)" } ?: "")
    ToolOutcome.Denied -> "**You declined this.**"
    ToolOutcome.Cancelled -> "**Stopped.**"
}

/** Keep the head and the tail: a failure is usually visible at one end or the other. */
private fun clip(text: String): String {
    if (text.length <= OUTPUT_LIMIT) return text
    val half = OUTPUT_LIMIT / 2
    val dropped = text.length - OUTPUT_LIMIT
    return text.take(half) + "\n\n… $dropped characters trimmed …\n\n" + text.takeLast(half)
}

private const val OUTPUT_LIMIT = 4_000

/** `Fix the login bug` → `fix-the-login-bug`. Used for the suggested file name. */
fun transcriptFileName(title: String, at: Long = System.currentTimeMillis()): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(at))
    val slug = title.lowercase(Locale.US)
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-').filter { it.isNotEmpty() }
        .take(6)
        .joinToString("-")
    return if (slug.isEmpty()) "box-task-$stamp.md" else "$slug-$stamp.md"
}
