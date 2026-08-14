package dev.localagent.workstation.ui

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.QueuedPrompt
import dev.localagent.workstation.agent.AgentActivity
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
    onAttachPhoto: (() -> Unit)? = null,
    onAttachFile: (() -> Unit)? = null,
    onRemoveAttachment: (Attachment) -> Unit = {},
) {
    val session = state.selectedSession
    val harness = state.harnesses.firstOrNull { it.id == session?.harnessId }
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
            showComputerAction = showComputerAction,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        /*
         * One strip, ranked — not a stack.
         *
         * Drawn one under another, two of these pushed the conversation a fifth of the way down a
         * 1384px pane with neither dismissable, and stacking them said they were equally urgent.
         * The order is what most stands between the user and what they came to do: no credential,
         * no computer, an agent stopped waiting to be answered, a dropped connection, a standing
         * setting. The first two are Box being unusable; the third is work that has halted.
         *
         * Each condition is spelled out rather than left to the banner's own early return: a
         * `when` that picks a branch which then draws nothing silently hides the one underneath.
         */
        val connectionTrouble = state.computerReady &&
            state.connection !is SessionConnection.Live &&
            state.connection !is SessionConnection.Ended
        val connectRequest = state.connectRequest?.takeIf { it.sessionId == state.selectedSessionId }
        when {
            state.needsSignIn -> SignInBanner(onSignIn)
            // While the computer is down, its own banner is the true and actionable one. Showing
            // the transport's view as well says the same thing twice, in red, about a normal state.
            showBoxState && state.runtimeState != RuntimeState.Ready ->
                ComputerBanner(state.runtimeState, onStartComputer)

            // An agent that has stopped and is waiting to be answered, for *this* conversation.
            // Above the reconnect below it because it is blocking real work and the user can act
            // on it, where a reconnect is transient and there is nothing to do but wait. Only for
            // the selected session: an agent in another task asking for an account is that task's
            // business until the user opens it.
            connectRequest != null ->
                ConnectBanner(connectRequest, onConnectGitHub, onDeclineConnection)

            connectionTrouble -> ConnectionBanner(state.connection)
        }

        val nothingToShow = (state.transcript == null || state.transcript.items.isEmpty()) &&
            queued.isEmpty()

        Box(Modifier.weight(1f)) {
            when {
                session == null && queued.isEmpty() -> NoSessionState(state.tasks.isNotEmpty())
                // Not while the box is opening: nothing can arrive until it does, the banner
                // above already says so, and a spinner that has to run for three minutes is the
                // app pretending to work.
                //
                // A closed box is the other case, and it is short: the session's log is being
                // fetched from `:computer`, which takes a bind and a file read. `Connecting` is
                // what says that is still in flight — every route out of it settles, so this can
                // never become the three-minute spinner above.
                (state.computerReady || state.connection == SessionConnection.Connecting) &&
                    state.transcriptLoading && state.transcript == null &&
                    queued.isEmpty() -> TranscriptLoading()
                nothingToShow -> EmptyTranscriptState(harness)
                else -> TranscriptList(
                    transcript = state.transcript,
                    queued = queued,
                    harness = harness,
                    listState = listState,
                    answers = answers,
                    onOpenArtifact = onOpenArtifact,
                    onRetry = onStartComputer,
                    onStopSubAgent = onStopSubAgent,
                    onPermissionDecision = { requestId, decision ->
                        // Answered is answered: the ticks have gone to the agent and holding a copy
                        // would only wait to be inherited by whatever reuses the id.
                        answers.forget(requestId)
                        onPermissionDecision(requestId, decision)
                    },
                    onReviewPermission = onReviewRequest,
                )
            }
        }

        val waiting = state.transcript?.pendingPermissions.orEmpty()
        /*
         * The way to the request from down here, and it is a scroll rather than a modal.
         *
         * Box used to raise a sheet over the conversation the moment anything was asked, which
         * took the keyboard down with it and gave it back afterwards — a request answered in the
         * middle of typing cost the user their draft's place twice. The card in the transcript is
         * already a complete decision, so this only has to *go* there. The sheet is still one tap
         * further in, on a permission's card, for a diff nobody would decide on from one line.
         */
        val waitingAction: (() -> Unit)? = waiting.firstOrNull()?.let { oldest ->
            {
                val index = state.transcript?.items.orEmpty().indexOfFirst { it.holds(oldest.requestId) }
                if (index >= 0) {
                    scope.launch { listState.animateScrollToItem(index) }
                } else {
                    // Nothing in the list to scroll to. The sheet is the only surface left, and
                    // it is the right one: this can only be a permission, since a question with
                    // no card is a question with no answer either.
                    onReviewRequest(oldest.requestId)
                }
            }
        }
        Composer(
            enabled = state.harnesses.isNotEmpty(),
            /*
             * Not a gate. A pending request used to switch send off, which was the sheet's
             * urgency left behind after the sheet: the field still took typing, so a thought went
             * in, send did nothing, and the only sign was a line of small text.
             *
             * Box already holds a message through a three-minute boot, and the guest holds this
             * one the same way — a prompt written while a tool call is blocked sits in the
             * harness's queue and is picked up the moment the turn moves. So the message goes,
             * and this says what is still waiting rather than standing in front of it.
             */
            // Named for what it is. "Request" is a permission's word, and an agent that stopped
            // to ask you something has not requested anything.
            notice = when {
                waiting.size > 1 -> "${waiting.size} things are waiting on you."
                waiting.firstOrNull()?.ask is PermissionAsk.Questions -> "A question is waiting on you."
                waiting.isNotEmpty() -> "A request is waiting on you."
                else -> null
            },
            onNotice = waitingAction,
            blockedReason = when {
                // A booting computer is not a lost connection, and typing into one is supported:
                // the message waits. Only a session that dropped while the computer was up is a
                // reason to stop the user mid-thought.
                state.computerReady && state.connection is SessionConnection.Disconnected ->
                    "Connection lost."
                else -> null
            },
            placeholder = "Ask Box anything…",
            onSend = onSend,
            mode = state.permissionMode,
            onModeChange = onSetPermissionMode,
            attachments = state.pendingAttachments,
            onAttachPhoto = onAttachPhoto,
            onAttachFile = onAttachFile,
            onRemoveAttachment = onRemoveAttachment,
        )
    }
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
        // The one place that is on screen whatever the transcript is doing, so it is where "this
        // agent is working" has to be said. Stop is the same statement with a way out attached:
        // there is nothing to stop when nothing is running, and its absence is what used to be
        // read — correctly, given what Box knew — as the agent being finished.
        if (busy) {
            WorkingDot(modifier = Modifier.padding(end = 2.dp))
            TextButton(onClick = onInterrupt) {
                Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stop")
            }
        }
        // Always, at every size. This used to be true only on a tablet-width window, which left the
        // one route to the machine on a phone buried in the menu below.
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

