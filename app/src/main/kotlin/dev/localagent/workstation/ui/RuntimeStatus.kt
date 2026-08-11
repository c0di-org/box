package dev.localagent.workstation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

data class StatePresentation(
    val shortLabel: String,
    val eyebrow: String,
    val title: String,
    val body: String,
    val color: Color,
)

@Composable
fun statePresentation(state: RuntimeState): StatePresentation = when (state) {
    RuntimeState.NotProvisioned -> StatePresentation(
        "Not set up",
        "THE AGENT'S COMPUTER",
        "A real Linux box,\ninside your phone.",
        "Agents work in a private Debian workspace. Setting it up prepares the disk image; the " +
            "first boot takes a couple of minutes.",
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    is RuntimeState.Provisioning -> StatePresentation(
        "Setting up",
        "PREPARING THE WORKSPACE",
        "Setting up the computer",
        "Verifying the Linux system and creating the private workspace. Keep Box open for this step.",
        MaterialTheme.colorScheme.primary,
    )
    RuntimeState.Stopped -> StatePresentation(
        "Off",
        "PRIVATE WORKSPACE",
        "The computer is off.",
        "Start it when an agent needs to run something. Files stay in /workspace between sessions.",
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RuntimeState.Starting -> StatePresentation(
        "Booting",
        "STARTING THE VM",
        "Booting Debian",
        "Box boots a full ARM64 virtual machine — about 90 seconds on this phone. You can keep " +
            "using the conversation while it starts.",
        MaterialTheme.colorScheme.primary,
    )
    RuntimeState.Connecting -> StatePresentation(
        "Connecting",
        "THE VM IS RUNNING",
        "Almost there",
        "Debian is up. Box is waiting for its private control channel so commands and files are " +
            "safe to use.",
        MaterialTheme.colorScheme.primary,
    )
    RuntimeState.Ready -> StatePresentation(
        "Ready",
        "THE AGENT'S COMPUTER",
        "The computer is ready.",
        "Agents can run commands and edit files in /workspace. You can take over any time.",
        MaterialTheme.colorScheme.primary,
    )
    RuntimeState.Stopping -> StatePresentation(
        "Stopping",
        "SHUTTING DOWN SAFELY",
        "Stopping the computer",
        "The virtual machine is closing. The persistent workspace stays stored on this phone.",
        MaterialTheme.colorScheme.tertiary,
    )
    RuntimeState.Suspending -> StatePresentation(
        "Pausing",
        "SAVING RUNTIME STATE",
        "Pausing the computer",
        "The workspace remains safely stored on this phone.",
        MaterialTheme.colorScheme.tertiary,
    )
    RuntimeState.Suspended -> StatePresentation(
        "Paused",
        "WORKSPACE SAVED",
        "The computer is paused.",
        "Resume when an agent needs the Linux workspace again.",
        MaterialTheme.colorScheme.tertiary,
    )
    is RuntimeState.Failed -> StatePresentation(
        "Needs attention",
        "STARTUP DIDN’T FINISH",
        "The computer couldn’t start.",
        state.reason.message.ifBlank { "The Linux runtime stopped before it became ready." },
        MaterialTheme.colorScheme.error,
    )
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
 * Shown wherever a tool needs the VM and the VM is not there yet. Deliberately calm: the VM takes
 * ~90 seconds and Android can reclaim it, so this screen is a normal part of the day.
 */
@Composable
fun RuntimeGate(
    destination: String,
    state: RuntimeState,
    onOpenBox: () -> Unit,
) {
    val presentation = statePresentation(state)
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RuntimeGlyph(state, Modifier.size(78.dp))
            Spacer(Modifier.height(22.dp))
            Text(
                when (state) {
                    RuntimeState.Ready -> "Ready"
                    RuntimeState.Starting, RuntimeState.Connecting, is RuntimeState.Provisioning ->
                        "The computer is getting ready"
                    RuntimeState.Stopping -> "The computer is shutting down"
                    is RuntimeState.Failed -> "The computer needs attention"
                    else -> "Start the computer to open $destination"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    state == RuntimeState.Starting || state == RuntimeState.Connecting ||
                        state is RuntimeState.Provisioning ->
                        "$destination unlocks as soon as the private control channel is up. " +
                            "Booting takes about 90 seconds."
                    state == RuntimeState.Stopping ->
                        "The workspace is safe. You can start the computer again after shutdown finishes."
                    else -> presentation.body
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            when (state) {
                RuntimeState.NotProvisioned, RuntimeState.Stopped, RuntimeState.Suspended ->
                    Button(onClick = onOpenBox) { Text("Open your box") }
                is RuntimeState.Failed -> Button(onClick = onOpenBox) { Text("Try again") }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    state: RuntimeState,
    onDismiss: () -> Unit,
    onOpenBox: () -> Unit,
    onStop: () -> Unit,
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
                DiagnosticRow("Virtual machine", "QEMU TCG • 2 vCPU • 1 GB")
                DiagnosticRow("Workspace", "/workspace • persistent disk")
                DiagnosticRow("Control channel", "Private on-device socket")
                DiagnosticRow("Guest network", "Private NAT")
                Spacer(Modifier.height(20.dp))
                when (state) {
                    RuntimeState.NotProvisioned, RuntimeState.Stopped, RuntimeState.Suspended ->
                        Button(onClick = onOpenBox, Modifier.fillMaxWidth()) { Text("Open your box") }
                    RuntimeState.Starting, RuntimeState.Connecting, RuntimeState.Ready, RuntimeState.Suspending ->
                        OutlinedButton(onClick = onStop, Modifier.fillMaxWidth()) { Text("Close your box") }
                    RuntimeState.Stopping -> Unit
                    is RuntimeState.Failed -> Button(onClick = onOpenBox, Modifier.fillMaxWidth()) { Text("Try again") }
                    is RuntimeState.Provisioning -> Unit
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "Box ${BuildConfig.VERSION_NAME} • ${BuildConfig.FLAVOR.replaceFirstChar { it.titlecase() }} runtime",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
