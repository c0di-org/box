package dev.localagent.workstation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.ComputerTool
import dev.localagent.workstation.computer.ControlHolder

/**
 * The agent's computer. Demoted from the app's home screen to one of two destinations, because
 * the VM is what makes the product possible, not what the product is.
 */
@Composable
fun ComputerPane(
    state: BoxUiState,
    onBack: (() -> Unit)?,
    onSelectTool: (ComputerTool) -> Unit,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onRunCommand: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (dev.localagent.runtime.api.FileEntry) -> Unit,
    onCloseFile: () -> Unit,
    onTakeControl: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier.fillMaxSize()) {
        ComputerHeader(
            state = state,
            onBack = onBack,
            onShowDiagnostics = onShowDiagnostics,
            onTakeControl = onTakeControl,
            compact = compact,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ToolSwitcher(state.computerTool, onSelectTool)
        Box(Modifier.weight(1f)) {
            when (state.computerTool) {
                ComputerTool.Overview -> ComputerOverview(
                    state = state,
                    onSetupAndStart = onSetupAndStart,
                    onStart = onStart,
                    onStop = onStop,
                    onRetry = onRetry,
                    onOpenTerminal = { onSelectTool(ComputerTool.Terminal) },
                )

                ComputerTool.Terminal -> TerminalTool(
                    state = state,
                    onSetupAndStart = onSetupAndStart,
                    onStart = onStart,
                    onRetry = onRetry,
                    onRunCommand = onRunCommand,
                )

                ComputerTool.Files -> FilesTool(
                    state = state,
                    onSetupAndStart = onSetupAndStart,
                    onStart = onStart,
                    onRetry = onRetry,
                    onOpenDirectory = onOpenDirectory,
                    onNavigateUp = onNavigateUp,
                    onRefresh = onRefreshFiles,
                    onOpenFile = onOpenFile,
                    onCloseFile = onCloseFile,
                )
            }
        }
    }
}

@Composable
private fun ComputerHeader(
    state: BoxUiState,
    onBack: (() -> Unit)?,
    onShowDiagnostics: () -> Unit,
    onTakeControl: () -> Unit,
    compact: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back to conversations",
                )
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.Outlined.Computer,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Agent's Computer",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (!compact) {
            ControlChip(ControlHolder.Agent, state.computerReady)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onTakeControl,
                enabled = state.computerReady,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Take over")
            }
            Spacer(Modifier.width(4.dp))
        } else {
            StatusPill(state.runtimeState)
        }
        IconButton(onClick = onShowDiagnostics) {
            Icon(Icons.Outlined.Info, contentDescription = "Computer details")
        }
    }
}

@Composable
private fun ControlChip(holder: ControlHolder, live: Boolean) {
    val color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (holder) {
                    ControlHolder.Agent -> "Agent has control"
                    ControlHolder.User -> "You have control"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            StatusDot(color, 8.dp)
        }
    }
}

@Composable
private fun ToolSwitcher(selected: ComputerTool, onSelect: (ComputerTool) -> Unit) {
    val tools = listOf(
        Triple(ComputerTool.Overview, "Desktop", Icons.Outlined.Computer),
        Triple(ComputerTool.Terminal, "Terminal", Icons.Outlined.Terminal),
        Triple(ComputerTool.Files, "Files", Icons.Outlined.FolderOpen),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tools.forEach { (tool, label, icon) ->
            val active = tool == selected
            Surface(
                onClick = { onSelect(tool) },
                shape = CircleShape,
                color = if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    Color.Transparent
                },
                contentColor = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                border = BorderStroke(
                    1.dp,
                    if (active) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                    },
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ComputerOverview(
    state: BoxUiState,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            DesktopSlot(
                state = state.runtimeState,
                modifier = Modifier.fillMaxWidth().widthIn(max = 920.dp),
            )
        }
        item {
            RuntimeStatusCard(
                state = state.runtimeState,
                modifier = Modifier.fillMaxWidth().widthIn(max = 920.dp),
                onSetupAndStart = onSetupAndStart,
                onStart = onStart,
                onStop = onStop,
                onRetry = onRetry,
                onOpenTerminal = onOpenTerminal,
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().widthIn(max = 920.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FactCard(
                    icon = Icons.Outlined.Lock,
                    title = "Private by design",
                    body = "The control channel stays on-device. No management port is exposed.",
                    modifier = Modifier.weight(1f),
                )
                FactCard(
                    icon = Icons.Outlined.Storage,
                    title = "Work that persists",
                    body = "Projects live in /workspace on a dedicated virtual disk.",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 920.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Memory, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Debian • ARM64", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "2 virtual CPUs • 1 GB memory • QEMU TCG",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Where the live desktop goes.
 *
 * Left inert on purpose: streaming the guest display needs a transport that does not exist yet
 * (see [dev.localagent.workstation.computer.DesktopTransport]). The slot keeps its real
 * proportions so the three-pane layout is laid out against the thing that will fill it, not
 * against a stand-in that shrinks when the real pane arrives.
 */
@Composable
fun DesktopSlot(
    state: RuntimeState,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = BoxTerminal,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f),
            contentAlignment = Alignment.Center,
        ) {
            if (content != null) {
                content()
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        Icons.Outlined.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = CodeColors.muted,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        when (state) {
                            RuntimeState.Ready -> "The desktop isn't streaming yet"
                            else -> "The computer isn't running"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = CodeColors.plain,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (state) {
                            RuntimeState.Ready ->
                                "Box can run commands and read files here today. The live screen " +
                                    "needs a display transport that's still being built."
                            else -> "Start the computer to give agents somewhere to work."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = CodeColors.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 380.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeStatusCard(
    state: RuntimeState,
    modifier: Modifier,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val presentation = statePresentation(state)
    val active = state == RuntimeState.Starting || state == RuntimeState.Connecting ||
        state == RuntimeState.Stopping || state is RuntimeState.Provisioning
    Surface(
        modifier = modifier,
        color = when {
            state is RuntimeState.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            state == RuntimeState.Ready -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        },
        shape = RoundedCornerShape(24.dp),
    ) {
        Box {
            Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                RuntimeGlyph(state, Modifier.size(64.dp))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        presentation.eyebrow,
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 11.sp,
                        color = presentation.color,
                        letterSpacing = 1.1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        presentation.title.replace("\n", " "),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            presentation.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (state) {
                            RuntimeState.NotProvisioned -> Button(onClick = onSetupAndStart) { Text("Set up") }
                            RuntimeState.Stopped -> Button(onClick = onStart) { Text("Start") }
                            RuntimeState.Suspended -> Button(onClick = onStart) { Text("Resume") }
                            RuntimeState.Starting, RuntimeState.Connecting, RuntimeState.Suspending ->
                                TextButton(onClick = onStop) { Text("Stop startup") }
                            RuntimeState.Stopping -> Unit
                            RuntimeState.Ready -> {
                                Button(onClick = onOpenTerminal) { Text("Open terminal") }
                                OutlinedButton(onClick = onStop) { Text("Stop") }
                            }
                            is RuntimeState.Failed -> Button(onClick = onRetry) { Text("Try again") }
                            is RuntimeState.Provisioning -> Unit
                        }
                    }
                }
            }
            if (active) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }
        }
    }
}

@Composable
private fun FactCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Inert preview affordance. Port forwarding throws today, so this only explains itself. */
@Composable
fun PreviewSlot(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Language, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Preview", style = MaterialTheme.typography.titleMedium, fontSize = 15.sp)
                Text(
                    "Needs port forwarding from the guest.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}
