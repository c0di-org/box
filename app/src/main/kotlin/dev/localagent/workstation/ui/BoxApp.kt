package dev.localagent.workstation.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BoxDestination
import dev.localagent.workstation.BoxProgress
import dev.localagent.workstation.BoxStage
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.ComputerTool
import dev.localagent.workstation.agent.Artifact
import dev.localagent.workstation.agent.PermissionDecision
import dev.localagent.workstation.computer.ControlHolder
import dev.localagent.workstation.computer.DesktopTransport

/**
 * Box's shell.
 *
 * Two destinations — the conversation and the computer — and three layouts derived from window
 * size. The layout decision is made once, here, and every pane below is written to be dropped
 * into any of the three without knowing which it landed in.
 *
 * One thing overrides all of that: until the box is open there is nothing worth showing beside it,
 * so the home surface takes the whole window at every size and gives the rest of the shell back as
 * it settles. That is not a separate screen or a gate to dismiss — see [YourBox].
 */
@Composable
fun BoxApp(
    state: BoxUiState,
    onDestinationSelected: (BoxDestination) -> Unit,
    onSelectSession: (String?) -> Unit,
    onNewConversation: (String) -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onPermissionDecision: (String, PermissionDecision) -> Unit,
    onOpenArtifact: (Artifact) -> Unit,
    onCloseSession: (String) -> Unit,
    onSelectComputerTool: (ComputerTool) -> Unit,
    onOpenBox: () -> Unit,
    onStop: () -> Unit,
    onRunCommand: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onCloseFile: () -> Unit,
    onNoticeShown: () -> Unit,
    onShowSignIn: () -> Unit = {},
    onDismissSignIn: () -> Unit = {},
    onBeginSignIn: () -> Unit = {},
    onOpenSignInUrl: (String) -> Unit = {},
    onSubmitSignInCode: (String) -> Unit = {},
    onCancelSignIn: () -> Unit = {},
    desktop: DesktopTransport? = null,
    onOpenDesktop: () -> Unit = {},
    onCloseDesktop: () -> Unit = {},
    onSetDesktopControl: (ControlHolder) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var dismissedRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val progress = rememberBoxProgress(state)
    // The app is handed back on press. Only the closed box is allowed to hold the window.
    val revealed = state.boxStage != BoxStage.Closed

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

            val home: @Composable (Modifier, Boolean) -> Unit = { modifier, showSelection ->
                SessionsPane(
                    state = state,
                    progress = progress,
                    desktop = desktop,
                    onSelectSession = { onSelectSession(it) },
                    onNewConversation = onNewConversation,
                    onOpenBox = onOpenBox,
                    onOpenDesktop = onOpenDesktop,
                    onShowDetails = { showDiagnostics = true },
                    modifier = modifier,
                    showSelection = showSelection,
                )
            }

            val conversation: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit =
                { modifier, onBack, showComputerAction ->
                    ConversationPane(
                        state = state,
                        onBack = onBack,
                        onSend = onSend,
                        onInterrupt = onInterrupt,
                        onOpenArtifact = onOpenArtifact,
                        onStartComputer = onOpenBox,
                        onOpenComputer = { onDestinationSelected(BoxDestination.Computer) },
                        onCloseSession = onCloseSession,
                        onReviewPermission = if (pending != null && !sheetVisible) {
                            { dismissedRequestId = null }
                        } else {
                            null
                        },
                        modifier = modifier,
                        showComputerAction = showComputerAction,
                        onSignIn = onShowSignIn,
                    )
                }

            val computer: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit = { modifier, onBack, compact ->
                ComputerPane(
                    state = state,
                    onBack = onBack,
                    onSelectTool = onSelectComputerTool,
                    onOpenBox = onOpenBox,
                    onStop = onStop,
                    onShowDiagnostics = { showDiagnostics = true },
                    onRunCommand = onRunCommand,
                    onOpenDirectory = onOpenDirectory,
                    onNavigateUp = onNavigateUp,
                    onRefreshFiles = onRefreshFiles,
                    onOpenFile = onOpenFile,
                    onCloseFile = onCloseFile,
                    onTakeControl = onOpenDesktop,
                    modifier = modifier,
                    compact = compact,
                    desktop = desktop,
                )
            }

            when (layout) {
                BoxLayout.Single -> SinglePaneLayout(
                    state = state,
                    revealed = revealed,
                    progress = progress,
                    onDestinationSelected = onDestinationSelected,
                    onSelectSession = onSelectSession,
                    onShowDiagnostics = { showDiagnostics = true },
                    home = home,
                    conversation = conversation,
                    computer = computer,
                )

                BoxLayout.Dual -> DualPaneLayout(
                    state = state,
                    revealed = revealed,
                    progress = progress,
                    windowWidth = maxWidth,
                    onDestinationSelected = onDestinationSelected,
                    onShowDiagnostics = { showDiagnostics = true },
                    home = home,
                    conversation = conversation,
                    computer = computer,
                )

                BoxLayout.Triple -> TriplePaneLayout(
                    state = state,
                    revealed = revealed,
                    progress = progress,
                    windowWidth = maxWidth,
                    onShowDiagnostics = { showDiagnostics = true },
                    home = home,
                    conversation = conversation,
                    computer = computer,
                )
            }

            // In every multi-pane layout the conversation is always on screen, so it needs a
            // session even before the user picks one. Not while the box is still opening: there is
            // no room for a conversation then, and selecting one behind the hero would mean the
            // list arrives with a choice already made for the user.
            LaunchedEffect(layout, revealed, state.sessions.firstOrNull()?.id, state.selectedSessionId) {
                if (layout != BoxLayout.Single && revealed && state.selectedSessionId == null) {
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

    // Above every pane and both sheets: this is a window mode, not a pane. Drawn last so a
    // permission sheet raised while the desktop is open cannot appear behind the picture.
    if (state.desktopVisible && desktop != null) {
        DesktopFullWindow(
            transport = desktop,
            control = state.desktopControl,
            onSetControl = onSetDesktopControl,
            onClose = onCloseDesktop,
        )
    }

    if (state.signInVisible) {
        SignInSheet(
            state = state.signIn,
            computerReady = state.computerReady,
            onBegin = onBeginSignIn,
            onOpenUrl = onOpenSignInUrl,
            onSubmitCode = onSubmitSignInCode,
            onCancel = onCancelSignIn,
            onDismiss = onDismissSignIn,
        )
    }

    if (showDiagnostics) {
        DiagnosticsSheet(
            state = state.runtimeState,
            onDismiss = { showDiagnostics = false },
            onOpenBox = {
                showDiagnostics = false
                onOpenBox()
            },
            onStop = {
                showDiagnostics = false
                onStop()
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
    revealed: Boolean,
    progress: BoxProgress,
    onDestinationSelected: (BoxDestination) -> Unit,
    onSelectSession: (String?) -> Unit,
    onShowDiagnostics: () -> Unit,
    home: @Composable (Modifier, Boolean) -> Unit,
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
                    // The hero is the window while the box is closed, so the chrome above it
                    // arrives the moment the box is asked for — carrying the opening on its mark.
                    BoxChrome(visible = revealed) { BoxTopBar(state, progress, onShowDiagnostics) }
                    home(Modifier.fillMaxSize(), false)
                }
            }
        }
        // Same reason: a Computer tab is a second door to a box nobody has asked for yet.
        BoxChrome(visible = revealed && !inConversation) {
            BoxNavigationBar(state.destination, onDestinationSelected)
        }
    }
}

/** Unfolded or tablet. The task list earns a permanent home beside the conversation. */
@Composable
private fun DualPaneLayout(
    state: BoxUiState,
    revealed: Boolean,
    progress: BoxProgress,
    windowWidth: Dp,
    onDestinationSelected: (BoxDestination) -> Unit,
    onShowDiagnostics: () -> Unit,
    home: @Composable (Modifier, Boolean) -> Unit,
    conversation: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
    computer: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(homeWidth(revealed, windowWidth))) {
            BoxChrome(visible = revealed) { BoxTopBar(state, progress, onShowDiagnostics) }
            home(Modifier.fillMaxSize(), true)
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

/** DeX with a keyboard and mouse. Everything at once: tasks, conversation, the computer. */
@Composable
private fun TriplePaneLayout(
    state: BoxUiState,
    revealed: Boolean,
    progress: BoxProgress,
    windowWidth: Dp,
    onShowDiagnostics: () -> Unit,
    home: @Composable (Modifier, Boolean) -> Unit,
    conversation: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
    computer: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(homeWidth(revealed, windowWidth))) {
            BoxChrome(visible = revealed) { BoxTopBar(state, progress, onShowDiagnostics) }
            home(Modifier.fillMaxSize(), true)
        }
        PaneDivider()
        Box(Modifier.weight(1f)) { conversation(Modifier.fillMaxSize(), null, false) }
        PaneDivider()
        Box(Modifier.weight(0.9f).widthIn(min = COMPUTER_PANE_MIN_WIDTH)) {
            computer(Modifier.fillMaxSize(), null, false)
        }
    }
}

