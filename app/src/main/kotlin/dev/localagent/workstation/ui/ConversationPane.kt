package dev.localagent.workstation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.agent.AgentPermissionMode
import dev.localagent.workstation.agent.Artifact
import dev.localagent.workstation.agent.HarnessDescriptor
import dev.localagent.workstation.agent.PermissionDecision
import dev.localagent.workstation.agent.SessionConnection
import dev.localagent.workstation.agent.Transcript
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
    onReviewPermission: (() -> Unit)? = null,
    showComputerAction: Boolean = true,
    onSignIn: () -> Unit = {},
    onSetPermissionMode: (AgentPermissionMode) -> Unit = {},
) {
    val session = state.selectedSession
    val harness = state.harnesses.firstOrNull { it.id == session?.harnessId }
    val queued = state.queuedForSelected

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

        // Above the rest, and not dismissable: everything else here is about whether Box can work,
        // and this is about what it is allowed to do without asking. A user scrolling past a
        // booting-computer banner must not scroll past this one.
        PermissionModeBanner(state.permissionMode, onSetPermissionMode)

        // While the computer is down, its own banner is the true and actionable one. Showing the
        // transport's view as well says the same thing twice, in red, about a normal state.
        if (state.computerReady) ConnectionBanner(state.connection)
        ComputerBanner(state.runtimeState, onStartComputer)
        if (state.needsSignIn) SignInBanner(onSignIn)

        val nothingToShow = (state.transcript == null || state.transcript.items.isEmpty()) &&
            queued.isEmpty()

        Box(Modifier.weight(1f)) {
            when {
                session == null && queued.isEmpty() -> NoSessionState()
                // Not while the box is opening: nothing can arrive until it does, the banner
                // above already says so, and a spinner that has to run for three minutes is the
                // app pretending to work.
                state.computerReady && state.transcriptLoading && state.transcript == null &&
                    queued.isEmpty() -> TranscriptLoading()
                nothingToShow -> EmptyTranscriptState(harness)
                else -> TranscriptList(
                    transcript = state.transcript,
                    queued = queued,
                    harness = harness,
                    onOpenArtifact = onOpenArtifact,
                    onRetry = onStartComputer,
                    onStopSubAgent = onStopSubAgent,
                    onPermissionDecision = onPermissionDecision,
                    onReviewPermission = onReviewRequest,
                )
            }
        }

        val waiting = state.transcript?.pendingPermissions.orEmpty()
        Composer(
            enabled = state.harnesses.isNotEmpty(),
            blockedReason = when {
                waiting.size > 1 -> "Answer the ${waiting.size} requests above."
                waiting.isNotEmpty() -> "Answer the request above."
                // A booting computer is not a lost connection, and typing into one is supported:
                // the message waits. Only a session that dropped while the computer was up is a
                // reason to stop the user mid-thought.
                state.computerReady && state.connection is SessionConnection.Disconnected ->
                    "Connection lost."
                else -> null
            },
            placeholder = "Ask Box anything…",
            onSend = onSend,
            onReview = onReviewPermission,
            mode = state.permissionMode,
            onModeChange = onSetPermissionMode,
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
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to conversations")
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
                Icon(Icons.Outlined.MoreVert, contentDescription = "Session options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onCloseSession != null) {
                    DropdownMenuItem(
                        text = { Text("Close session") },
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

/**
 * What is being skipped, while it is being skipped.
 *
 * Not a chip in the corner and not a line in a menu nobody opens: an agent working unsupervised
 * looks identical to an agent with nothing to ask about, so the only honest place for this is the
 * same strip that says the computer is booting — present in every conversation, carrying the way
 * out, and coloured like the risk it describes.
 */
@Composable
private fun PermissionModeBanner(mode: AgentPermissionMode, onSet: (AgentPermissionMode) -> Unit) {
    if (mode == AgentPermissionMode.Ask) return
    Banner(
        tint = when (mode) {
            AgentPermissionMode.Everything -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.tertiary
        },
        icon = { Icon(Icons.Outlined.LockOpen, null, Modifier.size(17.dp)) },
        title = when (mode) {
            AgentPermissionMode.Everything -> "Approving everything"
            else -> "Accepting file edits"
        },
        body = "Box is not asking before the agent acts.",
        action = "Ask again" to { onSet(AgentPermissionMode.Ask) },
    )
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
        RuntimeState.Stopped, RuntimeState.Suspended -> Triple("Your box is closed", null, "Open")
        RuntimeState.Stopping, RuntimeState.Suspending -> Triple("Shutting down", null, null)
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
                    )
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
    queued: List<String>,
    harness: HarnessDescriptor?,
    onOpenArtifact: (Artifact) -> Unit,
    onRetry: () -> Unit,
    onStopSubAgent: (String) -> Unit,
    onPermissionDecision: (String, PermissionDecision) -> Unit,
    onReviewPermission: (String) -> Unit,
) {
    val items = transcript?.items.orEmpty()
    val listState = rememberLazyListState()
    val lastKey = items.lastOrNull()?.key
    val total = items.size + queued.size
    val scope = rememberCoroutineScope()
    // Following the agent is the default, but only while the user is actually down here. Reading
    // something ten cards back and being dragged to the bottom by an event they were not watching
    // is the transcript taking the conversation away from them mid-sentence.
    LaunchedEffect(lastKey, items.size, queued.size, transcript?.activity) {
        if (total > 0 && listState.isNearEnd()) listState.animateScrollToItem(total)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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
                            modifier = Modifier.widthIn(max = 760.dp),
                        )
                    }
                }
            }
            queued.forEachIndexed { index, text ->
                item(key = "queued-$index-$text") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        QueuedMessage(text, Modifier.widthIn(max = 760.dp))
                    }
                }
            }
            transcript?.let { live ->
                item(key = "activity") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        ActivityRow(live.activity, Modifier.widthIn(max = 760.dp))
                    }
                }
            }
        }
        JumpToLatest(
            // Tighter than the auto-scroll's own slack, so the affordance is gone by the time
            // following resumes rather than sitting there offering to do what already happens.
            visible = remember(listState) { derivedStateOf { !listState.isNearEnd(slack = 1) } }.value,
            onClick = { scope.launch { listState.animateScrollToItem(total) } },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        )
    }
}

