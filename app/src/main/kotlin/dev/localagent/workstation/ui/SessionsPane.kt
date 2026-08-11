package dev.localagent.workstation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forum
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.HarnessGroup
import dev.localagent.workstation.agent.SessionStatus
import dev.localagent.workstation.agent.SessionSummary

/**
 * The harness wrangler. One list, grouped by harness, showing every session and whether it is
 * running, blocked on the user, or done — because the whole premise is that several agents are
 * working at once and the user needs to know which one wants something.
 */
@Composable
fun SessionsPane(
    state: BoxUiState,
    onSelectSession: (String) -> Unit,
    onToggleHarness: (String) -> Unit,
    onNewConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    showSelection: Boolean = true,
) {
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (state.harnesses.isEmpty()) {
                item { HarnessesEmptyState() }
            }
            state.groups.forEach { group ->
                val collapsed = group.harness.id in state.collapsedHarnesses
                item(key = "h-${group.harness.id}") {
                    HarnessHeader(
                        group = group,
                        collapsed = collapsed,
                        onToggle = { onToggleHarness(group.harness.id) },
                    )
                }
                if (!collapsed) {
                    if (group.sessions.isEmpty()) {
                        item(key = "empty-${group.harness.id}") {
                            EmptyHarnessRow(group) { onNewConversation(group.harness.id) }
                        }
                    }
                    items(group.sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            selected = showSelection && session.id == state.selectedSessionId,
                            onClick = { onSelectSession(session.id) },
                        )
                    }
                }
                item(key = "gap-${group.harness.id}") { Spacer(Modifier.height(14.dp)) }
            }
        }
        NewConversationBar(
            state = state,
            onNewConversation = onNewConversation,
        )
    }
}

@Composable
private fun HarnessHeader(
    group: HarnessGroup,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HarnessMark(group.harness, 30.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            group.harness.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (collapsed && group.activeCount > 0) {
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), shape = CircleShape) {
                Text(
                    "${group.activeCount} active",
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            contentDescription = if (collapsed) "Show ${group.harness.name} sessions" else "Hide ${group.harness.name} sessions",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val status = session.status
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
                    session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                session.preview?.let { preview ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            SessionStatusLabel(status)
        }
    }
}

@Composable
private fun SessionStatusLabel(status: SessionStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            SessionStatus.Active -> {
                StatusDot(MaterialTheme.colorScheme.primary, 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Active",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            is SessionStatus.NeedsYou -> {
                StatusDot(MaterialTheme.colorScheme.tertiary, 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Needs you",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            SessionStatus.Finished -> {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Finished",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is SessionStatus.Failed -> {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Failed",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SessionStatus.Idle -> Text(
                "Idle",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyHarnessRow(group: HarnessGroup, onNew: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onNew)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Start something with ${group.harness.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HarnessesEmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Forum,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text("No agents yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Box runs coding agents inside its own computer. Claude Code comes with it, so this " +
                "screen usually means Box is still setting itself up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "New conversation" plus a harness picker, mirroring the mockup's footer. */
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
                Text("New conversation")
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
