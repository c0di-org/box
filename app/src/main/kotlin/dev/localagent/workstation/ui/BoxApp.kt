package dev.localagent.workstation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BoxDestination
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.BuildConfig
import dev.localagent.workstation.CommandRecord
import dev.localagent.workstation.OpenedFile
import java.util.Locale

private val destinations = listOf(
    BoxDestination.Home to Icons.Outlined.Home,
    BoxDestination.Terminal to Icons.Outlined.Terminal,
    BoxDestination.Files to Icons.Outlined.FolderOpen,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxApp(
    state: BoxUiState,
    onDestinationSelected: (BoxDestination) -> Unit,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onRunCommand: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onCloseFile: () -> Unit,
    onNoticeShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.message)
        onNoticeShown()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        val content: @Composable (PaddingValues) -> Unit = { padding ->
            BoxScreen(
                state = state,
                padding = padding,
                onShowDiagnostics = { showDiagnostics = true },
                onDestinationSelected = onDestinationSelected,
                onSetupAndStart = onSetupAndStart,
                onStart = onStart,
                onStop = onStop,
                onRetry = onRetry,
                onRunCommand = onRunCommand,
                onOpenDirectory = onOpenDirectory,
                onNavigateUp = onNavigateUp,
                onRefreshFiles = onRefreshFiles,
                onOpenFile = onOpenFile,
                onCloseFile = onCloseFile,
            )
        }

        if (useRail) {
            Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                BoxNavigationRail(state.destination, onDestinationSelected)
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight().width(1.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                )
                Scaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    content = content,
                )
            }
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    BoxNavigationBar(state.destination, onDestinationSelected)
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                content = content,
            )
        }
    }

    if (showDiagnostics) {
        DiagnosticsSheet(
            state = state.runtimeState,
            onDismiss = { showDiagnostics = false },
            onSetupAndStart = {
                showDiagnostics = false
                onSetupAndStart()
            },
            onStart = {
                showDiagnostics = false
                onStart()
            },
            onStop = {
                showDiagnostics = false
                onStop()
            },
            onRetry = {
                showDiagnostics = false
                onRetry()
            },
        )
    }
}

