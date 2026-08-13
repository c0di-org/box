package dev.localagent.workstation.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BuildConfig
import dev.localagent.workstation.agent.GuestAuth

/**
 * What a runtime state is called, and in what colour.
 *
 * This used to carry an eyebrow, a headline and a paragraph for each of ten states — forty pieces
 * of copy explaining a virtual machine to someone who wanted to know whether they could use it.
 * The answer is a word and a colour.
 */
data class StatePresentation(val shortLabel: String, val color: Color)

@Composable
fun statePresentation(state: RuntimeState): StatePresentation = when (state) {
    RuntimeState.NotProvisioned -> StatePresentation("Not set up", MaterialTheme.colorScheme.onSurfaceVariant)
    is RuntimeState.Provisioning -> StatePresentation("Setting up", MaterialTheme.colorScheme.primary)
    RuntimeState.Stopped -> StatePresentation("Closed", MaterialTheme.colorScheme.onSurfaceVariant)
    RuntimeState.Starting -> StatePresentation("Booting", MaterialTheme.colorScheme.primary)
    RuntimeState.Connecting -> StatePresentation("Almost ready", MaterialTheme.colorScheme.primary)
    RuntimeState.Ready -> StatePresentation("Open", MaterialTheme.colorScheme.primary)
    RuntimeState.Stopping -> StatePresentation("Closing", MaterialTheme.colorScheme.tertiary)
    RuntimeState.Suspending -> StatePresentation("Pausing", MaterialTheme.colorScheme.tertiary)
    RuntimeState.Suspended -> StatePresentation("Paused", MaterialTheme.colorScheme.tertiary)
    is RuntimeState.Failed -> StatePresentation("Didn’t open", MaterialTheme.colorScheme.error)
}

@Composable
fun StatusPill(state: RuntimeState, modifier: Modifier = Modifier) {
    val presentation = statePresentation(state)
    Surface(
        color = presentation.color.copy(alpha = 0.13f),
        contentColor = presentation.color,
        shape = CircleShape,
        modifier = modifier.semantics {
            contentDescription = "Computer status: ${presentation.shortLabel}"
        },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(presentation.color, 7.dp)
            Spacer(Modifier.width(7.dp))
            Text(presentation.shortLabel, style = MaterialTheme.typography.labelLarge, fontSize = 12.sp)
        }
    }
}

@Composable
fun RuntimeGlyph(state: RuntimeState, modifier: Modifier = Modifier) {
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

/**
 * Shown wherever a tool needs the machine and the machine is not there yet.
 *
 * Deliberately calm and deliberately short: the box takes about three minutes and Android can
 * reclaim it, so this is a normal part of the day, not an incident to be written up.
 */
@Composable
fun RuntimeGate(state: RuntimeState, onOpenBox: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RuntimeGlyph(state, Modifier.size(64.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                when (state) {
                    RuntimeState.Starting, RuntimeState.Connecting, is RuntimeState.Provisioning ->
                        "Opening your box"
                    RuntimeState.Stopping -> "Closing"
                    RuntimeState.Suspending -> "Pausing your box"
                    // Not "closed". A put-away box still has everything in it, and comes back in
                    // about a second — which is a different offer from the one below it.
                    RuntimeState.Suspended -> "Your box is paused"
                    is RuntimeState.Failed -> "Your box didn’t open"
                    else -> "Your box is closed"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            when (state) {
                RuntimeState.NotProvisioned, RuntimeState.Stopped -> {
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onOpenBox) { Text("Open your box") }
                }
                RuntimeState.Suspended -> {
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onOpenBox) { Text("Pick up where you left off") }
                }
                is RuntimeState.Failed -> {
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onOpenBox) { Text("Try again") }
                }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    state: RuntimeState,
    signIn: GuestAuth.State,
    onDismiss: () -> Unit,
    onOpenBox: () -> Unit,
    onPutAway: () -> Unit,
    onStop: () -> Unit,
    onSignIn: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val presentation = statePresentation(state)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth().widthIn(max = 620.dp)) {
                Text("Your box", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(presentation.color, 8.dp)
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
                // What each line costs the reader, not what it costs the runtime: "QEMU TCG" and
                // "vCPU" name the implementation, and the sheet is answering "what have I got".
                DiagnosticRow("Machine", "Emulated • 2 processors • 1 GB memory")
                DiagnosticRow("Workspace", "/workspace • kept between tasks")
                DiagnosticRow("Connection", "Private, on this phone")
                DiagnosticRow("Network", "Outgoing only, through your phone")
                SignedInRow(signIn, onSignIn)
                Spacer(Modifier.height(20.dp))
                when (state) {
                    RuntimeState.NotProvisioned, RuntimeState.Stopped ->
                        Button(onClick = onOpenBox, Modifier.fillMaxWidth()) { Text("Open your box") }
                    RuntimeState.Suspended ->
                        Button(onClick = onOpenBox, Modifier.fillMaxWidth()) {
                            Text("Pick up where you left off")
                        }
                    // Two endings, and the primary one is the cheap one. Putting the box away
                    // keeps everything and reopens in about a second; closing it means the next
                    // box boots from nothing, which on an emulated ARM64 machine is minutes.
                    RuntimeState.Ready -> {
                        Button(onClick = onPutAway, Modifier.fillMaxWidth()) { Text("Pause your box") }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onStop, Modifier.fillMaxWidth()) { Text("Close your box") }
                    }
                    RuntimeState.Starting, RuntimeState.Connecting ->
                        OutlinedButton(onClick = onStop, Modifier.fillMaxWidth()) { Text("Close your box") }
                    RuntimeState.Stopping, RuntimeState.Suspending -> Unit
                    is RuntimeState.Failed -> Button(onClick = onOpenBox, Modifier.fillMaxWidth()) { Text("Try again") }
                    is RuntimeState.Provisioning -> Unit
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    // The flavour is only worth a word when it is the experimental one, and the
                    // suffix it stamps on the version is never worth one. On every shipping phone
                    // "0.1.0-stock • Stock runtime" was a build detail wearing a user's clothes.
                    "Box ${BuildConfig.VERSION_NAME.substringBefore('-')}" +
                        if (BuildConfig.FLAVOR == "avf") " • Experimental runtime" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Who the box is signed in as, and the way to change it.
 *
 * The only route to signing in used to be a banner inside a conversation, which is fine for the
 * first time — you are on your way to ask an agent for something — and useless every time after:
 * a credential that expired leaves someone on the home screen with nothing to press. This sheet is
 * what "your box" means, and the account it works as is part of that.
 */
@Composable
private fun SignedInRow(signIn: GuestAuth.State, onSignIn: () -> Unit) {
    val value = when (signIn) {
        is GuestAuth.State.SignedIn -> signIn.account ?: "Signed in"
        GuestAuth.State.Checking -> "Checking…"
        GuestAuth.State.Starting, is GuestAuth.State.AwaitingCode -> "Signing in…"
        is GuestAuth.State.Failed -> "Sign-in didn’t finish"
        GuestAuth.State.SignedOut -> "Not signed in"
        GuestAuth.State.Unknown -> "Not checked yet"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Claude",
            Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(0.58f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer {
                    Text(
                        value,
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (signIn !is GuestAuth.State.SignedIn) {
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onSignIn, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Sign in")
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
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
