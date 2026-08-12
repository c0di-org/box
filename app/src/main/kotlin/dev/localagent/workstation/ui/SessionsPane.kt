package dev.localagent.workstation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.workstation.BoxProgress
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.agent.SessionStatus
import dev.localagent.workstation.agent.SessionSummary
import dev.localagent.workstation.computer.DesktopTransport

/**
 * Home: your box, and everything being done inside it.
 *
 * The box is the first thing on this surface at every size — filling it while closed, a row at the
 * top once open. Below that is one flat list of tasks, newest first. There is no harness level any
 * more: the user has one box and many tasks, and putting "Claude Code" in between said they had
 * several agents and, apparently, no computer.
 */
@Composable
fun SessionsPane(
    state: BoxUiState,
    progress: BoxProgress,
    desktop: DesktopTransport?,
    onSelectSession: (String) -> Unit,
    onNewConversation: (String) -> Unit,
    onOpenBox: () -> Unit,
    onOpenComputer: () -> Unit,
    onSendFirstTask: (String) -> Unit,
    onDismissGreeting: () -> Unit,
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier,
    showSelection: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Two heights for one panel, and the tasks take whatever is left. Animating the height
        // rather than swapping screens is what makes this read as the box moving rather than the
        // app navigating.
        //
        // When the box gets the window and when it becomes a row is [BoxUiState.boxOwnsWindow].
        val full = state.boxOwnsWindow
        val panelHeight by animateDpAsState(
            targetValue = if (full) maxHeight else HERO_SETTLED_HEIGHT,
            animationSpec = tween(SETTLE_MILLIS),
            label = "box panel",
        )

        Column(Modifier.fillMaxSize()) {
            YourBox(
                state = state,
                progress = progress,
                desktop = desktop,
                full = full,
                onOpen = onOpenBox,
                onOpenComputer = onOpenComputer,
                onOpenChat = {
                    state.sessions.firstOrNull()?.let { onSelectSession(it.id) }
                        ?: state.harnesses.firstOrNull()?.let { onNewConversation(it.id) }
                },
                onSendFirstTask = onSendFirstTask,
                onDismissGreeting = onDismissGreeting,
                onShowDetails = onShowDetails,
                modifier = Modifier.fillMaxWidth().height(panelHeight).clipToBounds(),
            )
            // Zero-height until the panel has shrunk out of the way, so the tasks slide up into
            // view rather than appearing on top of a screen they were never part of.
            TaskList(
                state = state,
                onSelectSession = onSelectSession,
                onNewConversation = onNewConversation,
                showSelection = showSelection,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            AnimatedVisibility(
                visible = !full,
                enter = fadeIn(tween(SETTLE_MILLIS)),
                exit = fadeOut(tween(SETTLE_MILLIS / 2)),
            ) {
                NewConversationBar(state = state, onNewConversation = onNewConversation)
            }
        }
    }
}

@Composable
private fun TaskList(
    state: BoxUiState,
    onSelectSession: (String) -> Unit,
    onNewConversation: (String) -> Unit,
    showSelection: Boolean,
    modifier: Modifier = Modifier,
) {
    val tasks = state.tasks
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        item(key = "tasks-heading") { SectionHeading("Tasks") }
        if (tasks.isEmpty()) {
            item(key = "tasks-empty") {
                NoTasksYet(
                    enabled = state.harnesses.isNotEmpty() && !state.startingSession,
                    onNew = { state.harnesses.firstOrNull()?.let { onNewConversation(it.id) } },
                )
            }
        }
        items(tasks, key = { it.id }) { task ->
            TaskRow(
                task = task,
                harnessName = state.harnessOf(task)?.name,
                selected = showSelection && task.id == state.selectedSessionId,
                onClick = { onSelectSession(task.id) },
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        fontSize = 11.sp,
        letterSpacing = 1.1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TaskRow(
    task: SessionSummary,
    harnessName: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(12.dp),
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        } else {
            null
        },
    ) {
        Row(
            Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Which agent is running this is a detail of the task, not a place to put it.
                    harnessName?.let { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = harnessAccent(task.harnessId),
                        )
                        Text(
                            " · ",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        task.preview ?: task.workingDirectory,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            SessionStatusLabel(task.status)
        }
    }
}

/**
 * A task's state, as one dot.
 *
 * Colour carries all of it, because the words never earned their room: three rows of "Active",
 * "Needs you", "Finished" is a column of labels the eye has to read to find the one row that wants
 * something, when a colour is found without reading. Green is working, amber wants you, red broke.
 * Finished and idle draw nothing at all — a task that needs nothing should be quiet.
 *
 * The words are still there for anyone listening rather than looking.
 */
@Composable
private fun SessionStatusLabel(status: SessionStatus) {
    val (color, spoken) = when (status) {
        SessionStatus.Active -> MaterialTheme.colorScheme.primary to "Active"
        is SessionStatus.NeedsYou -> MaterialTheme.colorScheme.tertiary to "Needs you"
        is SessionStatus.Failed -> MaterialTheme.colorScheme.error to "Failed"
        SessionStatus.Finished -> null to "Finished"
        SessionStatus.Idle -> null to "Idle"
    }
    Box(
        Modifier.size(20.dp).semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        if (color != null) StatusDot(color, 9.dp)
    }
}

@Composable
private fun NoTasksYet(enabled: Boolean, onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 22.dp)) {
        Text("Nothing running yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onNew,
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Start a task")
        }
    }
}

/** "New task" plus a harness picker, for when the user does care which agent takes it. */
@Composable
private fun NewConversationBar(
    state: BoxUiState,
    onNewConversation: (String) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val defaultHarness = state.harnesses.firstOrNull()

    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { defaultHarness?.let { onNewConversation(it.id) } },
                enabled = defaultHarness != null && !state.startingSession,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (state.startingSession) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("New task")
            }
            Box {
                OutlinedButton(
                    onClick = { pickerOpen = true },
                    enabled = state.harnesses.isNotEmpty(),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(
                        Icons.Outlined.Apps,
                        contentDescription = "Choose an agent",
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    state.harnesses.forEach { harness ->
                        DropdownMenuItem(
                            text = { Text(harness.name) },
                            leadingIcon = { HarnessMark(harness, 24.dp) },
                            onClick = {
                                pickerOpen = false
                                onNewConversation(harness.id)
                            },
                        )
                    }
                }
            }
        }
    }
}