/**
 * The way back down, and nothing more.
 *
 * It only exists while the user has scrolled away from the end, which is exactly when the
 * transcript stops following on its own — the pair is one behaviour. Quiet by design: the same
 * surface and outline as a tool card, because it is a way to move, not a thing that happened.
 */
@Composable
private fun JumpToLatest(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
                Icon(Icons.Outlined.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text("Latest", style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp)
            }
        }
    }
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
 * What the user typed, before the computer was awake enough to take it.
 *
 * Drawn as their own message but visibly unsent, because the alternative — showing nothing for the
 * three minutes a cold VM takes to boot — reads as the app having lost it. It disappears the moment
 * the harness echoes the prompt into the session log, so it is never a second copy of a real one.
 */
@Composable
private fun QueuedMessage(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            "Waiting for the computer",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
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

@Composable
private fun NoSessionState() = EmptyState("Pick a task")

@Composable
private fun EmptyTranscriptState(harness: HarnessDescriptor?) =
    EmptyState(harness?.let { "Nothing yet with ${it.name}" } ?: "Nothing yet")

/**
 * A symbol and a statement.
 *
 * The paragraphs that used to sit under these titles explained the product to someone already
 * inside it, next to a composer that explains itself.
 */
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
    blockedReason: String?,
    placeholder: String,
    onSend: (String) -> Unit,
    onReview: (() -> Unit)?,
    modifier: Modifier = Modifier,
    footer: Boolean = true,
    mode: AgentPermissionMode = AgentPermissionMode.Ask,
    /** Null where the setting has nowhere to go — the opening hero shares this composer. */
    onModeChange: ((AgentPermissionMode) -> Unit)? = null,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var modeMenuOpen by remember { mutableStateOf(false) }
    val canSend = enabled && blockedReason == null && draft.isNotBlank()
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
        blockedReason?.let { reason ->
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
                onReview?.let {
                    TextButton(onClick = it) { Text("Review") }
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        ) {
            Row(
                Modifier.padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp),
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
                        .padding(4.dp)
                        .size(40.dp)
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
        if (footer) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Box can make mistakes. Review all work.",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

/**
 * What the agent has to ask about, on the composer where the asking happens.
 *
 * Deliberately here rather than in the header's overflow menu, where it started: it is the answer to
 * "stop asking me about this", which is a thing people want *while* being asked, and a setting
 * nobody finds is a setting that turns into fatigue at the sheet instead. The label lives in the
 * menu; the trigger is an icon, because the composer's row is not where a sentence fits.
 */
@Composable
private fun ModeControl(
    mode: AgentPermissionMode,
    open: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (AgentPermissionMode) -> Unit,
) {
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                modeIcon(mode),
                contentDescription = "Permission: ${permissionModeLabel(mode)}",
                modifier = Modifier.size(17.dp),
                tint = if (mode == AgentPermissionMode.Ask) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    modeTint(mode)
                },
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
