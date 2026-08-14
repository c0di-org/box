package dev.localagent.workstation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.workstation.agent.AgentActivity
import dev.localagent.workstation.agent.Artifact
import dev.localagent.workstation.agent.Attachment
import dev.localagent.workstation.agent.CodeLanguage
import dev.localagent.workstation.agent.HarnessDescriptor
import dev.localagent.workstation.agent.PermissionAsk
import dev.localagent.workstation.agent.PermissionDecision
import dev.localagent.workstation.agent.SessionOutcome
import dev.localagent.workstation.agent.TaskItem
import dev.localagent.workstation.agent.TaskState
import dev.localagent.workstation.agent.ToolCall
import dev.localagent.workstation.agent.ToolOutcome
import dev.localagent.workstation.agent.TranscriptItem
import dev.localagent.workstation.files.Inbox
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

fun formatClock(epochMillis: Long): String =
    clockFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** Dispatches one folded transcript item to its renderer. */
@Composable
fun TranscriptRow(
    item: TranscriptItem,
    harness: HarnessDescriptor?,
    onOpenArtifact: (Artifact) -> Unit,
    onRetry: () -> Unit,
    onStopSubAgent: (String) -> Unit,
    onPermissionDecision: (String, PermissionDecision) -> Unit,
    onReviewPermission: (String) -> Unit,
    /** Half-finished question answers, held above the list. See [AnswerStore]. */
    answers: AnswerStore,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is TranscriptItem.User -> UserBubble(item, modifier)
        is TranscriptItem.Agent -> AgentProse(item, harness, modifier)
        is TranscriptItem.Thinking -> ThinkingBlock(item, modifier)
        is TranscriptItem.Tool -> ToolCard(item, modifier)
        is TranscriptItem.SubAgent -> SubAgentCard(
            item, onOpenArtifact, onRetry, onStopSubAgent,
            onPermissionDecision, onReviewPermission, answers, modifier,
        )
        is TranscriptItem.Diff -> DiffCard(item, modifier)
        is TranscriptItem.Checklist -> ChecklistCard(item, modifier)
        is TranscriptItem.Permission ->
            PermissionRecord(item, onPermissionDecision, onReviewPermission, answers, modifier)
        is TranscriptItem.Artifacts -> ArtifactRow(item, onOpenArtifact, modifier)
        is TranscriptItem.Error -> ErrorCard(item, onRetry, modifier)
        is TranscriptItem.Ended -> EndedRow(item, modifier)
    }
}

// ---------------------------------------------------------------------------
// Turns
// ---------------------------------------------------------------------------

@Composable
private fun UserBubble(item: TranscriptItem.User, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (dark) BoxUserBubble else BoxUserBubbleLight,
        contentColor = if (dark) Color(0xFFEEF0FF) else Color(0xFF1B2151),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("You", style = MaterialTheme.typography.labelLarge, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    formatClock(item.at),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    modifier = Modifier.alpha(0.7f),
                )
            }
            if (item.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                item.attachments.forEach { attachment ->
                    AttachmentTile(attachment)
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (item.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * Something the user showed the agent, drawn on their own turn.
 *
 * A picture is shown as a picture; everything else is a labelled row, in the spirit of
 * `ToolCall.Generic` — Box has no renderer for a PDF and will not pretend, but it can always say
 * what was sent and how big.
 *
 * The thumbnail is read from the phone's copy, found *from* the guest path rather than stored
 * beside it, because the guest path is the one the agent was told. When there is no file there the
 * row still draws: a file the user deleted from their own Files app is gone from the phone and
 * still in the box, and a turn that quietly lost its picture would hide that from them.
 */
@Composable
internal fun AttachmentTile(attachment: Attachment) {
    val context = LocalContext.current
    var thumbnail by remember(attachment.guestPath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(attachment.guestPath) {
        if (!attachment.isImage) return@LaunchedEffect
        thumbnail = withContext(Dispatchers.IO) { readThumbnail(context, attachment.guestPath) }
    }

    val picture = thumbnail
    if (picture != null) {
        Image(
            bitmap = picture,
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (attachment.isImage) Icons.Outlined.Image else Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        attachment.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatBytes(attachment.bytes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 11.sp,
                        modifier = Modifier.alpha(0.75f),
                    )
                }
            }
        }
    }
}

/**
 * A thumbnail, decoded at roughly the size it will be drawn at.
 *
 * Sampled down rather than loaded whole and scaled: this runs on a phone that is also emulating a
 * computer, and a full-resolution camera photograph is tens of megabytes of bitmap for a picture
 * the size of a message bubble.
 */
private fun readThumbnail(context: Context, guestPath: String): ImageBitmap? {
    val file = Inbox.phoneFile(context, guestPath) ?: return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > THUMBNAIL_PIXELS || bounds.outHeight / sample > THUMBNAIL_PIXELS) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
    }.getOrNull()
}