// ---------------------------------------------------------------------------
// Banners: "not ready yet" is a normal state in Box, not an edge case.
// ---------------------------------------------------------------------------

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

/** The words for each mode, in one place: the menu names it and the banner reports it. */
private fun permissionModeLabel(mode: AgentPermissionMode): String = when (mode) {
    AgentPermissionMode.Ask -> "Ask every time"
    AgentPermissionMode.AcceptEdits -> "Accept file edits"
    AgentPermissionMode.Everything -> "Approve everything"
}

@Composable
private fun ComputerBanner(runtimeState: RuntimeState, onStart: () -> Unit) {
    val banner: Triple<String, String?, String?> = when (runtimeState) {
        RuntimeState.Ready -> return
        // Measured on the Fold 7, not estimated: the guest is fully emulated under TCG.
        RuntimeState.Starting -> Triple(
            "Booting Debian",
            // The only banner that earns a second line: it is telling the user their message is
            // not lost, which is the question a three-minute wait raises.
            "Keep typing — Box sends when it’s ready.",
            null,
        )
        RuntimeState.Connecting -> Triple("Almost ready", null, null)
        is RuntimeState.Provisioning -> Triple("Setting up your box", null, null)
        RuntimeState.NotProvisioned -> Triple("No box yet", null, "Open")
        RuntimeState.Stopped -> Triple("Your box is closed", null, "Open")
        // Put away is not closed, and the difference is the whole reason it exists: everything is
        // still in there and it comes back in about a second.
        RuntimeState.Suspended -> Triple("Your box is paused", null, "Open")
        RuntimeState.Stopping -> Triple("Shutting down", null, null)
        RuntimeState.Suspending -> Triple("Pausing your box", null, null)
        is RuntimeState.Failed -> Triple("The computer couldn’t start", runtimeState.reason.message, "Try again")
    }
    val (title, body, action) = banner
    Banner(
        tint = if (runtimeState is RuntimeState.Failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.tertiary
        },
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
    /** A second, quieter answer. Only for banners where declining is a real thing to say. */
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
                        // A caption, and the guest trims it to one. Bounded here as well because
                        // this text is written by a model and the banner sits above the composer:
                        // an unbounded body pushes the thing somebody is typing into off screen.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            secondary?.let { (label, onClick) ->
                TextButton(onClick = onClick) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick) { Text(label) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Transcript
// ---------------------------------------------------------------------------

@Composable
private fun TranscriptList(
    transcript: Transcript?,
    queued: List<QueuedPrompt>,
    harness: HarnessDescriptor?,
    listState: LazyListState,
    answers: AnswerStore,
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
    // Following the agent is the default, but only while the user is actually down here. Reading
    // something ten cards back and being dragged to the bottom by an event they were not watching
    // is the transcript taking the conversation away from them mid-sentence.
    LaunchedEffect(lastKey, items.size, queued.size, transcript?.activity) {
        if (total > 0 && listState.isNearEnd()) listState.animateScrollToItem(total)
    }

    /*
     * The one exception to leaving a scrolled-back reader alone: the request they have to answer.
     *
     * It is the whole reason the sheet no longer opens by itself. A modal followed the user
     * anywhere, at the cost of taking the keyboard down and covering the conversation; this does
     * the same job by bringing them to the card instead. It fires when the oldest unanswered
     * request changes, which is both moments that matter — one arriving while the transcript is
     * scrolled back, and the *next* one coming up as each is answered — and it stays quiet when
     * the card is already fully on screen, so answering three in a row that happen to be visible
     * together does not jerk the list once per tap.
     */
    val nextRequest = transcript?.pendingPermissions?.firstOrNull()?.requestId
    LaunchedEffect(nextRequest, items.size) {
        val requestId = nextRequest ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.holds(requestId) }
        if (index >= 0 && !listState.isFullyVisible(index)) listState.animateScrollToItem(index)
    }

    /*
     * A stack, with the pill floating over the transcript.
     *
     * It had its own lane for a while, and that cost more than it saved: a strip of the pane
     * appearing and disappearing shoved every line up and back down as the user scrolled, and it
     * read as furniture rather than a control. Reserving space at the end of the list is the same
     * price paid more quietly — a permanent band of empty pane under the newest message, bought to
     * protect a spot the pill is never in. It is only up while the transcript is scrolled *away*
     * from its end, so the newest message is the one thing it cannot land on.
     */
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
                            StartingCard(activity, Modifier.widthIn(max = 760.dp))
                        } else {
                            ActivityRow(
                                activity,
                                Modifier.widthIn(max = 760.dp),
                                waitingOn = live.pendingPermission?.ask,
                            )
                        }
                    }
                }
            }
        }
        JumpToLatest(
            // Tighter than the auto-scroll's own slack, so the affordance is gone by the time
            // following resumes rather than sitting there offering to do what already happens.
            //
            // And never while the list is actually moving. Floating is what a pill like this is,
            // and the price of floating is that it covers a strip of whatever is under it — which
            // is worst mid-flick, when it sits still in the middle of text that is streaming past
            // and reads as a smudge on the glass. Gone while the finger is down, back when things
            // settle, which is also the only moment anyone wants to press it.
            visible = remember(listState) {
                derivedStateOf { !listState.isNearEnd(slack = 1) && !listState.isScrollInProgress }
            }.value,
            // Scrolled away from the end is exactly when a working agent is invisible: the
            // activity line trails the transcript, so it is off the bottom of the screen. The
            // pill is on screen by definition, so it is where that fact can still be told.
            busy = transcript?.isBusy == true,
            onClick = { scope.launch { listState.animateScrollToItem(total) } },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }
}

