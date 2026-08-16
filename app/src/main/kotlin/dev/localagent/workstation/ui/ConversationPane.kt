package dev.localagent.workstation.ui

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BoxContainer
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.QueuedPrompt
import dev.localagent.workstation.agent.AgentActivity
import dev.localagent.workstation.agent.AgentModel
import dev.localagent.workstation.agent.AgentPermissionMode
import dev.localagent.workstation.agent.Artifact
import dev.localagent.workstation.ConnectRequest
import dev.localagent.workstation.agent.Attachment
import dev.localagent.workstation.agent.HarnessDescriptor
import dev.localagent.workstation.agent.PermissionAsk
import dev.localagent.workstation.agent.PermissionDecision
import dev.localagent.workstation.agent.SessionConnection
import dev.localagent.workstation.agent.Transcript
import dev.localagent.workstation.agent.TranscriptItem
import kotlinx.coroutines.launch

/**
 * The primary surface. A user should be able to live here and never open the computer view, so
 * everything the agent does has to be legible from inside the transcript.
 */
@Composable
fun ConversationPane(
    state: BoxUiState,
    onBack: (() -> Unit)?,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onStopSubAgent: (String) -> Unit,
    onPermissionDecision: (String, PermissionDecision) -> Unit,
    onReviewRequest: (String) -> Unit,
    onOpenArtifact: (Artifact) -> Unit,
    onStartComputer: () -> Unit,
    onOpenComputer: () -> Unit,
    onCloseSession: (String) -> Unit,
    /** [TOUR_PROMPT], sent into this conversation. See `BoxViewModel.startTour`. */
    onTour: () -> Unit = {},
    modifier: Modifier = Modifier,
    showComputerAction: Boolean = true,
    /**
     * Whether this pane is the one that reports the box's own state.
     *
     * False in `Wide`, where the task list beside it already leads with the box and its Open
     * button. See the call site in [BoxApp]; the default is true so the pane on its own always
     * says it.
     */
    showBoxState: Boolean = true,
    onSignIn: () -> Unit = {},
    onConnectGitHub: () -> Unit = {},
    onDeclineConnection: () -> Unit = {},
    onSetPermissionMode: (AgentPermissionMode) -> Unit = {},
    onSetAgentModel: (AgentModel) -> Unit = {},
    onAttachPhoto: (() -> Unit)? = null,
    onAttachFile: (() -> Unit)? = null,
    onRemoveAttachment: (Attachment) -> Unit = {},
) {
    val session = state.selectedSession
    val harness = state.harnesses.firstOrNull { it.id == session?.harnessId }
    val application = LocalContext.current.applicationContext as Application
    val harnessControls = remember(application) { BoxContainer.harnessControls(application) }
    val openHarnessSettings = harness?.takeIf { it.capabilities.hasSettings }?.let { selected ->
        { harnessControls.show(selected, state.computerReady) }
    }
    val queued = state.queuedForSelected
    // Held here rather than inside the list, because the composer's own way back to an unanswered
    // request is a scroll and not a modal. See [waitingAction].
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Ticks on a question that has not been sent yet, kept above the list that draws it. See
    // [AnswerStore] for why they cannot live in the card.
    val answers = remember { AnswerStore() }

    Column(modifier.fillMaxSize()) {
        ConversationHeader(
            title = session?.title ?: "Box",
            harness = harness,
            busy = state.transcript?.isBusy == true,
            onBack = onBack,
            onInterrupt = onInterrupt,
            onOpenComputer = onOpenComputer,
            onCloseSession = session?.let { { onCloseSession(it.id) } },
            onOpenHarnessSettings = openHarnessSettings,
            showComputerAction = showComputerAction,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        val connectionTrouble = state.computerReady &&
            state.connection !is SessionConnection.Live &&
            state.connection !is SessionConnection.Ended
        val connectRequest = state.connectRequest?.takeIf { it.sessionId == state.selectedSessionId }
        when {
            // This state is Claude's existing credential state. Other harnesses own their account
            // state behind their advertised settings capability rather than inheriting Claude UI.
            state.needsSignIn && (harness == null || harness.id == "claude-code") -> SignInBanner(onSignIn)
            showBoxState && state.runtimeState != RuntimeState.Ready ->
                ComputerBanner(state.runtimeState, onStartComputer)
            connectRequest != null ->
                ConnectBanner(connectRequest, onConnectGitHub, onDeclineConnection)
            connectionTrouble -> ConnectionBanner(state.connection)
        }

        val nothingToShow = (state.transcript == null || state.transcript.items.isEmpty()) &&
            queued.isEmpty() && !state.agentStopped

        Box(Modifier.weight(1f)) {
            when {
                session == null && queued.isEmpty() ->
                    NoSessionState(state.tasks.isNotEmpty(), onTour)
                (state.computerReady || state.connection == SessionConnection.Connecting) &&
                    state.transcriptLoading && state.transcript == null &&
                    queued.isEmpty() -> TranscriptLoading()
                nothingToShow -> EmptyTranscriptState(harness, onTour)
                else -> TranscriptList(
                    transcript = state.transcript,
                    queued = queued,
                    harness = harness,
                    listState = listState,
                    answers = answers,
                    stopped = state.agentStopped,
                    onOpenArtifact = onOpenArtifact,
                    onRetry = onStartComputer,
                    onStopSubAgent = onStopSubAgent,
                    onPermissionDecision = { requestId, decision ->
                        answers.forget(requestId)
                        onPermissionDecision(requestId, decision)
                    },
                    onReviewPermission = onReviewRequest,
                )
            }
        }

        val waiting = state.transcript?.pendingPermissions.orEmpty()
        val waitingAction: (() -> Unit)? = waiting.firstOrNull()?.let { oldest ->
            {
                val index = state.transcript?.items.orEmpty().indexOfFirst { it.holds(oldest.requestId) }
                if (index >= 0) {
                    scope.launch { listState.animateScrollToItem(index) }
                } else {
                    onReviewRequest(oldest.requestId)
                }
            }
        }
        Composer(
            enabled = state.harnesses.isNotEmpty(),
            notice = when {
                waiting.size > 1 -> "${waiting.size} things are waiting on you."
                waiting.firstOrNull()?.ask is PermissionAsk.Questions -> "A question is waiting on you."
                waiting.isNotEmpty() -> "A request is waiting on you."
                else -> null
            },
            onNotice = waitingAction,
            blockedReason = when {
                state.computerReady && state.connection is SessionConnection.Disconnected ->
                    "Connection lost."
                else -> null
            },
            placeholder = "Ask Box anything…",
            onSend = onSend,
            mode = state.permissionMode,
            onModeChange = onSetPermissionMode,
            model = state.agentModel,
            // AgentModel remains Claude's established type. Non-Claude harnesses expose their
            // own advertised model catalog through Agent settings instead of seeing Claude names.
            onModelChange = if (harness == null || harness.id == "claude-code") onSetAgentModel else null,
            attachments = state.pendingAttachments,
            onAttachPhoto = onAttachPhoto,
            onAttachFile = onAttachFile,
            onRemoveAttachment = onRemoveAttachment,
        )
    }

    HarnessSettingsSheet(controller = harnessControls, onDismiss = harnessControls::dismiss)
}

@Composable
private fun ConversationHeader(
    title: String,
    harness: HarnessDescriptor?,
    busy: Boolean,
    onBack: (() -> Unit)?,
    onInterrupt: () -> Unit,
    onOpenComputer: () -> Unit,
    onCloseSession: (() -> Unit)?,
    onOpenHarnessSettings: (() -> Unit)?,
    showComputerAction: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to tasks")
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        if (harness != null) {
            HarnessMark(harness, 30.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            if (harness != null) {
                Text(
                    harness.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            WorkingDot(modifier = Modifier.padding(end = 2.dp))
            TextButton(onClick = onInterrupt) {
                Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stop")
            }
        }
        if (showComputerAction) {
            IconButton(onClick = onOpenComputer) {
                Icon(Icons.Outlined.Computer, contentDescription = "Open the computer")
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Task options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onOpenHarnessSettings != null) {
                    DropdownMenuItem(
                        text = { Text("Agent settings") },
                        onClick = {
                            menuOpen = false
                            onOpenHarnessSettings()
                        },
                    )
                }
                if (onCloseSession != null) {
                    DropdownMenuItem(
                        text = { Text("Close task") },
                        onClick = {
                            menuOpen = false
                            onCloseSession()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(connection: SessionConnection) {
    when (connection) {
        is SessionConnection.Disconnected -> Banner(
            tint = MaterialTheme.colorScheme.error,
            icon = { Icon(Icons.Outlined.CloudOff, null, Modifier.size(17.dp)) },
            title = connection.reason,
            body = if (connection.retrying) "Reconnecting…" else null,
        )
        SessionConnection.Connecting -> Banner(
            tint = MaterialTheme.colorScheme.primary,
            icon = { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) },
            title = "Connecting",
            body = null,
        )
        SessionConnection.Live, SessionConnection.Ended -> Unit
    }
}

private fun permissionModeLabel(mode: AgentPermissionMode): String = when (mode) {
    AgentPermissionMode.Ask -> "Ask every time"
    AgentPermissionMode.AcceptEdits -> "Accept file edits"
    AgentPermissionMode.Everything -> "Approve everything"
}

@Composable
private fun ComputerBanner(runtimeState: RuntimeState, onStart: () -> Unit) {
    val banner: Triple<String, String?, String?> = when (runtimeState) {
        RuntimeState.Ready -> return
        RuntimeState.Starting -> Triple("Booting Debian", "Keep typing — Box sends when it’s ready.", null)
        RuntimeState.Connecting -> Triple("Almost ready", null, null)
        is RuntimeState.Provisioning -> Triple("Setting up your box", null, null)
        RuntimeState.NotProvisioned -> Triple("No box yet", null, "Open")
        RuntimeState.Stopped -> Triple("Your box is closed", null, "Open")
        RuntimeState.Suspended -> Triple("Your box is paused", null, "Open")
        RuntimeState.Stopping -> Triple("Shutting down", null, null)
        RuntimeState.Suspending -> Triple("Pausing your box", null, null)
        is RuntimeState.Failed -> Triple("The computer couldn’t start", runtimeState.reason.message, "Try again")
    }
    val (title, body, action) = banner
    Banner(
        tint = if (runtimeState is RuntimeState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        icon = { Icon(Icons.Outlined.Computer, null, Modifier.size(17.dp)) },
        title = title,
        body = body,
        action = action?.let { it to onStart },
    )
}

@Composable
private fun Banner(
    tint: Color,
    icon: @Composable () -> Unit,
    title: String,
    body: String?,
    action: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Surface(color = tint.copy(alpha = 0.10f), contentColor = tint, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                body?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            secondary?.let { (label, onClick) ->
                TextButton(onClick = onClick) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
        }
    }
}

@Composable
private fun TranscriptList(
    transcript: Transcript?,
    queued: List<QueuedPrompt>,
    harness: HarnessDescriptor?,
    listState: LazyListState,
    answers: AnswerStore,
    stopped: Boolean,
    onOpenArtifact: (Artifact) -> Unit,
    onRetry: () -> Unit,
    onStopSubAgent: (String) -> Unit,
    onPermissionDecision: (String, PermissionDecision) -> Unit,
    onReviewPermission: (String) -> Unit,
) {
    val items = transcript?.items.orEmpty()
    val lastKey = items.lastOrNull()?.key
    val total = items.size + queued.size
    val scope = rememberCoroutineScope()
    LaunchedEffect(lastKey, items.size, queued.size, transcript?.activity) {
        if (total > 0 && listState.isNearEnd()) listState.animateScrollToItem(total)
    }
    val nextRequest = transcript?.pendingPermissions?.firstOrNull()?.requestId
    LaunchedEffect(nextRequest, items.size) {
        val requestId = nextRequest ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.holds(requestId) }
        if (index >= 0 && !listState.isFullyVisible(index)) listState.animateScrollToItem(index)
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items.forEach { entry ->
                item(key = entry.key) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        TranscriptRow(
                            item = entry,
                            harness = harness,
                            onOpenArtifact = onOpenArtifact,
                            onRetry = onRetry,
                            onStopSubAgent = onStopSubAgent,
                            onPermissionDecision = onPermissionDecision,
                            onReviewPermission = onReviewPermission,
                            answers = answers,
                            modifier = Modifier.widthIn(max = 760.dp),
                        )
                    }
                }
            }
            queued.forEachIndexed { index, prompt ->
                item(key = "queued-$index-${prompt.text}") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        QueuedMessage(prompt, Modifier.widthIn(max = 760.dp))
                    }
                }
            }
            transcript?.let { live ->
                item(key = "activity") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        val activity = live.activity
                        if (activity is AgentActivity.Starting) {
                            StartingCard(activity, Modifier.widthIn(max = 760.dp), stopped = stopped)
                        } else {
                            ActivityRow(
                                activity,
                                Modifier.widthIn(max = 760.dp),
                                waitingOn = live.pendingPermission?.ask,
                                stopped = stopped,
                            )
                        }
                    }
                }
            }
        }
        JumpToLatest(
            visible = remember(listState) {
                derivedStateOf { !listState.isNearEnd(slack = 1) && !listState.isScrollInProgress }
            }.value,
            busy = transcript?.isBusy == true,
            onClick = { scope.launch { listState.animateScrollToItem(total) } },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun JumpToLatest(
    visible: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 3.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) WorkingDot() else Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (busy) "Working" else "Latest", style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp)
            }
        }
    }
}

