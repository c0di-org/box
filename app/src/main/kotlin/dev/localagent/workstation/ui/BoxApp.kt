package dev.localagent.workstation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BoxDestination
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.ComputerTool
import dev.localagent.workstation.agent.Artifact
import dev.localagent.workstation.agent.PermissionDecision

/**
 * Box's shell.
 *
 * Two destinations — the conversation and the computer — and three layouts derived from window
 * size. The layout decision is made once, here, and every pane below is written to be dropped
 * into any of the three without knowing which it landed in.
 */
@Composable
fun BoxApp(
    state: BoxUiState,
    onDestinationSelected: (BoxDestination) -> Unit,
    onSelectSession: (String?) -> Unit,
    onToggleHarness: (String) -> Unit,
    onNewConversation: (String) -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onPermissionDecision: (String, PermissionDecision) -> Unit,
    onOpenArtifact: (Artifact) -> Unit,
    onCloseSession: (String) -> Unit,
    onSelectComputerTool: (ComputerTool) -> Unit,
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
    var dismissedRequestId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.message)
        onNoticeShown()
    }

    val pending = state.transcript?.pendingPermission
    val sheetVisible = pending != null && pending.requestId != dismissedRequestId

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding(),
        ) {
            val layout = rememberBoxLayout(maxWidth, maxHeight)

            val conversation: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit =
                { modifier, onBack, showComputerAction ->
                    ConversationPane(
                        state = state,
                        onBack = onBack,
                        onSend = onSend,
                        onInterrupt = onInterrupt,
                        onOpenArtifact = onOpenArtifact,
                        onStartComputer = {
                            if (state.runtimeState == RuntimeState.NotProvisioned) {
                                onSetupAndStart()
                            } else {
                                onStart()
                            }
                        },
                        onOpenComputer = { onDestinationSelected(BoxDestination.Computer) },
                        onCloseSession = onCloseSession,
                        onReviewPermission = if (pending != null && !sheetVisible) {
                            { dismissedRequestId = null }
                        } else {
                            null
                        },
                        modifier = modifier,
                        showComputerAction = showComputerAction,
                    )
                }

            val computer: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit = { modifier, onBack, compact ->
                ComputerPane(
                    state = state,
                    onBack = onBack,
                    onSelectTool = onSelectComputerTool,
                    onSetupAndStart = onSetupAndStart,
                    onStart = onStart,
                    onStop = onStop,
                    onRetry = onRetry,
                    onShowDiagnostics = { showDiagnostics = true },
                    onRunCommand = onRunCommand,
                    onOpenDirectory = onOpenDirectory,
                    onNavigateUp = onNavigateUp,
                    onRefreshFiles = onRefreshFiles,
                    onOpenFile = onOpenFile,
                    onCloseFile = onCloseFile,
                    onTakeControl = { onOpenArtifact(Artifact.Computer) },
                    modifier = modifier,
                    compact = compact,
                )
            }

            when (layout) {
                BoxLayout.Single -> SinglePaneLayout(
                    state = state,
                    onDestinationSelected = onDestinationSelected,
                    onSelectSession = onSelectSession,
                    onToggleHarness = onToggleHarness,
                    onNewConversation = onNewConversation,
                    onShowDiagnostics = { showDiagnostics = true },
                    conversation = conversation,
                    computer = computer,
                )

                BoxLayout.Dual -> DualPaneLayout(
                    state = state,
                    onDestinationSelected = onDestinationSelected,
                    onSelectSession = onSelectSession,
                    onToggleHarness = onToggleHarness,
                    onNewConversation = onNewConversation,
                    onShowDiagnostics = { showDiagnostics = true },
                    conversation = conversation,
                    computer = computer,
                )

                BoxLayout.Triple -> TriplePaneLayout(
                    state = state,
                    onSelectSession = onSelectSession,
                    onToggleHarness = onToggleHarness,
                    onNewConversation = onNewConversation,
                    onShowDiagnostics = { showDiagnostics = true },
                    conversation = conversation,
                    computer = computer,
                )
            }

            // In every multi-pane layout the conversation is always on screen, so it needs a
            // session even before the user picks one.
            LaunchedEffect(layout, state.sessions.firstOrNull()?.id, state.selectedSessionId) {
                if (layout != BoxLayout.Single && state.selectedSessionId == null) {
                    state.sessions.firstOrNull()?.let { onSelectSession(it.id) }
                }
            }
        }
    }

    if (pending != null && sheetVisible) {
        val harnessName = state.harnesses
            .firstOrNull { it.id == state.selectedSession?.harnessId }
            ?.name
        PermissionSheet(
            pending = pending,
            harnessName = harnessName,
            onDecision = { decision ->
                dismissedRequestId = null
                onPermissionDecision(pending.requestId, decision)
            },
            onDismiss = { dismissedRequestId = pending.requestId },
        )
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

// ---------------------------------------------------------------------------
// Layouts
// ---------------------------------------------------------------------------

/** Phone, folded. One pane, bottom nav, and the conversation pushes over the list. */
@Composable
private fun SinglePaneLayout(
    state: BoxUiState,
    onDestinationSelected: (BoxDestination) -> Unit,
    onSelectSession: (String?) -> Unit,
    onToggleHarness: (String) -> Unit,
    onNewConversation: (String) -> Unit,
    onShowDiagnostics: () -> Unit,
    conversation: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
    computer: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
) {
    val inConversation = state.destination == BoxDestination.Conversations &&
        state.selectedSessionId != null

    BackHandler(enabled = inConversation) { onSelectSession(null) }
    BackHandler(enabled = state.destination == BoxDestination.Computer) {
        onDestinationSelected(BoxDestination.Conversations)
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when {
                inConversation -> conversation(Modifier.fillMaxSize(), { onSelectSession(null) }, false)
                state.destination == BoxDestination.Computer ->
                    computer(Modifier.fillMaxSize(), null, true)
                else -> Column(Modifier.fillMaxSize()) {
                    BoxTopBar(state, onShowDiagnostics)
                    SessionsPane(
                        state = state,
                        onSelectSession = { onSelectSession(it) },
                        onToggleHarness = onToggleHarness,
                        onNewConversation = onNewConversation,
                        showSelection = false,
                    )
                }
            }
        }
        if (!inConversation) {
            BoxNavigationBar(state.destination, onDestinationSelected)
        }
    }
}

