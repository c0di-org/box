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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import dev.localagent.workstation.FilesPlace
import dev.localagent.workstation.QueuedPrompt
import dev.localagent.workstation.ComputerPanel
import dev.localagent.workstation.agent.AgentPermissionMode
import dev.localagent.workstation.agent.AgentViewport
import dev.localagent.workstation.agent.Attachment
import dev.localagent.workstation.agent.Artifact
import dev.localagent.workstation.agent.GuestAuth
import dev.localagent.workstation.agent.PermissionAsk
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
    onUndoCloseSession: () -> Unit,
    onCommitCloseSession: () -> Unit,
    onSelectComputerPanel: (ComputerPanel) -> Unit,
    onOpenBox: () -> Unit,
    onPutAway: () -> Unit,
    onStop: () -> Unit,
    onRunCommand: (String) -> Unit,
    onSelectFilesPlace: (FilesPlace) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onCloseFile: () -> Unit,
    onOpenInPhoneFiles: () -> Unit,
    onNoticeShown: () -> Unit,
    onDismissGreeting: () -> Unit = {},
    onShowSignIn: () -> Unit = {},
    onDismissSignIn: () -> Unit = {},
    onBeginSignIn: () -> Unit = {},
    onOpenSignInUrl: (String) -> Unit = {},
    onSubmitSignInCode: (String) -> Unit = {},
    onCancelSignIn: () -> Unit = {},
    onShowGitHub: () -> Unit = {},
    onResumeConnection: () -> Unit = {},
    onDismissGitHub: () -> Unit = {},
    onConnectGitHub: () -> Unit = {},
    onGitHubRepositoriesChosen: () -> Unit = {},
    onSubmitGitHubToken: (String) -> Unit = {},
    onDeclineConnection: () -> Unit = {},
    onDisconnectGitHub: () -> Unit = {},
    onSetPermissionMode: (AgentPermissionMode) -> Unit = {},
    onViewportChanged: (AgentViewport) -> Unit = {},
    onAttachPhoto: (() -> Unit)? = null,
    onAttachFile: (() -> Unit)? = null,
    onRemoveAttachment: (Attachment) -> Unit = {},
    desktop: DesktopTransport? = null,
    onSetDesktopControl: (ControlHolder) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    /**
     * The one request the user asked to look at properly, or null — which is almost always.
     *
     * The sheet is opened, never raised. Box used to put it up by itself the moment anything was
     * asked, on a phone, over whatever the user was doing: the keyboard went down as it arrived
     * and came back up after the answer, so a request that landed mid-sentence cost them their
     * place twice, and a request they had already decided about from the card still had to be
     * dismissed. Every unanswered request draws its own decision in the transcript, next to the
     * work it is about, so nothing needs a modal to be answerable — the sheet is for the one thing
     * a card cannot hold, which is a whole diff, and a person asks for it by tapping the card.
     */
    var reviewingRequestId by remember { mutableStateOf<String?>(null) }
    val progress = rememberBoxProgress(state)
    // The app is handed back on press. Only a box holding the whole window keeps the chrome off,
    // which is the first-run splash and the one arrival — never a closed box with work under it.
    val revealed = state.boxStage != BoxStage.Closed || state.tasks.isNotEmpty()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.message)
        onNoticeShown()
    }

    // The undo window for a task swiped off the list, which is the snackbar's own lifetime and not
    // a timer of Box's: whichever way it goes away is the user's answer. A second swipe cancels
    // this effect without either branch running, which is why committing the previous task is the
    // view model's job — see [BoxViewModel.beginClosingTask].
    LaunchedEffect(state.closingTaskId) {
        if (state.closingTaskId == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Task closed",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndoCloseSession() else onCommitCloseSession()
    }

    // Three minutes of waiting deserve to end with something the hand can feel. The words are the
    // snackbar's job, or the greeting's on the first open ever; this is just the arrival landing.
    LaunchedEffect(state.boxStage) {
        if (state.boxStage == BoxStage.Open) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val waiting = state.transcript?.pendingPermissions.orEmpty()
    /*
     * What the sheet is about, if anything: only ever the one the user tapped.
     *
     * Answered elsewhere in the meantime — from the card, or by an "always allow" that covered it
     * — is the same as never having been asked for, so the sheet closes with it.
     *
     * Never a question. A question stops the work by design, so its card is already the
     * interruption; a modal over that interrupts the same person twice about the same thing, and
     * everything a question needs — the options, what each one means, the free-text answer — is on
     * the card. Filtered here rather than trusted not to happen, so [PermissionSheet] can say
     * plainly that it never draws one.
     */
    val sheetTarget = waiting.firstOrNull {
        it.requestId == reviewingRequestId && it.ask !is PermissionAsk.Questions
    }

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

            // The one place in Box that knows how much room there is. Reported rather than stored:
            // this is the same measurement the layout above is drawn from, so an agent writing for
            // a wide window and a UI drawing one can never disagree about which it is.
            val viewport = rememberViewport(maxWidth, maxHeight)
            LaunchedEffect(viewport) { onViewportChanged(viewport) }

            val home: @Composable (Modifier, Boolean) -> Unit = { modifier, showSelection ->
                SessionsPane(
                    state = state,
                    progress = progress,
                    desktop = desktop,
                    onSelectSession = { onSelectSession(it) },
                    onNewConversation = onNewConversation,
                    onCloseTask = onCloseSession,
                    onOpenBox = onOpenBox,
                    onOpenComputer = { onDestinationSelected(BoxDestination.Computer) },
                    onSendFirstTask = onSend,
                    onDismissGreeting = onDismissGreeting,
                    onShowDetails = { showDiagnostics = true },
                    onSignIn = onShowSignIn,
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
                    onPermissionDecision = onPermissionDecision,
                    onReviewRequest = { requestId -> reviewingRequestId = requestId },
                    modifier = modifier,
                    showComputerAction = showComputerAction,
                    // In `Wide` the task list is beside this, and the box's own state is the
                    // first thing on it -- a card reading "Your box is closed / Nothing is
                    // running / Open". Repeating it as a banner over the transcript said the same
                    // sentence twice, about six inches apart, with the same button on both. The
                    // list owns it there. In `Single` they are different screens and never
                    // collide, so the banner is the only place it can be said at all.
                    showBoxState = layout == BoxLayout.Single,
                    onSignIn = onShowSignIn,
                    onConnectGitHub = onResumeConnection,
                    onDeclineConnection = onDeclineConnection,
                    onSetPermissionMode = onSetPermissionMode,
                    onAttachPhoto = onAttachPhoto,
                    onAttachFile = onAttachFile,
                    onRemoveAttachment = onRemoveAttachment,
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
                        onSelectFilesPlace = onSelectFilesPlace,
                        onOpenDirectory = onOpenDirectory,
                        onNavigateUp = onNavigateUp,
                        onRefreshFiles = onRefreshFiles,
                        onOpenFile = onOpenFile,
                        onCloseFile = onCloseFile,
                        onOpenInPhoneFiles = onOpenInPhoneFiles,
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

            if (sheetTarget != null) {
                val harnessName = state.harnesses
                    .firstOrNull { it.id == state.selectedSession?.harnessId }
                    ?.name
                PermissionSheet(
                    pending = sheetTarget,
                    harnessName = harnessName,
                    // How many others are blocked behind this one, so answering does not feel like
                    // the end of it when it is not.
                    alsoWaiting = waiting.count { it.requestId != sheetTarget.requestId },
                    onDecision = { decision ->
                        reviewingRequestId = null
                        onPermissionDecision(sheetTarget.requestId, decision)
                    },
                    // Closing answers nothing, and costs nothing: the request is still standing in
                    // the transcript with its buttons on it.
                    onDismiss = { reviewingRequestId = null },
                )
            }
        }
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

    if (state.githubVisible) {
        ConnectGitHubSheet(
            state = state.github,
            computerReady = state.computerReady,
            reason = state.connectRequest?.reason,
            agentWaiting = state.connectRequest != null,
            onConnect = onConnectGitHub,
            onOpenUrl = onOpenSignInUrl,
            onRepositoriesChosen = onGitHubRepositoriesChosen,
            onSubmitToken = onSubmitGitHubToken,
            onDecline = onDeclineConnection,
            onDisconnect = onDisconnectGitHub,
            onDismiss = onDismissGitHub,
        )
    }

    if (showDiagnostics) {
        DiagnosticsSheet(
            state = state.runtimeState,
            signIn = state.signIn,
            github = state.github,
            onDismiss = { showDiagnostics = false },
            onOpenBox = {
                showDiagnostics = false
                onOpenBox()
            },
            onPutAway = {
                showDiagnostics = false
                onPutAway()
            },
            onStop = {
                showDiagnostics = false
                onStop()
            },
            onSignIn = {
                showDiagnostics = false
                onShowSignIn()
            },
            onGitHub = {
                showDiagnostics = false
                onShowGitHub()
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
            onUndoCloseSession = {},
            onCommitCloseSession = {},
            onSelectComputerPanel = {},
            onOpenBox = {},
            onPutAway = {},
            onStop = {},
            onRunCommand = {},
            onSelectFilesPlace = {},
            onOpenDirectory = {},
            onNavigateUp = {},
            onRefreshFiles = {},
            onOpenFile = {},
            onCloseFile = {},
            onOpenInPhoneFiles = {},
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

@Preview(name = "Phone — first open, not signed in", widthDp = 411, heightDp = 891)
@Composable
private fun PhoneSignInPreview() = PreviewBox(
    BoxUiState(
        runtimeState = RuntimeState.Ready,
        readyGreeting = true,
        signIn = GuestAuth.State.SignedOut,
        queued = listOf(
            QueuedPrompt(null, "Clone my project and get it running.", heldForSignIn = true),
        ),
    ),
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