@Composable
private fun BoxScreen(
    state: BoxUiState,
    padding: PaddingValues,
    onShowDiagnostics: () -> Unit,
    onDestinationSelected: (BoxDestination) -> Unit,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onRunCommand: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onCloseFile: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .background(MaterialTheme.colorScheme.background),
    ) {
        BoxTopBar(state.runtimeState, onShowDiagnostics)
        when (state.destination) {
            BoxDestination.Home -> HomeScreen(
                state = state.runtimeState,
                onSetupAndStart = onSetupAndStart,
                onStart = onStart,
                onStop = onStop,
                onRetry = onRetry,
                onOpenTerminal = { onDestinationSelected(BoxDestination.Terminal) },
                onOpenFiles = { onDestinationSelected(BoxDestination.Files) },
            )

            BoxDestination.Terminal -> TerminalScreen(
                state = state,
                onSetupAndStart = onSetupAndStart,
                onStart = onStart,
                onRetry = onRetry,
                onRunCommand = onRunCommand,
            )

            BoxDestination.Files -> FilesScreen(
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

@Composable
private fun BoxTopBar(state: RuntimeState, onShowDiagnostics: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxMark(34.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "BOX",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.2.sp,
        )
        Spacer(Modifier.weight(1f))
        StatusPill(state)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onShowDiagnostics) {
            Icon(Icons.Outlined.Info, contentDescription = "Runtime details")
        }
    }
}

@Composable
private fun BoxMark(size: androidx.compose.ui.unit.Dp) {
    Canvas(
        Modifier
            .size(size)
            .semantics { contentDescription = "Box" },
    ) {
        drawRoundRect(color = BoxInk, cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val left = Offset(this.size.width * 0.26f, this.size.height * 0.36f)
        val top = Offset(this.size.width * 0.50f, this.size.height * 0.23f)
        val right = Offset(this.size.width * 0.74f, this.size.height * 0.36f)
        val center = Offset(this.size.width * 0.50f, this.size.height * 0.50f)
        val bottomLeft = Offset(this.size.width * 0.26f, this.size.height * 0.63f)
        val bottom = Offset(this.size.width * 0.50f, this.size.height * 0.77f)
        val bottomRight = Offset(this.size.width * 0.74f, this.size.height * 0.63f)
        val ink = BoxGreenLight
        drawLine(ink, left, top, strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(ink, top, right, strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(ink, left, center, strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(ink, right, center, strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(ink, center, bottom, strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(ink, left, bottomLeft, strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(ink, bottomLeft, bottom, strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(ink, right, bottomRight, strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(ink, bottomRight, bottom, strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

@Composable
private fun StatusPill(state: RuntimeState) {
    val presentation = statePresentation(state)
    Surface(
        color = presentation.color.copy(alpha = 0.13f),
        contentColor = presentation.color,
        shape = CircleShape,
        modifier = Modifier.semantics {
            contentDescription = "Runtime status: ${presentation.shortLabel}"
        },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(presentation.color))
            Spacer(Modifier.width(7.dp))
            Text(presentation.shortLabel, style = MaterialTheme.typography.labelLarge, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BoxNavigationBar(
    selected: BoxDestination,
    onSelected: (BoxDestination) -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            destinations.forEach { (destination, icon) ->
                NavigationBarItem(
                    selected = selected == destination,
                    onClick = { onSelected(destination) },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(destination.label) },
                )
            }
        }
    }
}

@Composable
private fun BoxNavigationRail(
    selected: BoxDestination,
    onSelected: (BoxDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight().padding(top = 76.dp),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        destinations.forEach { (destination, icon) ->
            NavigationRailItem(
                selected = selected == destination,
                onClick = { onSelected(destination) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(destination.label) },
                modifier = Modifier.padding(vertical = 5.dp),
            )
        }
    }
}

private val BoxDestination.label: String
    get() = when (this) {
        BoxDestination.Home -> "Home"
        BoxDestination.Terminal -> "Terminal"
        BoxDestination.Files -> "Files"
    }

private data class StatePresentation(
    val shortLabel: String,
    val eyebrow: String,
    val title: String,
    val body: String,
    val color: Color,
)

@Composable
private fun statePresentation(state: RuntimeState): StatePresentation = when (state) {
    RuntimeState.NotProvisioned -> StatePresentation(
        "Not set up",
        "LINUX, WITHOUT THE LAPTOP",
        "A real Linux box,\ninside your phone.",
        "One tap prepares a private Debian workspace for coding agents and command-line tools.",
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    is RuntimeState.Provisioning -> StatePresentation(
        "Setting up",
        "PREPARING YOUR WORKSPACE",
        "Setting up Box",
        "Verifying the Linux system and creating your private workspace. Keep Box open for this step.",
        MaterialTheme.colorScheme.primary,
    )
    RuntimeState.Stopped -> StatePresentation(
        "Off",
        "PRIVATE WORKSPACE",
        "Your Linux workspace\nis ready to wake.",
        "Start it when an agent needs a real computer. Your files stay in /workspace between sessions.",
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RuntimeState.Starting -> StatePresentation(
        "Booting",
        "STARTING THE VM",
        "Booting Debian",
        "Box boots a full ARM64 virtual machine. On this phone it can take about 2–3 minutes; you can leave the app while it starts.",
        MaterialTheme.colorScheme.primary,
    )
    RuntimeState.Connecting -> StatePresentation(
        "Connecting",
        "THE VM IS RUNNING",
        "Almost there",
        "Debian is up. Box is waiting for its private control channel so commands and files are safe to use.",
        MaterialTheme.colorScheme.primary,
    )
    RuntimeState.Ready -> StatePresentation(
        "Ready",
        "YOUR PRIVATE COMPUTER",
        "Your Box is ready.",
        "Run real Linux commands or work directly with the persistent /workspace volume.",
        MaterialTheme.colorScheme.primary,
    )
    RuntimeState.Stopping -> StatePresentation(
        "Stopping",
        "SHUTTING DOWN SAFELY",
        "Stopping Box",
        "The virtual machine is closing. Your persistent workspace stays safely stored on this phone.",
        MaterialTheme.colorScheme.tertiary,
    )
    RuntimeState.Suspending -> StatePresentation(
        "Pausing",
        "SAVING RUNTIME STATE",
        "Pausing Box",
        "Your workspace remains safely stored on this phone.",
        MaterialTheme.colorScheme.tertiary,
    )
    RuntimeState.Suspended -> StatePresentation(
        "Paused",
        "WORKSPACE SAVED",
        "Box is paused.",
        "Resume when you need the Linux workspace again.",
        MaterialTheme.colorScheme.tertiary,
    )
    is RuntimeState.Failed -> StatePresentation(
        "Needs attention",
        "STARTUP DIDN’T FINISH",
        "Box couldn’t start.",
        state.reason.message.ifBlank { "The Linux runtime stopped before it became ready." },
        MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun HomeScreen(
    state: RuntimeState,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Box(Modifier.fillMaxWidth().widthIn(max = 920.dp)) {
                RuntimeHero(
                    state = state,
                    onSetupAndStart = onSetupAndStart,
                    onStart = onStart,
                    onStop = onStop,
                    onRetry = onRetry,
                    onOpenTerminal = onOpenTerminal,
                    onOpenFiles = onOpenFiles,
                )
            }
        }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 920.dp)) {
                if (maxWidth >= 680.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        FeatureCard(
                            icon = { Icon(Icons.Outlined.Lock, null) },
                            title = "Private by design",
                            body = "The control channel stays on-device. No management port is exposed to your network.",
                            modifier = Modifier.weight(1f),
                        )
                        FeatureCard(
                            icon = { Icon(Icons.Outlined.Storage, null) },
                            title = "Work that persists",
                            body = "Projects live in /workspace on a dedicated virtual disk, ready for the next session.",
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FeatureCard(
                            icon = { Icon(Icons.Outlined.Lock, null) },
                            title = "Private by design",
                            body = "The control channel stays on-device. No management port is exposed to your network.",
                        )
                        FeatureCard(
                            icon = { Icon(Icons.Outlined.Storage, null) },
                            title = "Work that persists",
                            body = "Projects live in /workspace on a dedicated virtual disk, ready for the next session.",
                        )
                    }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 920.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                shape = RoundedCornerShape(22.dp),
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

@Composable
private fun RuntimeHero(
    state: RuntimeState,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val presentation = statePresentation(state)
    val active = state == RuntimeState.Starting || state == RuntimeState.Connecting ||
        state == RuntimeState.Stopping || state is RuntimeState.Provisioning
    val failed = state is RuntimeState.Failed
    Surface(
        color = when {
            failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
            state == RuntimeState.Ready -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.64f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
        },
        shape = RoundedCornerShape(32.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val horizontal = maxWidth >= 700.dp
            if (horizontal) {
                Row(
                    Modifier.padding(34.dp),
                    horizontalArrangement = Arrangement.spacedBy(38.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RuntimeGlyph(state, Modifier.size(116.dp))
                    RuntimeHeroCopy(
                        state,
                        presentation,
                        onSetupAndStart,
                        onStart,
                        onStop,
                        onRetry,
                        onOpenTerminal,
                        onOpenFiles,
                        Modifier.weight(1f),
                    )
                }
            } else {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp)) {
                    RuntimeGlyph(state, Modifier.size(80.dp))
                    Spacer(Modifier.height(26.dp))
                    RuntimeHeroCopy(
                        state,
                        presentation,
                        onSetupAndStart,
                        onStart,
                        onStop,
                        onRetry,
                        onOpenTerminal,
                        onOpenFiles,
                    )
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
private fun RuntimeGlyph(state: RuntimeState, modifier: Modifier = Modifier) {
    val presentation = statePresentation(state)
    Surface(
        modifier = modifier.semantics { contentDescription = presentation.shortLabel },
        shape = RoundedCornerShape(28.dp),
        color = if (state == RuntimeState.Ready) BoxInk else MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                state == RuntimeState.Ready -> Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = BoxGreenLight,
                    modifier = Modifier.fillMaxSize(0.48f),
                )
                state is RuntimeState.Failed -> Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxSize(0.45f),
                )
                state == RuntimeState.Starting || state == RuntimeState.Connecting ||
                    state == RuntimeState.Stopping || state is RuntimeState.Provisioning ->
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(0.42f),
                        strokeWidth = 3.dp,
                    )
                else -> Icon(
                    Icons.Outlined.PowerSettingsNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxSize(0.42f),
                )
            }
        }
    }
}

@Composable
private fun RuntimeHeroCopy(
    state: RuntimeState,
    presentation: StatePresentation,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            presentation.eyebrow,
            style = MaterialTheme.typography.labelLarge,
            color = presentation.color,
            letterSpacing = 1.25.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(presentation.title, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        SelectionContainer {
            Text(
                presentation.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        when (state) {
            RuntimeState.NotProvisioned -> Button(onClick = onSetupAndStart) { Text("Set up Box") }
            RuntimeState.Stopped -> Button(onClick = onStart) { Text("Start Box") }
            RuntimeState.Starting,
            RuntimeState.Connecting,
            RuntimeState.Suspending,
            -> TextButton(onClick = onStop) { Text("Stop startup") }
            RuntimeState.Stopping -> Unit
            RuntimeState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpenTerminal) {
                    Icon(Icons.Outlined.Terminal, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open terminal")
                }
                OutlinedButton(onClick = onOpenFiles) { Text("Files") }
            }
            RuntimeState.Suspended -> Button(onClick = onStart) { Text("Resume Box") }
            is RuntimeState.Failed -> Button(onClick = onRetry) { Text("Try again") }
            is RuntimeState.Provisioning -> Unit
        }
    }
}

@Composable
private fun FeatureCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(42.dp),
            ) { Box(contentAlignment = Alignment.Center) { icon() } }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TerminalScreen(
    state: BoxUiState,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onRunCommand: (String) -> Unit,
) {
    if (state.runtimeState != RuntimeState.Ready) {
        RuntimeGate(
            destination = "terminal",
            state = state.runtimeState,
            onSetupAndStart = onSetupAndStart,
            onStart = onStart,
            onRetry = onRetry,
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
            .padding(bottom = 10.dp)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().widthIn(max = 1100.dp).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Terminal", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "One command at a time • /workspace",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(color = BoxGreen.copy(alpha = 0.14f), shape = CircleShape) {
                Text(
                    "CONNECTED",
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = BoxGreenLight,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 1100.dp).weight(1f),
            color = BoxTerminal,
            shape = RoundedCornerShape(26.dp),
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
                            "Commands run in /workspace using the guest shell.",
                            color = Color(0xFF9DA69F),
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
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF344038)),
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
                                    color = Color(0xFF9DA69F),
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
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            ),
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
                        color = Color(0xFFD7DED9),
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
private fun FilesScreen(
    state: BoxUiState,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
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
            onSetupAndStart = onSetupAndStart,
            onStart = onStart,
            onRetry = onRetry,
        )
        return
    }

    state.openedFile?.let { file ->
        FilePreview(file, onCloseFile)
        return
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().widthIn(max = 1000.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateUp, enabled = state.currentPath != "/workspace") {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Up one folder")
            }
            Column(Modifier.weight(1f)) {
                Text("Workspace", style = MaterialTheme.typography.headlineSmall)
                Text(
                    state.currentPath,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh files")
            }
        }
        WorkspaceBreadcrumb(state.currentPath, onOpenDirectory)
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 1000.dp).weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
            ),
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
                            "Create something from the terminal and it will appear here.",
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
private fun WorkspaceBreadcrumb(currentPath: String, onOpenDirectory: (String) -> Unit) {
    val relative = currentPath.removePrefix("/workspace").trim('/')
    val segments = if (relative.isBlank()) emptyList() else relative.split('/')
    Row(
        Modifier.fillMaxWidth().widthIn(max = 1000.dp).horizontalScroll(rememberScrollState()),
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
            Modifier.fillMaxWidth().widthIn(max = 1100.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to files") }
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    file.path,
                    style = MaterialTheme.typography.bodyMedium,
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
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 1100.dp).weight(1f),
            color = BoxTerminal,
            shape = RoundedCornerShape(22.dp),
        ) {
            SelectionContainer {
                LazyColumn(contentPadding = PaddingValues(18.dp)) {
                    item {
                        Text(
                            file.content,
                            color = Color(0xFFD7DED9),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeGate(
    destination: String,
    state: RuntimeState,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
) {
    val presentation = statePresentation(state)
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RuntimeGlyph(state, Modifier.size(78.dp))
            Spacer(Modifier.height(22.dp))
            Text(
                when (state) {
                    RuntimeState.Ready -> "Ready"
                    RuntimeState.Starting, RuntimeState.Connecting, is RuntimeState.Provisioning -> "Box is getting ready"
                    RuntimeState.Stopping -> "Box is shutting down"
                    is RuntimeState.Failed -> "Box needs attention"
                    else -> "Start Box to open $destination"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (state == RuntimeState.Starting || state == RuntimeState.Connecting || state is RuntimeState.Provisioning) {
                    "You’ll be able to use $destination as soon as the private Linux control channel is ready. Booting can take 2–3 minutes."
                } else if (state == RuntimeState.Stopping) {
                    "Your workspace is safe. You can start Box again after shutdown finishes."
                } else {
                    presentation.body
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            when (state) {
                RuntimeState.NotProvisioned -> Button(onClick = onSetupAndStart) { Text("Set up Box") }
                RuntimeState.Stopped, RuntimeState.Suspended -> Button(onClick = onStart) { Text("Start Box") }
                is RuntimeState.Failed -> Button(onClick = onRetry) { Text("Try again") }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsSheet(
    state: RuntimeState,
    onDismiss: () -> Unit,
    onSetupAndStart: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val presentation = statePresentation(state)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth().widthIn(max = 620.dp)) {
                Text("Runtime details", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(presentation.color))
                    Spacer(Modifier.width(8.dp))
                    Text(presentation.shortLabel, color = presentation.color, style = MaterialTheme.typography.labelLarge)
                }
                if (state is RuntimeState.Failed) {
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            SelectionContainer { Text(state.reason.message, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                DiagnosticRow("System", "Debian / ARM64")
                DiagnosticRow("Virtual machine", "QEMU TCG • 2 vCPU • 1 GB")
                DiagnosticRow("Workspace", "/workspace • persistent disk")
                DiagnosticRow("Control channel", "Private on-device socket")
                DiagnosticRow("Guest network", "Private NAT")
                Spacer(Modifier.height(20.dp))
                when (state) {
                    RuntimeState.NotProvisioned -> Button(onClick = onSetupAndStart, Modifier.fillMaxWidth()) { Text("Set up Box") }
                    RuntimeState.Stopped, RuntimeState.Suspended -> Button(onClick = onStart, Modifier.fillMaxWidth()) { Text("Start Box") }
                    RuntimeState.Starting, RuntimeState.Connecting, RuntimeState.Ready, RuntimeState.Suspending ->
                        OutlinedButton(onClick = onStop, Modifier.fillMaxWidth()) { Text("Stop Box") }
                    RuntimeState.Stopping -> Unit
                    is RuntimeState.Failed -> Button(onClick = onRetry, Modifier.fillMaxWidth()) { Text("Try again") }
                    is RuntimeState.Provisioning -> Unit
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "Box ${BuildConfig.VERSION_NAME} • ${BuildConfig.FLAVOR.replaceFirstChar { it.titlecase() }} runtime",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(0.58f)) {
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
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

@Preview(name = "Phone — ready", widthDp = 411, heightDp = 891)
@Composable
private fun ReadyPreview() {
    BoxTheme {
        BoxApp(
            state = BoxUiState(runtimeState = RuntimeState.Ready),
            onDestinationSelected = {},
            onSetupAndStart = {},
            onStart = {},
            onStop = {},
            onRetry = {},
            onRunCommand = {},
            onOpenDirectory = {},
            onNavigateUp = {},
            onRefreshFiles = {},
            onOpenFile = {},
            onCloseFile = {},
            onNoticeShown = {},
        )
    }
}

@Preview(name = "Foldable — starting", widthDp = 720, heightDp = 840)
@Composable
private fun StartingPreview() {
    BoxTheme {
        BoxApp(
            state = BoxUiState(runtimeState = RuntimeState.Starting),
            onDestinationSelected = {},
            onSetupAndStart = {},
            onStart = {},
            onStop = {},
            onRetry = {},
            onRunCommand = {},
            onOpenDirectory = {},
            onNavigateUp = {},
            onRefreshFiles = {},
            onOpenFile = {},
            onCloseFile = {},
            onNoticeShown = {},
        )
    }
}
