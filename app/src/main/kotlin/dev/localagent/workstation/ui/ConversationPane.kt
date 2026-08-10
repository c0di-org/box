package dev.localagent.workstation.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Stop
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.agent.Artifact
import dev.localagent.workstation.agent.HarnessDescriptor
import dev.localagent.workstation.agent.SessionConnection
import dev.localagent.workstation.agent.Transcript

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
    onOpenArtifact: (Artifact) -> Unit,
    onStartComputer: () -> Unit,
    onOpenComputer: () -> Unit,
    onCloseSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    onReviewPermission: (() -> Unit)? = null,
    showComputerAction: Boolean = false,
) {
    val session = state.selectedSession
    val harness = state.harnesses.firstOrNull { it.id == session?.harnessId }

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

        ConnectionBanner(state.connection)
        ComputerBanner(state.runtimeState, onStartComputer)

        Box(Modifier.weight(1f)) {
            when {
                session == null -> NoSessionState()
                state.transcriptLoading && state.transcript == null -> TranscriptLoading()
                state.transcript == null || state.transcript.items.isEmpty() ->
                    EmptyTranscriptState(harness)
                else -> TranscriptList(
                    transcript = state.transcript,
                    harness = harness,
                    onOpenArtifact = onOpenArtifact,
                    onRetry = onStartComputer,
                )
            }
        }

        Composer(
            enabled = state.harnesses.isNotEmpty(),
            blockedReason = when {
                state.transcript?.pendingPermission != null -> "Answer the request above to continue."
                state.connection is SessionConnection.Disconnected ->
                    "Box lost the connection to this session."
                else -> null
            },
            placeholder = if (session == null) "Start a conversation…" else "Ask Box anything…",
            onSend = onSend,
            onReview = onReviewPermission,
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
        if (showComputerAction) {
            IconButton(onClick = onOpenComputer) {
                Icon(Icons.Outlined.Computer, contentDescription = "Open the agent's computer")
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Session options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Open computer") },
                    leadingIcon = { Icon(Icons.Outlined.Computer, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onOpenComputer()
                    },
                )
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
            body = if (connection.retrying) "Reconnecting…" else "This transcript is the last thing Box saw.",
        )

        SessionConnection.Connecting -> Banner(
            tint = MaterialTheme.colorScheme.primary,
            icon = { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) },
            title = "Connecting to the session",
            body = null,
        )

        SessionConnection.Live, SessionConnection.Ended -> Unit
    }
}

@Composable
private fun ComputerBanner(runtimeState: RuntimeState, onStart: () -> Unit) {
    val banner: Triple<String, String?, String?> = when (runtimeState) {
        RuntimeState.Ready -> return
        RuntimeState.Starting -> Triple("The computer is booting", "About 90 seconds. You can keep typing.", null)
        RuntimeState.Connecting -> Triple("Almost ready", "Waiting for the private control channel.", null)
        is RuntimeState.Provisioning -> Triple("Setting up the computer", "Preparing the Linux workspace.", null)
        RuntimeState.NotProvisioned -> Triple("No computer yet", "Agents need a Linux box to work in.", "Set up")
        RuntimeState.Stopped, RuntimeState.Suspended ->
            Triple("The computer is off", "Start it before an agent runs anything.", "Start")
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
    transcript: Transcript,
    harness: HarnessDescriptor?,
    onOpenArtifact: (Artifact) -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    val lastKey = transcript.items.lastOrNull()?.key
    LaunchedEffect(lastKey, transcript.items.size, transcript.activity) {
        if (transcript.items.isNotEmpty()) {
            listState.animateScrollToItem(transcript.items.size)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            transcript.items.forEach { entry ->
                item(key = entry.key) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        TranscriptRow(
                            item = entry,
                            harness = harness,
                            onOpenArtifact = onOpenArtifact,
                            onRetry = onRetry,
                            modifier = Modifier.widthIn(max = 760.dp),
                        )
                    }
                }
            }
            item(key = "activity") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    ActivityRow(transcript.activity, Modifier.widthIn(max = 760.dp))
                }
            }
        }
    }
}

@Composable
private fun TranscriptLoading() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            "Loading the transcript",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoSessionState() {
    EmptyState(
        title = "Pick a conversation",
        body = "Every agent you run gets its own thread. Choose one on the left, or start a new " +
            "conversation and Box will spin up a fresh session for it.",
    )
}

@Composable
private fun EmptyTranscriptState(harness: HarnessDescriptor?) {
    EmptyState(
        title = harness?.let { "Nothing yet with ${it.name}" } ?: "Nothing yet",
        body = "Tell the agent what you want done. It has a real Linux computer to work in, and " +
            "Box will ask you before it changes anything.",
    )
}

@Composable
private fun EmptyState(title: String, body: String) {
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
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 380.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------------

@Composable
private fun Composer(
    enabled: Boolean,
    blockedReason: String?,
    placeholder: String,
    onSend: (String) -> Unit,
    onReview: (() -> Unit)?,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val canSend = enabled && blockedReason == null && draft.isNotBlank()
    fun submit() {
        if (!canSend) return
        onSend(draft.trim())
        draft = ""
    }

    Column(
        Modifier
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
                IconButton(onClick = ::submit, enabled = canSend) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Box can make mistakes. Review all work.",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}