/** Unfolded or tablet. The session list earns a permanent home beside the conversation. */
@Composable
private fun DualPaneLayout(
    state: BoxUiState,
    onDestinationSelected: (BoxDestination) -> Unit,
    onSelectSession: (String?) -> Unit,
    onToggleHarness: (String) -> Unit,
    onNewConversation: (String) -> Unit,
    onShowDiagnostics: () -> Unit,
    conversation: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
    computer: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(SESSION_PANE_WIDTH)) {
            BoxTopBar(state, onShowDiagnostics)
            SessionsPane(
                state = state,
                onSelectSession = { onSelectSession(it) },
                onToggleHarness = onToggleHarness,
                onNewConversation = onNewConversation,
            )
        }
        PaneDivider()
        Box(Modifier.weight(1f)) {
            when (state.destination) {
                BoxDestination.Conversations -> conversation(Modifier.fillMaxSize(), null, true)
                BoxDestination.Computer -> computer(
                    Modifier.fillMaxSize(),
                    { onDestinationSelected(BoxDestination.Conversations) },
                    false,
                )
            }
        }
    }
}

/** DeX with a keyboard and mouse. Everything at once: sessions, conversation, the computer. */
@Composable
private fun TriplePaneLayout(
    state: BoxUiState,
    onSelectSession: (String?) -> Unit,
    onToggleHarness: (String) -> Unit,
    onNewConversation: (String) -> Unit,
    onShowDiagnostics: () -> Unit,
    conversation: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
    computer: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(SESSION_PANE_WIDTH)) {
            BoxTopBar(state, onShowDiagnostics)
            SessionsPane(
                state = state,
                onSelectSession = { onSelectSession(it) },
                onToggleHarness = onToggleHarness,
                onNewConversation = onNewConversation,
            )
        }
        PaneDivider()
        Box(Modifier.weight(1f)) { conversation(Modifier.fillMaxSize(), null, false) }
        PaneDivider()
        Box(Modifier.weight(0.9f).widthIn(min = COMPUTER_PANE_MIN_WIDTH)) {
            computer(Modifier.fillMaxSize(), null, false)
        }
    }
}

@Composable
private fun PaneDivider() {
    VerticalDivider(
        modifier = Modifier.fillMaxHeight().width(1.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

@Composable
private fun BoxTopBar(state: BoxUiState, onShowDiagnostics: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxMark(32.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "Box",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        StatusPill(state.runtimeState)
        IconButton(onClick = onShowDiagnostics) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Computer details")
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
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            NavigationBarItem(
                selected = selected == BoxDestination.Conversations,
                onClick = { onSelected(BoxDestination.Conversations) },
                icon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                label = { Text("Conversations") },
            )
            NavigationBarItem(
                selected = selected == BoxDestination.Computer,
                onClick = { onSelected(BoxDestination.Computer) },
                icon = { Icon(Icons.Outlined.Computer, contentDescription = null) },
                label = { Text("Computer") },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Composable
private fun PreviewBox(state: BoxUiState) {
    BoxTheme {
        BoxApp(
            state = state,
            onDestinationSelected = {},
            onSelectSession = {},
            onToggleHarness = {},
            onNewConversation = {},
            onSend = {},
            onInterrupt = {},
            onPermissionDecision = { _, _ -> },
            onOpenArtifact = {},
            onCloseSession = {},
            onSelectComputerTool = {},
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

@Preview(name = "Phone — sessions", widthDp = 411, heightDp = 891)
@Composable
private fun PhonePreview() = PreviewBox(BoxUiState(runtimeState = RuntimeState.Ready))

@Preview(name = "Phone — booting", widthDp = 411, heightDp = 891)
@Composable
private fun PhoneBootingPreview() = PreviewBox(BoxUiState(runtimeState = RuntimeState.Starting))

@Preview(name = "Unfolded — two pane", widthDp = 720, heightDp = 840)
@Composable
private fun UnfoldedPreview() = PreviewBox(BoxUiState(runtimeState = RuntimeState.Ready))

@Preview(name = "DeX — three pane", widthDp = 1440, heightDp = 900)
@Composable
private fun DexPreview() = PreviewBox(BoxUiState(runtimeState = RuntimeState.Ready))