private const val THUMBNAIL_PIXELS = 1024

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

@Composable
private fun AgentProse(
    item: TranscriptItem.Agent,
    harness: HarnessDescriptor?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (harness != null) {
                HarnessMark(harness, 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    harness.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                formatClock(item.at),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(7.dp))
        SelectionContainer {
            // The caret is appended to the source rather than drawn beside the text, because the
            // last thing on screen mid-stream is whatever markdown was mid-sentence — a list item,
            // a heading, a line inside an unclosed fence — and the caret belongs at the end of
            // that, not floating under the block it was written into.
            MarkdownText(
                text = if (item.streaming) item.text + "▍" else item.text,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ThinkingBlock(item: TranscriptItem.Thinking, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (expanded) "Hide reasoning" else "Reasoning",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(expanded) {
            Text(
                item.text,
                Modifier.padding(start = 24.dp, top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

@Composable
private fun ToolCard(item: TranscriptItem.Tool, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val outcome = item.outcome
    val failed = outcome is ToolOutcome.Failure
    val denied = outcome is ToolOutcome.Denied
    val accent = when {
        failed -> MaterialTheme.colorScheme.error
        denied -> MaterialTheme.colorScheme.onSurfaceVariant
        item.running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }
    val hasBody = item.output.isNotBlank()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (hasBody) Modifier.clickable { expanded = !expanded } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        item.running -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = accent)
                        failed -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(17.dp), tint = accent)
                        denied -> Icon(Icons.Outlined.Close, null, Modifier.size(17.dp), tint = accent)
                        else -> Icon(toolIcon(item.call), null, Modifier.size(17.dp), tint = accent)
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.call.label,
                        fontFamily = if (item.call is ToolCall.Shell) FontFamily.Monospace else FontFamily.Default,
                        fontSize = if (item.call is ToolCall.Shell) 13.sp else 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    toolSubtitle(item)?.let { subtitle ->
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            color = if (failed) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (hasBody) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Hide output" else "Show output",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.call is ToolCall.Generic && item.call.arguments.isNotEmpty()) {
                Column(Modifier.padding(start = 45.dp, end = 14.dp, bottom = 12.dp)) {
                    item.call.arguments.forEach { (key, value) ->
                        Row {
                            Text(
                                "$key ",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                value,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(expanded && hasBody) {
                CodeBlock(
                    text = item.output,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 10.dp),
                    language = CodeLanguage.Plain,
                )
            }
        }
    }
}

/**
 * A sub-agent: one card for a whole delegated piece of work.
 *
 * Deliberately the tool card's shape — same surface, same border, same chevron — because to the
 * person reading it this *is* one step the agent took, and a second visual language for it would
 * suggest a second kind of thing to reason about. What it adds is the two affordances a tool card
 * has no use for: what the delegate was asked to do, and a way to tell it to stop.
 *
 * Opened, it draws its own transcript through [TranscriptRow] — the same renderers, one level in.
 * That is what keeps a sub-agent's shell command looking like a shell command.
 */
@Composable
private fun SubAgentCard(
    item: TranscriptItem.SubAgent,
    onOpenArtifact: (Artifact) -> Unit,
    onRetry: () -> Unit,
    onStop: (String) -> Unit,
    onPermissionDecision: (String, PermissionDecision) -> Unit,
    onReviewPermission: (String) -> Unit,
    answers: AnswerStore,
    modifier: Modifier = Modifier,
) {
    // Open while it is working, because a card that hides live work is a spinner with a chevron.
    // Once it has finished, closed: the parent's own summary is the answer, and this is the receipt.
    var expanded by rememberSaveable(item.key) { mutableStateOf(item.running) }
    val failed = item.outcome is ToolOutcome.Failure
    val accent = when {
        failed -> MaterialTheme.colorScheme.error
        item.stopped -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        item.running ->
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = accent)
                        failed -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(17.dp), tint = accent)
                        item.stopped -> Icon(Icons.Outlined.Close, null, Modifier.size(17.dp), tint = accent)
                        else -> Icon(Icons.Outlined.AccountTree, null, Modifier.size(17.dp), tint = accent)
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.task.description,
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (expanded) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subAgentSubtitle(item),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = if (failed) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Only while it is running, and never a session-wide stop: this ends one delegate
                // and leaves the agent that sent it to carry on with what it hears back.
                if (item.running) {
                    TextButton(onClick = { onStop(item.subAgentId) }) {
                        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Stop", fontSize = 13.sp)
                    }
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Hide what it did" else "Show what it did",
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                // The rule is the nesting. Without it a sub-agent's tool cards read as the parent's,
                // one indent to the right for no stated reason. Drawn rather than laid out as a
                // sibling, because a full-height sibling needs the row's intrinsic height — and the
                // things nested in here (diffs, scrolling code) are not all willing to be asked.
                val rule = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                Column(
                    Modifier
                        .padding(start = 22.dp, end = 12.dp, bottom = 12.dp)
                        .drawBehind { drawRect(rule, size = Size(2.dp.toPx(), size.height)) }
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    item.task.prompt?.let { prompt ->
                        Text(
                            prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.items.isEmpty()) {
                        Text(
                            "Nothing back from it yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item.items.forEach { nested ->
                        TranscriptRow(
                            item = nested,
                            // No byline in here. The card's own header says who is talking, and
                            // stamping the harness's mark on a delegate's prose credits the wrong
                            // agent for it.
                            harness = null,
                            onOpenArtifact = onOpenArtifact,
                            onRetry = onRetry,
                            onStopSubAgent = onStop,
                            onPermissionDecision = onPermissionDecision,
                            onReviewPermission = onReviewPermission,
                            answers = answers,
                        )
                    }
                }
            }
        }
    }
}

/** One line saying where the delegate has got to, in the terms the person cares about. */
private fun subAgentSubtitle(item: TranscriptItem.SubAgent): String {
    val kind = item.task.agentType
    val state = when (val outcome = item.outcome) {
        null -> item.latest ?: "Working…"
        is ToolOutcome.Success -> outcome.summary ?: "Finished"
        is ToolOutcome.Failure -> outcome.message
        ToolOutcome.Cancelled -> "You stopped this"
        ToolOutcome.Denied -> "You denied this"
    }
    return if (kind == null) state else "$kind · $state"
}

private fun toolIcon(call: ToolCall): ImageVector = when (call) {
    is ToolCall.Shell -> Icons.Outlined.Terminal
    is ToolCall.ReadFile -> Icons.Outlined.Description
    is ToolCall.EditFile, is ToolCall.WriteFile -> Icons.Outlined.Description
    is ToolCall.Search -> Icons.Outlined.Search
    is ToolCall.Fetch -> Icons.Outlined.Language
    // Only reachable for a Task whose start event was folded as a plain tool call; the sub-agent
    // card is what normally draws one.
    is ToolCall.Task -> Icons.Outlined.AccountTree
    is ToolCall.Generic -> Icons.Outlined.Terminal
}

@Composable
private fun toolSubtitle(item: TranscriptItem.Tool): String? = when (val outcome = item.outcome) {
    null -> when (val call = item.call) {
        is ToolCall.Shell -> "Running in ${call.workingDirectory}"
        else -> "Running…"
    }
    is ToolOutcome.Success -> outcome.summary
    is ToolOutcome.Failure -> outcome.message
    ToolOutcome.Denied -> "You denied this"
    ToolOutcome.Cancelled -> "Cancelled"
}

@Composable
private fun DiffCard(item: TranscriptItem.Diff, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        DiffView(
            diff = item.diff,
            modifier = Modifier.fillMaxWidth(),
            maxLines = if (expanded) null else 14,
        )
        val total = item.diff.hunks.sumOf { it.lines.size }
        if (total > 14) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show whole diff")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------

@Composable
private fun ChecklistCard(item: TranscriptItem.Checklist, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            item.items.forEachIndexed { index, task ->
                if (index > 0) Spacer(Modifier.height(11.dp))
                ChecklistRow(task)
            }
        }
    }
}

@Composable
private fun ChecklistRow(task: TaskItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when (task.state) {
                TaskState.Done -> Icon(
                    Icons.Outlined.Check,
                    contentDescription = "done",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                TaskState.Running -> CircularProgressIndicator(
                    Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )

                TaskState.Failed -> Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = "failed",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )

                TaskState.Skipped -> Icon(
                    Icons.Outlined.Close,
                    contentDescription = "skipped",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TaskState.Pending -> Icon(
                    Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = "pending",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            task.text,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 15.sp,
            color = when (task.state) {
                TaskState.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
                TaskState.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Permission, artifacts, errors
// ---------------------------------------------------------------------------

/**
 * A permission moment in the transcript: a one-line record once it is answered, and a card you can
 * answer *from* while it is not.
 *
 * The inline answer is not a shortcut for the sheet — it is what makes several of these possible at
 * once. A turn that asks for two commands blocks on both, and one modal can only ever be about one
 * of them; the second sat in the transcript as a line of text saying it was waiting, with nothing
 * anywhere that could answer it. Each card now carries its own decision, so they can be answered in
 * any order, and a permission's row opens the sheet for whoever wants the full diff first. A
 * question's does not: all of it is already on the card.
 */
@Composable
private fun PermissionRecord(
    item: TranscriptItem.Permission,
    onDecision: (String, PermissionDecision) -> Unit,
    onReview: (String) -> Unit,
    answers: AnswerStore,
    modifier: Modifier = Modifier,
) {
    val decision = item.decision
    if (decision == null) {
        PendingPermissionCard(item, onDecision, onReview, answers, modifier)
        return
    }
    val (label, tint) = when (decision) {
        PermissionDecision.Allow -> "You allowed this" to MaterialTheme.colorScheme.primary
        is PermissionDecision.AllowAlways ->
            "You allowed ${decision.scope}" to MaterialTheme.colorScheme.primary
        // One question gets its answer said back, because that is the whole record of it: the
        // headline is what was asked and this is what you told it. Several do not fit on a line,
        // and the answer went to the agent rather than into the transcript's furniture.
        is PermissionDecision.Answered -> {
            val only = decision.answers.values.singleOrNull()
            val said = if (only != null) "You chose $only" else "You answered"
            said to MaterialTheme.colorScheme.primary
        }
        PermissionDecision.Deny -> "You denied this" to MaterialTheme.colorScheme.error
        PermissionDecision.Abandoned -> "Left unanswered" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Lock, null, Modifier.size(15.dp), tint = tint)
        Spacer(Modifier.width(9.dp))
        Text(
            "${item.ask.headline} · $label",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PendingPermissionCard(
    item: TranscriptItem.Permission,
    onDecision: (String, PermissionDecision) -> Unit,
    onReview: (String) -> Unit,
    answers: AnswerStore,
    modifier: Modifier = Modifier,
) {
    val question = item.ask as? PermissionAsk.Questions
    val tint = MaterialTheme.colorScheme.tertiary
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tint.copy(alpha = 0.09f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp)) {
            Row(
                // A question has nowhere further to go — all of it is already here — so the row is
                // not a way in to anything. Only a permission keeps more of itself in the sheet.
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (question == null) Modifier.clickable { onReview(item.requestId) } else Modifier,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (question != null) Icons.Outlined.HelpOutline else Icons.Outlined.Lock,
                    null,
                    Modifier.size(16.dp),
                    tint = tint,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.ask.headline,
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    askOneLiner(item.ask)?.let { detail ->
                        Text(
                            detail,
                            fontFamily = if (item.ask is PermissionAsk.RunCommand) {
                                FontFamily.Monospace
                            } else {
                                FontFamily.Default
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // A command gets room to be read whole. This card is not a summary of
                            // the decision -- it *is* the decision, since the sheet no longer opens
                            // over it -- and "allow this command" with the command ellipsized is
                            // not a question anyone can answer. Still bounded, so one pathological
                            // line cannot take the pane.
                            maxLines = if (item.ask is PermissionAsk.RunCommand) 6 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (question == null) RowChevron()
            }
            /*
             * The diff, in the card, before anything is decided.
             *
             * The promise is that nothing is edited without the change being put in front of the
             * person first, and the sheet used to keep it by arriving uninvited. It no longer
             * does, so the card keeps it instead -- the same renderer, capped at the same fourteen
             * lines the transcript's own diff card shows, with the rest a tap away on the card.
             * That is most real edits whole and enough of a large one to recognise; "+N more
             * lines" says when it is not.
             */
            (item.ask as? PermissionAsk.EditFile)?.let { edit ->
                DiffView(
                    diff = edit.diff,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                    maxLines = 14,
                )
            }
            /*
             * The whole question, answerable where it was asked.
             *
             * A question stops the work either way — that is what it is for — so the card is
             * already the interruption, and a modal over it interrupts the same person twice about
             * the same thing. Options, descriptions, the multi-select rule and the free-text answer
             * all live here; there is nothing behind a tap.
             *
             * The ticks are held above the list, because this row is inside a `LazyColumn` and
             * would lose them the moment someone scrolled up to re-read the paragraph the question
             * is about. See [AnswerStore].
             */
            question?.let { asked ->
                val form = answers.of(item.requestId)
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    asked.questions.forEachIndexed { index, one ->
                        QuestionBlock(
                            question = one,
                            answers = form,
                            // The headline is already a lone question, word for word, so drawing
                            // it again as the first line would ask it twice an inch apart.
                            showText = asked.questions.size > 1,
                            last = index == asked.questions.lastIndex,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (question != null) {
                    val form = answers.of(item.requestId)
                    val answered = form.answeredCount(question.questions)
                    if (question.questions.size > 1 && !form.complete(question.questions)) {
                        Text(
                            "$answered of ${question.questions.size} answered",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // A denial, not a dismissal, and the difference is the point: this tells the
                    // agent now that no answer is coming, where walking away leaves it waiting on
                    // one that might still arrive. Neither invents a preference.
                    TextButton(onClick = { onDecision(item.requestId, PermissionDecision.Deny) }) {
                        Text("Rather not say", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(2.dp))
                    Button(
                        onClick = {
                            onDecision(
                                item.requestId,
                                PermissionDecision.Answered(form.collected(question.questions)),
                            )
                        },
                        // Off until every question has something in it. A live Answer that sent a
                        // blank map would hand the agent the same non-answer it used to invent.
                        enabled = form.complete(question.questions),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                    ) {
                        Text("Answer", fontSize = 13.sp)
                    }
                    return@Row
                }
                // Widening the rule is a decision about the future, so it stays a quiet text
                // button even here, where the other two are the obvious things to press.
                item.ask.alwaysAllowScope?.let { scope ->
                    TextButton(
                        onClick = { onDecision(item.requestId, PermissionDecision.AllowAlways(scope)) },
                    ) {
                        Text("Always", fontSize = 13.sp)
                    }
                }
                TextButton(onClick = { onDecision(item.requestId, PermissionDecision.Deny) }) {
                    Text("Deny", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(2.dp))
                Button(
                    onClick = { onDecision(item.requestId, PermissionDecision.Allow) },
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    Text("Allow", fontSize = 13.sp)
                }
            }
        }
    }
}

/** Enough of a permission to answer a familiar one without opening the sheet for the whole story. */
private fun askOneLiner(ask: PermissionAsk): String? = when (ask) {
    is PermissionAsk.RunCommand -> ask.command
    // Nothing: the diff drawn underneath carries the path and the counts in its own header, and
    // saying it here as well is the file named twice, an inch apart.
    is PermissionAsk.EditFile -> null
    is PermissionAsk.NetworkAccess -> ask.purpose ?: ask.host
    // Nothing: the options are drawn underneath in full, with what each one means. This used to
    // list their labels, back when the card was a trailer for a sheet.
    is PermissionAsk.Questions -> null
    is PermissionAsk.Generic -> ask.description
}

@Composable
private fun ArtifactRow(
    item: TranscriptItem.Artifacts,
    onOpen: (Artifact) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item.artifacts.forEach { artifact ->
            OutlinedButton(
                onClick = { onOpen(artifact) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    when (artifact) {
                        Artifact.Computer -> Icons.Outlined.Computer
                        is Artifact.Preview -> Icons.Outlined.Language
                        is Artifact.Document ->
                            if (artifact.mimeType.startsWith("image/")) {
                                Icons.Outlined.Image
                            } else {
                                Icons.Outlined.Description
                            }
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    when (artifact) {
                        Artifact.Computer -> "Open computer"
                        is Artifact.Preview -> "Open preview"
                        // Named, unlike the other two: there is only ever one computer and one
                        // preview to open, and there can be any number of documents.
                        is Artifact.Document -> "Open ${artifact.name}"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(
    item: TranscriptItem.Error,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(19.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(item.message, style = MaterialTheme.typography.titleMedium, fontSize = 15.sp)
                item.detail?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (item.recoverable) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text("Reconnect")
                    }
                }
            }
        }
    }
}

@Composable
private fun EndedRow(item: TranscriptItem.Ended, modifier: Modifier = Modifier) {
    val text = when (val outcome = item.outcome) {
        is SessionOutcome.Completed -> outcome.summary?.let { "Task finished · $it" } ?: "Task finished"
        is SessionOutcome.Failed -> "Task failed · ${outcome.message}"
        SessionOutcome.Interrupted -> "Task stopped"
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        )
        Text(
            text,
            Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // One line, hard. This is a rule across the conversation, and a summary is a caption
            // on it — never a second copy of the answer. Box's own harness sends none at all now,
            // for exactly that reason: it used to pass the SDK's `result`, which *is* the final
            // message, and printed the agent's whole reply again underneath itself in small grey
            // type. A harness that says something short still gets to; one that says too much is
            // cut here rather than allowed to become a second transcript.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        )
    }
}

// ---------------------------------------------------------------------------
// Live activity
// ---------------------------------------------------------------------------

/**
 * The "agent is doing something" line that trails the transcript.
 *
 * [waitingOn] is the ask the agent has stopped for, where it has stopped for one. Only the kind of
 * it matters here: an agent parked on a question is not waiting for approval, and saying so put a
 * word in the user's mouth — "approve" — for a card whose buttons read Answer and Rather not say.
 */
@Composable
fun ActivityRow(
    activity: AgentActivity,
    modifier: Modifier = Modifier,
    waitingOn: PermissionAsk? = null,
) {
    val label = when (activity) {
        is AgentActivity.Thinking -> activity.label ?: "Thinking"
        is AgentActivity.Working -> activity.label
        is AgentActivity.AwaitingPermission ->
            if (waitingOn is PermissionAsk.Questions) "Waiting for your answer" else "Waiting for your approval"
        AgentActivity.AwaitingInput -> "Waiting for your reply"
        AgentActivity.Idle, AgentActivity.Ended -> return
    }
    val transition = rememberInfiniteTransition(label = "activity")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(MaterialTheme.colorScheme.primary.copy(alpha = pulse), 8.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "$label…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Chevron affordance shared by list rows. */
@Composable
fun RowChevron(modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        modifier = modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** True when a background colour is dark enough to want light ink on top. */
fun Color.luminanceIsDark(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f
