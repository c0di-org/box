package dev.localagent.workstation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.CommandRecord
import dev.localagent.workstation.OpenedFile
import java.util.Locale

/**
 * The escape hatch. Not a destination any more — you reach it through the computer, when the
 * agent has done something you want to poke at yourself.
 */
@Composable
fun TerminalTool(
    state: BoxUiState,
    onOpenBox: () -> Unit,
    onRunCommand: (String) -> Unit,
) {
    if (state.runtimeState != RuntimeState.Ready) {
        RuntimeGate(
            destination = "the terminal",
            state = state.runtimeState,
            onOpenBox = onOpenBox,
        )
        return
    }

    var draft by rememberSaveable { mutableStateOf("") }
    fun submit() {
        val command = draft.trim()
        if (command.isEmpty() || state.runningCommand != null) return
        onRunCommand(command)
        draft = ""
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.commandHistory.size, state.runningCommand) {
        val extra = if (state.runningCommand != null) 1 else 0
        if (state.commandHistory.isNotEmpty() || extra > 0) {
            listState.animateScrollToItem(state.commandHistory.size + extra)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 1100.dp).weight(1f),
            color = BoxTerminal,
            shape = RoundedCornerShape(22.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    Column {
                        Text(
                            "BOX / DEBIAN ARM64",
                            color = BoxGreenLight,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Commands run in ${state.currentPath} using the guest shell.",
                            color = CodeColors.muted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (state.commandHistory.isEmpty() && state.runningCommand == null) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf("pwd", "ls -la", "uname -a")) { command ->
                                Surface(
                                    onClick = { onRunCommand(command) },
                                    color = Color(0xFF1B211D),
                                    contentColor = Color(0xFFD5DDD7),
                                    shape = CircleShape,
                                    border = BorderStroke(1.dp, Color(0xFF344038)),
                                ) {
                                    Text(
                                        command,
                                        Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
                items(state.commandHistory, key = { it.id }) { record ->
                    CommandOutput(record)
                }
                state.runningCommand?.let { command ->
                    item(key = "running") {
                        Column {
                            Text("$ $command", color = BoxGreenLight, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = BoxGreenLight)
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    "Running…",
                                    color = CodeColors.muted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(2.dp)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 1100.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.replace('\n', ' ') },
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    enabled = state.runningCommand == null,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    "Run a command",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
                IconButton(onClick = ::submit, enabled = draft.isNotBlank() && state.runningCommand == null) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Run command")
                }
            }
        }
    }
}

@Composable
private fun CommandOutput(record: CommandRecord) {
    Column {
        SelectionContainer {
            Column {
                Text("$ ${record.command}", color = BoxGreenLight, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                if (record.stdout.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        record.stdout.trimEnd(),
                        color = CodeColors.plain,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
                if (record.stderr.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        record.stderr.trimEnd(),
                        color = Color(0xFFFFB4AB),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
            }
        }
        if (record.exitCode != 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "exit ${record.exitCode}",
                color = Color(0xFFFFB4AB),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
fun FilesTool(
    state: BoxUiState,
    onOpenBox: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onCloseFile: () -> Unit,
) {
    if (state.runtimeState != RuntimeState.Ready) {
        RuntimeGate(
            destination = "files",
            state = state.runtimeState,
            onOpenBox = onOpenBox,
        )
        return
    }

    state.openedFile?.let { file ->
        FilePreview(file, onCloseFile)
        return
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().widthIn(max = 1000.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateUp, enabled = state.currentPath != "/workspace") {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Up one folder")
            }
            WorkspaceBreadcrumb(state.currentPath, onOpenDirectory, Modifier.weight(1f))
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh files")
            }
        }
        Spacer(Modifier.height(6.dp))
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 1000.dp).weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        ) {
            if (state.filesLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val sorted = remember(state.files) {
                state.files.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            }
            if (!state.filesLoading && sorted.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.padding(18.dp).size(30.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("This folder is empty", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Anything an agent creates in here shows up as it works.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(sorted, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            opening = state.openingFilePath == entry.path,
                            onOpenDirectory = onOpenDirectory,
                            onOpenFile = onOpenFile,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceBreadcrumb(
    currentPath: String,
    onOpenDirectory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val relative = currentPath.removePrefix("/workspace").trim('/')
    val segments = if (relative.isBlank()) emptyList() else relative.split('/')
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpenDirectory("/workspace") }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("workspace", fontFamily = FontFamily.Monospace)
        }
        var path = "/workspace"
        segments.forEach { segment ->
            path += "/$segment"
            val target = path
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onOpenDirectory(target) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(segment, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    opening: Boolean,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (entry.isDirectory) onOpenDirectory(entry.path) else onOpenFile(entry) }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(11.dp),
            color = if (entry.isDirectory) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (entry.isDirectory) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (entry.isDirectory) "Folder" else formatBytes(entry.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (opening) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FilePreview(file: OpenedFile, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().widthIn(max = 1100.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to files")
            }
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    file.path,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (file.truncated) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
                Text(
                    "Preview shortened to keep Box responsive.",
                    Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().widthIn(max = 1100.dp).weight(1f),
        ) {
            item {
                CodeBlock(
                    text = file.content,
                    modifier = Modifier.fillMaxWidth(),
                    language = dev.localagent.workstation.agent.CodeLanguage.forPath(file.path),
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1_024 && unit < units.lastIndex) {
        value /= 1_024
        unit++
    }
    return String.format(Locale.US, if (value >= 10) "%.0f %s" else "%.1f %s", value, units[unit])
}
