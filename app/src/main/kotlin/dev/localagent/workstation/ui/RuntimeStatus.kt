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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import dev.localagent.runtime.qemu.GuestSizing
import dev.localagent.runtime.qemu.GuestSizingChoices
import dev.localagent.workstation.BuildConfig
import dev.localagent.workstation.agent.GitHubAuth
import dev.localagent.workstation.agent.GuestAuth

/**
 * What a runtime state is called, and in what colour.
 *
 * Someone asking about the machine wants to know whether they can use it, and the answer to that
 * is a word and a colour rather than a paragraph explaining a virtual machine.
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
        color = if (state == RuntimeState.Ready) BoxVoid else MaterialTheme.colorScheme.surface,
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
    github: GitHubAuth.State,
    onDismiss: () -> Unit,
    onOpenBox: () -> Unit,
    onPutAway: () -> Unit,
    onStop: () -> Unit,
    onSignIn: () -> Unit,
    onGitHub: () -> Unit,
    openFaster: Boolean = true,
    onSetOpenFaster: (Boolean) -> Unit = {},
    guestSizing: GuestSizing = GuestSizing.DEFAULT,
    guestSizingChoices: GuestSizingChoices = GuestSizingChoices(
        memoryMb = listOf(guestSizing.memoryMb),
        processors = listOf(guestSizing.processors),
    ),
    onSetGuestSizing: (GuestSizing) -> Unit = {},
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
                DiagnosticRow("Machine", "Emulated • " + machineSummary(guestSizing))
                DiagnosticRow("Workspace", "/workspace • kept between tasks")
                DiagnosticRow("Connection", "Private, on this phone")
                DiagnosticRow("Network", "Outgoing only, through your phone")
                SignedInRow(signIn, onSignIn)
                GitHubRow(github, onGitHub)
                OpenFasterRow(openFaster, onSetOpenFaster)
                MachineSizeRow(guestSizing, guestSizingChoices, openFaster, onSetGuestSizing)
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

/**
 * What this box can reach on GitHub, and the way to change it.
 *
 * It sits beside the Claude row because they are the same kind of fact — who the box works as —
 * but it says a different thing on purpose. An account name answers "who"; the repository count
 * answers the question people actually have about an agent with a credential, which is "how much
 * of my code can it touch". So the number is the value, and the name is the caption.
 */