/**
 * The way back down, and — while the agent is working — the only sign of it that is on screen.
 *
 * It exists while the user has scrolled away from the end, which is exactly when the transcript
 * stops following on its own; the pair is one behaviour. Quiet by design: the same surface and
 * outline as a tool card, because it is a way to move, not a thing that happened. It floats rather
 * than taking a lane or reserving room at the end of the list — see the stack in [TranscriptList]
 * for why both of those cost more than they save.
 *
 * The dot is the working indicator for a scrolled-back reader. The activity line trails the
 * transcript and is therefore off screen precisely when this is up, so an agent halfway through a
 * long job looked identical to one that had finished and gone quiet.
 */
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
                if (busy) {
                    WorkingDot()
                } else {
                    Icon(
                        Icons.Outlined.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    if (busy) "Working" else "Latest",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * "Something is happening in here", in one pulsing dot.
 *
 * The same beat as the activity line at the end of the transcript, so the header, the pill and the
 * line under the conversation are visibly one fact rather than three separate claims.
 */
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

/**
 * Whether this row is where a given request is answered — including inside a sub-agent's card.
 *
 * A delegate's questions and permissions are drawn nested in the card that spawned it, so the row
 * to bring on screen is the sub-agent's, not one of its own. Recursive because a sub-agent can
 * send a sub-agent, and the answer is still "whichever top-level row contains it".
 */
private fun TranscriptItem.holds(requestId: String): Boolean = when (this) {
    is TranscriptItem.Permission -> this.requestId == requestId
    is TranscriptItem.SubAgent -> items.any { it.holds(requestId) }
    else -> false
}

/**
 * Whether the list is close enough to its end that new arrivals should still pull it along.
 *
 * Measured in items with slack, not in pixels, because the answer is needed *after* the arrival
 * that prompted the question: at the bottom of a live transcript the last laid-out row is
 * routinely one or two behind the newest one. Anything further back than that was a deliberate
 * scroll by a person, and is left alone.
 */
internal fun LazyListState.isNearEnd(slack: Int = 2): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return true
    return last >= info.totalItemsCount - 1 - slack
}

/**
 * Whether one row is on screen *whole*, which is the question a card with buttons on it raises.
 *
 * Deliberately stricter than "visible": a permission card whose Allow and Deny are an inch below
 * the fold counts as not there, and is worth scrolling to. Unlaid-out rows answer false, so a
 * request far up the scrollback is brought down rather than assumed to be fine.
 */
internal fun LazyListState.isFullyVisible(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= info.viewportStartOffset &&
        item.offset + item.size <= info.viewportEndOffset
}

/**
 * What the user typed, before the computer was awake enough to take it.
 *
 * Drawn as their own message but visibly unsent, because the alternative — showing nothing for the
 * three minutes a cold VM takes to boot — reads as the app having lost it. It disappears the moment
 * the harness echoes the prompt into the session log, so it is never a second copy of a real one.
 */
@Composable
private fun QueuedMessage(prompt: QueuedPrompt, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            // The same tiles the sent turn draws, so a message waiting here looks like the message
            // it is about to become. It matters most for a picture sent with no words, which is a
            // whole message on its own — and which, held for a sign-in, would otherwise sit on
            // screen as an empty bubble for as long as the user took to sign in.
            Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                prompt.attachments.forEach { attachment ->
                    AttachmentTile(attachment)
                    Spacer(Modifier.height(6.dp))
                }
                if (prompt.text.isNotBlank()) {
                    Text(
                        prompt.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            // Two different promises, and the difference is who is being waited on. One resolves
            // itself; the other is waiting on the person reading it, and has to say so.
            if (prompt.heldForSignIn) "Waiting for you to sign in" else "Waiting for the computer",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

/**
 * An agent holding its turn open, waiting for an account only the person can grant.
 *
 * It stays until it is answered rather than until it is dismissed, because behind it a tool call
 * is genuinely blocked — the SDK pauses one indefinitely, which is what lets the same turn carry
 * on with the clone afterwards. So closing the sheet does not answer it, and the only ways out are
 * the two written here.
 *
 * The agent's own reason is the body. It knows what it was in the middle of, and "to clone
 * garfbargle/box" is a better sentence than anything this file could have written in advance.
 */
@Composable
private fun ConnectBanner(
    request: ConnectRequest,
    onConnect: () -> Unit,
    onDecline: () -> Unit,
) {
    Banner(
        tint = MaterialTheme.colorScheme.tertiary,
        icon = { Icon(Icons.AutoMirrored.Outlined.CallMerge, null, Modifier.size(17.dp)) },
        title = "Connect GitHub",
        body = request.reason,
        action = "Connect" to onConnect,
        secondary = "Not now" to onDecline,
    )
}

/**
 * The one thing the agent cannot work around by itself. Quiet, and never a blocking wall: the
 * conversation stays usable, because a user who is mid-thought should be able to finish typing.
 */
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
    // A spinner says "loading". Writing it underneath as well was the app reading itself aloud.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/**
 * Nothing selected — which is two different situations, and it used to say the same thing about
 * both.
 *
 * "Pick a task" is instruction the reader cannot follow when there is nothing in the list, which
 * is exactly the state a new box is in. The composer under it is live and does work in both cases:
 * typing with nothing selected opens a task and sends into it (see `BoxViewModel.sendMessage`), so
 * the second title is a description of what is already true rather than a new affordance.
 */
@Composable
private fun NoSessionState(hasTasks: Boolean) =
    EmptyState(if (hasTasks) "Pick a task" else "Start a task")

@Composable
private fun EmptyTranscriptState(harness: HarnessDescriptor?) =
    EmptyState(harness?.let { "Nothing yet with ${it.name}" } ?: "Nothing yet")

/** A symbol and a statement. The composer underneath explains itself. */
@Composable
private fun EmptyState(title: String) {
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
    }
}

// ---------------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------------

/**
 * Where anything is said to an agent.
 *
 * Shared with the opening hero rather than copied into it: the first thing typed on a phone whose
 * box is still booting goes through exactly the same path as the thousandth, queue and all, and a
 * second text field would have been a second set of rules about when sending is allowed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Composer(
    enabled: Boolean,
    /** Something that genuinely stops a message going: send is off while this is set. */
    blockedReason: String?,
    placeholder: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Something waiting that does not stop a message going. Drawn in the same strip and worded
     * the same way, and deliberately *not* a second kind of block: the difference between the two
     * is whether tapping send does anything.
     */
    notice: String? = null,
    onNotice: (() -> Unit)? = null,
    mode: AgentPermissionMode = AgentPermissionMode.Ask,
    /** Null where the setting has nowhere to go — the opening hero shares this composer. */
    onModeChange: ((AgentPermissionMode) -> Unit)? = null,
    /** What the user has picked or shared in and not sent yet. Drawn above the text. */
    attachments: List<Attachment> = emptyList(),
    /**
     * Null where nothing can be attached. Two entries rather than one because Android has two
     * pickers and they are not interchangeable: the photo picker needs no storage permission and
     * shows the camera roll, while the document picker reaches everything else. Offering only one
     * would mean either asking for a permission to show pictures, or making someone find a
     * screenshot through a file tree.
     */
    onAttachPhoto: (() -> Unit)? = null,
    onAttachFile: (() -> Unit)? = null,
    onRemoveAttachment: (Attachment) -> Unit = {},
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var modeMenuOpen by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    // A picture on its own is a message: "look at this" is often the whole thought, and asking for
    // a word alongside it would be Box requiring something it does not need.
    val canSend = enabled && blockedReason == null && (draft.isNotBlank() || attachments.isNotEmpty())
    fun submit() {
        if (!canSend) return
        onSend(draft.trim())
        draft = ""
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        (blockedReason ?: notice)?.let { reason ->
            Row(
                Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                // "Show me" rather than "Review": it scrolls the transcript to the card, and a
                // word that promises a closer look would be describing the sheet this replaced.
                if (blockedReason == null) {
                    onNotice?.let {
                        TextButton(onClick = it) { Text("Show me") }
                    }
                }
            }
        }
        if (attachments.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { attachment ->
                    AttachmentChip(attachment) { onRemoveAttachment(attachment) }
                }
            }
            // Said here because there is no unsend, and this is the last moment it is true. A file
            // the user later deletes on the phone leaves the box's copy where it is — the sync
            // stops carrying it out, and nothing reaches in to remove it.
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
            // Bottom rather than centre because the field grows: past one line the controls
            // belong beside the line being typed, not floating in the middle of the block. On the
            // one line that is nearly always there, the sizes below make bottom *be* centre —
            // every control is 44.dp and the field's padding brings it to the same height.
            Row(
                Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // The second door. The share sheet wins when the file exists first and the wish to
                // send it comes second; this wins when the conversation exists first — which is
                // most of the time, and is the one that needs no app switch to reach.
                if (onAttachPhoto != null || onAttachFile != null) {
                    Box {
                        IconButton(
                            onClick = { attachMenuOpen = true },
                            enabled = enabled,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "Attach something",
                                tint = if (enabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                },
                            )
                        }
                        DropdownMenu(
                            expanded = attachMenuOpen,
                            onDismissRequest = { attachMenuOpen = false },
                        ) {
                            onAttachPhoto?.let { pick ->
                                DropdownMenuItem(
                                    text = { Text("Photo") },
                                    leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                    onClick = { attachMenuOpen = false; pick() },
                                )
                            }
                            onAttachFile?.let { pick ->
                                DropdownMenuItem(
                                    text = { Text("File") },
                                    leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                                    onClick = { attachMenuOpen = false; pick() },
                                )
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    enabled = enabled,
                    maxLines = 6,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    placeholder,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 15.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (onModeChange != null) {
                    ModeControl(
                        mode = mode,
                        open = modeMenuOpen,
                        onOpen = { modeMenuOpen = true },
                        onDismiss = { modeMenuOpen = false },
                        onPick = {
                            modeMenuOpen = false
                            onModeChange(it)
                        },
                    )
                }
                // Long-press is the shortcut, not the affordance: the control beside it is what
                // makes the mode findable, and a gesture nobody discovers cannot be the only way in.
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .combinedClickable(
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

/**
 * One waiting attachment, with the way to take it back off.
 *
 * Named for what the user called it rather than for the stamped name it was given on disk. The
 * stamp exists so two screenshots never argue with each other inside the shared folder; showing it
 * here would be Box explaining its own filing to someone who just picked a photo.
 */
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
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Remove ${attachment.name}",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What the agent has to ask about, on the composer where the asking happens.
 *
 * Not in the header's overflow menu, where it started: "stop asking me about this" is a thought
 * people have *while* being asked, and a setting nobody finds turns into fatigue at the sheet
 * instead. The label lives in the menu; the trigger is an icon, because the composer's row is not
 * where a sentence fits.
 *
 * It is also the *only* place unsupervised mode is shown, and the caution sign beside the icon is
 * what carries that. A banner said it in a whole strip above the composer, forever — but nothing
 * turns this on by accident, so once told, the user needs to see which mode is in force at a
 * glance, not to be told again in the space the conversation was using.
 */
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
            Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                // Tinted only when it has something to say. In Ask it is a plain glyph on the
                // composer's own surface, which is what a default should look like.
                .background(if (unsupervised) tint.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (unsupervised) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = tint,
                )
                Spacer(Modifier.width(3.dp))
            }
            Icon(
                modeIcon(mode),
                contentDescription = "Permission: ${permissionModeLabel(mode)}" +
                    if (unsupervised) ". Box is not asking before the agent acts." else "",
                modifier = Modifier.size(19.dp),
                tint = tint,
            )
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
            AgentPermissionMode.entries.forEach { option ->
                DropdownMenuItem(
                    onClick = { onPick(option) },
                    // A check on the one in force rather than a switch per row: these are three
                    // answers to one question, and only one of them can be true.
                    leadingIcon = {
                        Icon(
                            if (option == mode) Icons.Outlined.Check else modeIcon(option),
                            contentDescription = if (option == mode) "in use" else null,
                            modifier = Modifier.size(18.dp),
                            tint = if (option == mode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    text = {
                        Column {
                            Text(permissionModeLabel(option), fontSize = 14.sp)
                            Text(
                                // Each says what it stops stopping, because that is the part
                                // someone is agreeing to.
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

/** The banner's colours, so the two places this state appears agree about how loud it is. */
@Composable
private fun modeTint(mode: AgentPermissionMode): Color = when (mode) {
    AgentPermissionMode.Ask -> MaterialTheme.colorScheme.onSurfaceVariant
    AgentPermissionMode.AcceptEdits -> MaterialTheme.colorScheme.tertiary
    AgentPermissionMode.Everything -> MaterialTheme.colorScheme.error
}