/**
 * How wide the home column is: the whole window until the box has settled, then its rail width.
 *
 * A big screen deserves the same treatment as the phone. Showing a 320dp column with an "Open your
 * box" button in it, next to two panes that have nothing to show until the box is open, would be
 * three quarters of a desktop's worth of empty and one small button — exactly the thing this
 * change is getting rid of.
 */
@Composable
private fun homeWidth(revealed: Boolean, windowWidth: Dp) =
    animateDpAsState(
        targetValue = if (revealed) SESSION_PANE_WIDTH else windowWidth,
        animationSpec = tween(SETTLE_MILLIS),
        label = "home width",
    ).value

/** Chrome that belongs to the opened box, and arrives with it. */
@Composable
private fun BoxChrome(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(SETTLE_MILLIS)) + expandVertically(tween(SETTLE_MILLIS)),
        exit = fadeOut(tween(SETTLE_MILLIS / 2)) + shrinkVertically(tween(SETTLE_MILLIS / 2)),
    ) {
        content()
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
private fun BoxTopBar(state: BoxUiState, progress: BoxProgress, onShowDiagnostics: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The whole of the opening lives here now.
        OpeningMark(progress, opening = state.boxStage == BoxStage.Working)
        Spacer(Modifier.width(10.dp))
        Text(
            "Box",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
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
                label = { Text("Tasks") },
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
            onNewConversation = {},
            onSend = {},
            onInterrupt = {},
            onPermissionDecision = { _, _ -> },
            onOpenArtifact = {},
            onCloseSession = {},
            onSelectComputerTool = {},
            onOpenBox = {},
            onStop = {},
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

@Preview(name = "Phone — closed box", widthDp = 411, heightDp = 891)
@Composable
private fun PhoneClosedPreview() = PreviewBox(BoxUiState())

@Preview(name = "Phone — opening", widthDp = 411, heightDp = 891)
@Composable
private fun PhoneOpeningPreview() = PreviewBox(
    BoxUiState(
        runtimeState = RuntimeState.Connecting,
        openingSince = SystemClock.elapsedRealtime() - 40_000L,
    ),
)

@Preview(name = "Phone — open", widthDp = 411, heightDp = 891)
@Composable
private fun PhoneOpenPreview() = PreviewBox(BoxUiState(runtimeState = RuntimeState.Ready))

@Preview(name = "Unfolded — two pane", widthDp = 720, heightDp = 840)
@Composable
private fun UnfoldedPreview() = PreviewBox(BoxUiState(runtimeState = RuntimeState.Ready))

@Preview(name = "DeX — three pane", widthDp = 1440, heightDp = 900)
@Composable
private fun DexPreview() = PreviewBox(BoxUiState(runtimeState = RuntimeState.Ready))