@Composable
private fun GitHubRow(github: GitHubAuth.State, onGitHub: () -> Unit) {
    val value = when (github) {
        is GitHubAuth.State.Connected -> when {
            github.needsRepositories -> "No repositories yet"
            // Connected, and the count could not be refreshed. "@codi" is the part that was never
            // in doubt; a number nobody just confirmed is not.
            github.stale -> "Connected"
            github.repositories != null ->
                "${github.repositories} ${if (github.repositories == 1) "repository" else "repositories"}"
            else -> "@${github.login}"
        }
        GitHubAuth.State.Checking -> "Checking…"
        GitHubAuth.State.Starting,
        is GitHubAuth.State.AwaitingApproval,
        is GitHubAuth.State.ChoosingRepositories,
        -> "Connecting…"
        is GitHubAuth.State.Failed -> "Didn’t connect"
        GitHubAuth.State.Unconfigured -> "Not set up in this build"
        GitHubAuth.State.Disconnected -> "Not connected"
        GitHubAuth.State.Unknown -> "Not checked yet"
    }
    // Held as the state itself rather than a boolean, so the caption below can read the count off
    // it without a second instance check the compiler can see through.
    val settled = (github as? GitHubAuth.State.Connected)?.takeIf { !it.needsRepositories }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "GitHub",
            Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(0.58f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f, fill = false)) {
                    SelectionContainer {
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    // Only when the line above is a count: "3 repositories" is the answer, and
                    // whose they are is the footnote rather than a second row.
                    //
                    // A stale row says so instead. The count is the last one anybody saw and the
                    // box could not be asked for a fresh one — a phone in a tunnel, not a revoked
                    // credential — and printing the old number flat would be asserting something
                    // this row does not currently know.
                    if (settled?.stale == true) {
                        Text(
                            "@${settled.login} · not checked just now",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (settled?.repositories != null) {
                        Text(
                            "@${settled.login}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onGitHub, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (settled != null) "Manage" else "Connect")
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/**
 * The one switch on this sheet: whether Box keeps a saved copy of the guest.
 *
 * Phrased as what the user gets and what it costs, in that order, because both halves are real.
 * A saved box reopens in about a second against the 95–120 s a cold boot takes on this hardware;
 * the saving is ~430 MB of guest memory written inside the system disk, which is a noticeable
 * slice of a phone.
 *
 * On by default, which is not a nudge — it is what Box already did before there was a choice, and
 * defaulting a new switch to off would have made every existing box slower overnight.
 */
@Composable
private fun OpenFasterRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Open faster", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                "Box keeps a saved copy, so it opens in about a second. Uses around 430 MB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/**
 * How much of the phone the box gets.
 *
 * Offered because the one number that is not adjustable is the one everything else waits on: the
 * guest is fully emulated, so a build inside it is bounded by processors that translate ARM into
 * ARM, and a toolchain that does not fit in memory does not get slower, it fails. What the phone
 * can spare is a question about the phone, so [GuestSizing.choicesFor] answers it and this only
 * draws what came back — a device with four cores is never offered eight.
 *
 * Two costs, and both are stated rather than discovered. A bigger box is a fatter `:computer`
 * process, which is the one Android reaches for first when it needs memory back; and the size is
 * part of the machine fingerprint, so any box saved by "Open faster" no longer matches the machine
 * being built and is dropped on the way up. That second line is only shown when there is something
 * to lose.
 */
@Composable
private fun MachineSizeRow(
    sizing: GuestSizing,
    choices: GuestSizingChoices,
    openFaster: Boolean,
    onChange: (GuestSizing) -> Unit,
) {
    // Nothing to choose between is not a setting. A phone small enough to be offered one memory
    // size and one processor count gets the plain "Machine" line above and no controls.
    if (choices.memoryMb.size <= 1 && choices.processors.size <= 1) return
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("Machine size", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(
            "A bigger box builds faster and holds more at once. It also makes Android likelier to " +
                "close it while you are in another app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (choices.memoryMb.size > 1) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                choices.memoryMb.forEach { megabytes ->
                    FilterChip(
                        selected = megabytes == sizing.memoryMb,
                        onClick = { onChange(sizing.copy(memoryMb = megabytes)) },
                        label = { Text(memoryLabel(megabytes)) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        if (choices.processors.size > 1) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                choices.processors.forEach { count ->
                    FilterChip(
                        selected = count == sizing.processors,
                        onClick = { onChange(sizing.copy(processors = count)) },
                        label = { Text(if (count == 1) "1 processor" else "$count processors") },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            // Said here rather than left to be found out, because the second half is expensive and
            // invisible: a resized machine cannot be handed the memory a differently sized one was
            // saved from, so the next open is the 95-120 s boot rather than the one-second reopen.
            if (openFaster) {
                "Applies the next time you open your box — and that open is a full boot, because " +
                    "the saved copy was a different machine."
            } else {
                "Applies the next time you open your box."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/** "2 processors • 2 GB memory". Whole gigabytes where they are whole, so 1536 still reads right. */
private fun machineSummary(sizing: GuestSizing): String {
    val processors = if (sizing.processors == 1) "1 processor" else "${sizing.processors} processors"
    return "$processors • ${memoryLabel(sizing.memoryMb)} memory"
}

private fun memoryLabel(megabytes: Int): String =
    if (megabytes % 1024 == 0) "${megabytes / 1024} GB" else "$megabytes MB"

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
