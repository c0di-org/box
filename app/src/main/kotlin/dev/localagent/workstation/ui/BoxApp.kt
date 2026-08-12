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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import dev.localagent.workstation.ComputerPanel
import dev.localagent.workstation.agent.Artifact
import dev.localagent.workstation.agent.PermissionDecision
import dev.localagent.workstation.computer.ControlHolder
import dev.localagent.workstation.computer.DesktopTransport

/**
 * Box's shell.
 *
 * Two destinations, and they are peers rather than a surface and its footnote:
 *
 * - **Tasks** — the box, everything being done inside it, and the conversation. One pane on a
 *   phone, list beside transcript on anything wider.
 * - **Computer** — the machine, taking the whole window at every size, with the agent floating
 *   over it. Not a pane, not a tab with a photograph of Linux on it, and not three taps deep.
 *
 * One thing overrides all of that: until the box is open there is nothing worth showing beside it,
 * so the home surface takes the whole window and gives the rest of the shell back as it settles.
 * That is not a separate screen or a gate to dismiss — see [YourBox].
 */
@Composable
fun BoxApp(
    state: BoxUiState,
    onDestinationSelected: (BoxDestination) -> Unit,
    onSelectSession: (String?) -> Unit,
    onNewConversation: (String) -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onStopSubAgent: (String) -> Unit,
    onPermissionDecision: (String, PermissionDecision) -> Unit,
    onOpenArtifact: (Artifact) -> Unit,
    onCloseSession: (String) -> Unit,
    onSelectComputerPanel: (ComputerPanel) -> Unit,
    onOpenBox: () -> Unit,
    onStop: () -> Unit,
    onRunCommand: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onCloseFile: () -> Unit,
    onNoticeShown: () -> Unit,
    onDismissGreeting: () -> Unit = {},
    onShowSignIn: () -> Unit = {},
    onDismissSignIn: () -> Unit = {},
    onBeginSignIn: () -> Unit = {},
    onOpenSignInUrl: (String) -> Unit = {},
    onSubmitSignInCode: (String) -> Unit = {},
    onCancelSignIn: () -> Unit = {},
    desktop: DesktopTransport? = null,
    onSetDesktopControl: (ControlHolder) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var dismissedRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val progress = rememberBoxProgress(state)
    // The app is handed back on press. Only the closed box is allowed to hold the window.
    val revealed = state.boxStage != BoxStage.Closed
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.message)
        onNoticeShown()
    }

    // Three minutes of waiting deserve to end with something the hand can feel. The words are the
    // snackbar's job, or the greeting's on the first open ever; this is just the arrival landing.
    LaunchedEffect(state.boxStage) {
        if (state.boxStage == BoxStage.Open) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
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
            val compact = layout == BoxLayout.Single

            val home: @Composable (Modifier, Boolean) -> Unit = { modifier, showSelection ->
                SessionsPane(
                    state = state,
                    progress = progress,
                    desktop = desktop,
                    onSelectSession = { onSelectSession(it) },
                    onNewConversation = onNewConversation,
                    onOpenBox = onOpenBox,
                    onOpenComputer = { onDestinationSelected(BoxDestination.Computer) },
                    onSendFirstTask = onSend,
                    onDismissGreeting = onDismissGreeting,
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
                    onStopSubAgent = onStopSubAgent,
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

            when (state.destination) {
                // The genuine thing: the machine is the window, and the agent is a panel on it.
                BoxDestination.Computer -> {
                    BackHandler(enabled = true) { onDestinationSelected(BoxDestination.Tasks) }
                    ComputerPane(
                        state = state,
                        progress = progress,
                        desktop = desktop,
                        onBack = { onDestinationSelected(BoxDestination.Tasks) },
                        onSelectPanel = onSelectComputerPanel,
                        onSetControl = onSetDesktopControl,
                        onOpenBox = onOpenBox,
                        onStop = onStop,
                        onShowDiagnostics = { showDiagnostics = true },
                        onRunCommand = onRunCommand,
                        onOpenDirectory = onOpenDirectory,
                        onNavigateUp = onNavigateUp,
                        onRefreshFiles = onRefreshFiles,
                        onOpenFile = onOpenFile,
                        onCloseFile = onCloseFile,
                        // No "open the computer" button on a panel that is already on it.
                        chat = { modifier -> conversation(modifier, null, false) },
                        modifier = Modifier.fillMaxSize(),
                        compact = compact,
                    )
                }

                BoxDestination.Tasks -> when (layout) {
                    BoxLayout.Single -> SinglePaneLayout(
                        state = state,
                        revealed = revealed,
                        progress = progress,
                        onSelectSession = onSelectSession,
                        onShowDiagnostics = { showDiagnostics = true },
                        home = home,
                        conversation = conversation,
                    )

                    BoxLayout.Wide -> WidePaneLayout(
                        state = state,
                        revealed = revealed,
                        progress = progress,
                        windowWidth = maxWidth,
                        onShowDiagnostics = { showDiagnostics = true },
                        home = home,
                        conversation = conversation,
                    )
                }
            }

            // Beside a permanent task list the conversation is always on screen, so it needs a
            // session even before the user picks one. Not while the box is still opening or the
            // first-open greeting is up: there is no room for a conversation then, and selecting
            // one behind the hero would mean the list arrives with a choice already made.
            LaunchedEffect(
                layout,
                state.boxStage,
                state.readyGreeting,
                state.sessions.firstOrNull()?.id,
                state.selectedSessionId,
            ) {
                val settled = state.boxStage == BoxStage.Open && !state.readyGreeting
                if (layout != BoxLayout.Single && settled && state.selectedSessionId == null) {
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

/**
 * Phone, folded. One pane, and the conversation pushes over the list.
 *
 * There is no bottom navigation bar. Two destinations do not need a permanent five percent of a
 * phone screen when one of them is the first row of the other and the second is a button in the
 * conversation's own header — and the bar it replaces was hidden inside conversations anyway,
 * which is exactly where someone would reach for it.
 */
@Composable
private fun SinglePaneLayout(
    state: BoxUiState,
    revealed: Boolean,
    progress: BoxProgress,
    onSelectSession: (String?) -> Unit,
    onShowDiagnostics: () -> Unit,
    home: @Composable (Modifier, Boolean) -> Unit,
    conversation: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
) {
    val inConversation = state.selectedSessionId != null

    BackHandler(enabled = inConversation) { onSelectSession(null) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (inConversation) {
                conversation(Modifier.fillMaxSize(), { onSelectSession(null) }, true)
            } else {
                Column(Modifier.fillMaxSize()) {
                    // The hero is the window while the box is closed, so the chrome above it
                    // arrives the moment the box is asked for — carrying the opening on its mark.
                    BoxChrome(visible = revealed) { BoxTopBar(state, progress, onShowDiagnostics) }
                    home(Modifier.fillMaxSize(), false)
                }
            }
        }
    }
}

/** Unfolded, tablet, or a DeX window. The task list earns a permanent home beside the transcript. */
@Composable
private fun WidePaneLayout(
    state: BoxUiState,
    revealed: Boolean,
    progress: BoxProgress,
    windowWidth: Dp,
    onShowDiagnostics: () -> Unit,
    home: @Composable (Modifier, Boolean) -> Unit,
    conversation: @Composable (Modifier, (() -> Unit)?, Boolean) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(homeWidth(!state.boxOwnsWindow, windowWidth))) {
            BoxChrome(visible = revealed) { BoxTopBar(state, progress, onShowDiagnostics) }
            home(Modifier.fillMaxSize(), true)
        }
        PaneDivider()
        Box(Modifier.weight(1f)) { conversation(Modifier.fillMaxSize(), null, true) }
    }
}

/**
 * How wide the home column is: the whole window while the box owns the surface, then its rail.
 *
 * A big screen deserves the same treatment as the phone. Squeezing the opening — or the one
 * arrival an install ever gets — into a 320dp column beside a pane with nothing in it is how a
 * moment written to be unmissable gets missed on the largest screen Box runs on.
 */
@Composable
private fun homeWidth(settled: Boolean, windowWidth: Dp) =
    animateDpAsState(
        targetValue = if (settled) SESSION_PANE_WIDTH else windowWidth,
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
        // The whole of the opening lives here too, for whenever the panel below is a row.
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
            onStopSubAgent = {},
            onPermissionDecision = { _, _ -> },
            onOpenArtifact = {},
            onCloseSession = {},
            onSelectComputerPanel = {},
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

@Preview(name = "Phone — first open ever", widthDp = 411, heightDp = 891)
@Composable
private fun PhoneGreetingPreview() = PreviewBox(
    BoxUiState(runtimeState = RuntimeState.Ready, readyGreeting = true),
)

@Preview(name = "Phone — open", widthDp = 411, heightDp = 891)
@Composable
private fun PhoneOpenPreview() = PreviewBox(BoxUiState(runtimeState = RuntimeState.Ready))

@Preview(name = "Unfolded — two pane", widthDp = 720, heightDp = 840)
@Composable
private fun UnfoldedPreview() = PreviewBox(BoxUiState(runtimeState = RuntimeState.Ready))

@Preview(name = "DeX — the computer", widthDp = 1440, heightDp = 900)
@Composable
private fun DexComputerPreview() = PreviewBox(
    BoxUiState(runtimeState = RuntimeState.Ready, destination = BoxDestination.Computer),
)