@Composable
internal fun WorkingDot(size: Dp = 9.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "working")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    StatusDot(MaterialTheme.colorScheme.primary.copy(alpha = pulse), size, modifier)
}

private fun TranscriptItem.holds(requestId: String): Boolean = when (this) {
    is TranscriptItem.Permission -> this.requestId == requestId
    is TranscriptItem.SubAgent -> items.any { it.holds(requestId) }
    else -> false
}

internal fun LazyListState.isNearEnd(slack: Int = 2): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return true
    return last >= info.totalItemsCount - 1 - slack
}

internal fun LazyListState.isFullyVisible(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
}

@Composable
private fun QueuedMessage(prompt: QueuedPrompt, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                prompt.attachments.forEach { attachment ->
                    AttachmentTile(attachment)
                    Spacer(Modifier.height(6.dp))
                }
                if (prompt.text.isNotBlank()) {
                    Text(prompt.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            if (prompt.heldForSignIn) "Waiting for you to sign in" else "Waiting for the computer",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun ConnectBanner(request: ConnectRequest, onConnect: () -> Unit, onDecline: () -> Unit) {
    Banner(
        tint = MaterialTheme.colorScheme.tertiary,
        icon = { Icon(Icons.AutoMirrored.Outlined.CallMerge, null, Modifier.size(17.dp)) },
        title = "Connect GitHub",
        body = request.reason,
        action = "Connect" to onConnect,
        secondary = "Not now" to onDecline,
    )
}

@Composable
private fun SignInBanner(onSignIn: () -> Unit) {
    Banner(
        tint = MaterialTheme.colorScheme.primary,
        icon = { Icon(Icons.Outlined.Lock, null, Modifier.size(17.dp)) },
        title = "Sign in to Claude",
        body = null,
        action = "Sign in" to onSignIn,
    )
}

@Composable
private fun TranscriptLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun NoSessionState(hasTasks: Boolean, onTour: () -> Unit) =
    EmptyState(if (hasTasks) "Pick a task" else "Start a task", onTour)

@Composable
private fun EmptyTranscriptState(harness: HarnessDescriptor?, onTour: () -> Unit) =
    EmptyState(harness?.let { "Nothing yet with ${it.name}" } ?: "Nothing yet", onTour)

@Composable
private fun EmptyState(title: String, onTour: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
            Icon(
                Icons.Outlined.Forum,
                contentDescription = null,
                modifier = Modifier.padding(16.dp).size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(22.dp))
        TourSuggestion(onTour)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Composer(
    enabled: Boolean,
    blockedReason: String?,
    placeholder: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    notice: String? = null,
    onNotice: (() -> Unit)? = null,
    mode: AgentPermissionMode = AgentPermissionMode.Ask,
    onModeChange: ((AgentPermissionMode) -> Unit)? = null,
    model: AgentModel = AgentModel.DEFAULT,
    onModelChange: ((AgentModel) -> Unit)? = null,
    attachments: List<Attachment> = emptyList(),
    onAttachPhoto: (() -> Unit)? = null,
    onAttachFile: (() -> Unit)? = null,
    onRemoveAttachment: (Attachment) -> Unit = {},
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var modeMenuOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    val canSend = enabled && blockedReason == null && (draft.isNotBlank() || attachments.isNotEmpty())
    fun submit() {
        if (!canSend) return
        onSend(draft.trim())
        draft = ""
    }

    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        (blockedReason ?: notice)?.let { reason ->
            Row(
                Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Bolt, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                if (blockedReason == null) onNotice?.let { TextButton(onClick = it) { Text("Show me") } }
            }
        }
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().widthIn(max = 760.dp).horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { attachment -> AttachmentChip(attachment) { onRemoveAttachment(attachment) } }
            }
            Text(
                "Once sent, a file stays in the box.",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(bottom = 8.dp),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        ) {
            Row(
                Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (onAttachPhoto != null || onAttachFile != null) {
                    Box {
                        IconButton(onClick = { attachMenuOpen = true }, enabled = enabled, modifier = Modifier.size(44.dp)) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "Attach something",
                                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        }
                        DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                            onAttachPhoto?.let { pick ->
                                DropdownMenuItem(
                                    text = { Text("Photo") },
                                    leadingIcon = { Icon(Icons.Outlined.Image, null) },
                                    onClick = { attachMenuOpen = false; pick() },
                                )
                            }
                            onAttachFile?.let { pick ->
                                DropdownMenuItem(
                                    text = { Text("File") },
                                    leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                    onClick = { attachMenuOpen = false; pick() },
                                )
                            }
                        }
                    }
                } else Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    enabled = enabled,
                    maxLines = 6,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                            inner()
                        }
                    },
                )
                if (onModelChange != null) {
                    ModelControl(
                        model = model,
                        open = modelMenuOpen,
                        onOpen = { modelMenuOpen = true },
                        onDismiss = { modelMenuOpen = false },
                        onPick = { modelMenuOpen = false; onModelChange(it) },
                    )
                }
                if (onModeChange != null) {
                    ModeControl(
                        mode = mode,
                        open = modeMenuOpen,
                        onOpen = { modeMenuOpen = true },
                        onDismiss = { modeMenuOpen = false },
                        onPick = { modeMenuOpen = false; onModeChange(it) },
                    )
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).combinedClickable(
                        enabled = canSend || onModeChange != null,
                        onClick = ::submit,
                        onLongClick = onModeChange?.let { { modeMenuOpen = true } },
                        onClickLabel = "Send",
                        onLongClickLabel = "Change what the agent asks about",
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Send",
                        tint = when {
                            canSend && mode != AgentPermissionMode.Ask -> modeTint(mode)
                            canSend -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (attachment.isImage) Icons.Outlined.Image else Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Close, "Remove ${attachment.name}", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModelControl(
    model: AgentModel,
    open: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (AgentModel) -> Unit,
) {
    Box {
        Row(
            Modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                model.label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.semantics { contentDescription = "Model: ${model.label}. ${model.summary}" },
            )
            Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
            AgentModel.entries.forEach { option ->
                DropdownMenuItem(
                    onClick = { onPick(option) },
                    leadingIcon = {
                        if (option == model) {
                            Icon(Icons.Outlined.Check, "in use", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        } else Spacer(Modifier.size(18.dp))
                    },
                    text = {
                        Column {
                            Text(option.label, fontSize = 14.sp)
                            Text(option.summary, style = MaterialTheme.typography.bodyMedium, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ModeControl(
    mode: AgentPermissionMode,
    open: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (AgentPermissionMode) -> Unit,
) {
    val unsupervised = mode != AgentPermissionMode.Ask
    val tint = if (unsupervised) modeTint(mode) else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Row(
            Modifier.height(44.dp).clip(RoundedCornerShape(14.dp))
                .background(if (unsupervised) tint.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(onClick = onOpen).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (unsupervised) {
                Icon(Icons.Filled.Warning, null, Modifier.size(13.dp), tint = tint)
                Spacer(Modifier.width(3.dp))
            }
            Icon(
                modeIcon(mode),
                contentDescription = "Permission: ${permissionModeLabel(mode)}" + if (unsupervised) ". Box is not asking before the agent acts." else "",
                modifier = Modifier.size(19.dp),
                tint = tint,
            )
            Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
            AgentPermissionMode.entries.forEach { option ->
                DropdownMenuItem(
                    onClick = { onPick(option) },
                    leadingIcon = {
                        Icon(
                            if (option == mode) Icons.Outlined.Check else modeIcon(option),
                            contentDescription = if (option == mode) "in use" else null,
                            modifier = Modifier.size(18.dp),
                            tint = if (option == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = {
                        Column {
                            Text(permissionModeLabel(option), fontSize = 14.sp)
                            Text(
                                when (option) {
                                    AgentPermissionMode.Ask -> "Every edit and command"
                                    AgentPermissionMode.AcceptEdits -> "Commands still stop"
                                    AgentPermissionMode.Everything -> "Nothing stops"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }
}

private fun modeIcon(mode: AgentPermissionMode) = when (mode) {
    AgentPermissionMode.Ask -> Icons.Outlined.Lock
    AgentPermissionMode.AcceptEdits -> Icons.Outlined.EditNote
    AgentPermissionMode.Everything -> Icons.Outlined.Bolt
}

@Composable
private fun modeTint(mode: AgentPermissionMode): Color = when (mode) {
    AgentPermissionMode.Ask -> MaterialTheme.colorScheme.onSurfaceVariant
    AgentPermissionMode.AcceptEdits -> MaterialTheme.colorScheme.tertiary
    AgentPermissionMode.Everything -> MaterialTheme.colorScheme.error
}
