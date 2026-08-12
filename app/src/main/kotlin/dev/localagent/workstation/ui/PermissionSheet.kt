package dev.localagent.workstation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.workstation.agent.CodeLanguage
import dev.localagent.workstation.agent.PendingPermission
import dev.localagent.workstation.agent.PermissionAsk
import dev.localagent.workstation.agent.PermissionDecision

/**
 * The moment Box justifies existing.
 *
 * A terminal shows `Edit file? (y/n)` and expects the user to trust the agent's summary. Box shows
 * the actual diff, syntax-highlighted, with the file path and the change counts, before anything
 * touches the disk. Three deliberate choices:
 *
 *  - Allow and Deny carry equal visual weight. Nothing is pre-selected and there is no default
 *    action on dismiss; backing out leaves the agent waiting rather than silently approving.
 *  - "Always allow" is a separate, quieter control with its scope spelled out in words, because
 *    it is the only button here that changes future behaviour.
 *  - The evidence is scrollable but the decision buttons are pinned, so a long diff can never
 *    push the choice off screen or invite a blind tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    pending: PendingPermission,
    harnessName: String?,
    onDecision: (PermissionDecision) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 26.dp)) {
            PermissionHeader(pending.ask, harnessName)
            Spacer(Modifier.height(16.dp))

            Box(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    PermissionEvidence(pending.ask)
                }
                // Fades whatever is still below the fold, so a long diff never looks like it
                // ended at the last visible line.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                            ),
                        ),
                )
            }

            Spacer(Modifier.height(18.dp))
            PermissionActions(pending.ask, onDecision)
        }
    }
}

@Composable
private fun PermissionHeader(ask: PermissionAsk, harnessName: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
            contentColor = MaterialTheme.colorScheme.tertiary,
        ) {
            Icon(askIcon(ask), contentDescription = null, modifier = Modifier.padding(9.dp).size(20.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                harnessName?.let { "$it wants permission" } ?: "Permission needed",
                style = MaterialTheme.typography.labelLarge,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                ask.headline,
                style = MaterialTheme.typography.headlineSmall,
                fontSize = 22.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun askIcon(ask: PermissionAsk): ImageVector = when (ask) {
    is PermissionAsk.EditFile -> Icons.Outlined.Folder
    is PermissionAsk.RunCommand -> Icons.Outlined.Terminal
    is PermissionAsk.NetworkAccess -> Icons.Outlined.Language
    is PermissionAsk.Generic -> Icons.Outlined.Lock
}

@Composable
private fun PermissionEvidence(ask: PermissionAsk) {
    when (ask) {
        is PermissionAsk.EditFile -> {
            ask.rationale?.let { Rationale(it) }
            DiffView(diff = ask.diff, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Footnote("Nothing is written until you allow it. Box only ever edits inside /workspace.")
        }

        is PermissionAsk.RunCommand -> {
            ask.rationale?.let { Rationale(it) }
            if (ask.destructive) {
                Spacer(Modifier.height(4.dp))
                DestructiveWarning()
                Spacer(Modifier.height(10.dp))
            }
            CodeBlock(
                text = "$ ${ask.command}",
                modifier = Modifier.fillMaxWidth(),
                language = CodeLanguage.Shell,
            )
            Spacer(Modifier.height(10.dp))
            DetailRow("Working directory", ask.workingDirectory)
        }

        is PermissionAsk.NetworkAccess -> {
            ask.purpose?.let { Rationale(it) }
            DetailRow("Host", ask.host)
            Spacer(Modifier.height(8.dp))
            Footnote("Through Box's private NAT. No port on this phone is exposed.")
        }

        is PermissionAsk.Generic -> {
            Rationale(ask.description)
            ask.details.forEach { (label, value) ->
                DetailRow(label, value)
            }
        }
    }
}

@Composable
private fun Rationale(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 14.dp),
    )
}

@Composable
private fun DestructiveWarning() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("This command deletes or overwrites files.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.width(140.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionActions(ask: PermissionAsk, onDecision: (PermissionDecision) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onDecision(PermissionDecision.Deny) },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(15.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Deny", fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = { onDecision(PermissionDecision.Allow) },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Allow", fontWeight = FontWeight.SemiBold)
            }
        }
        ask.alwaysAllowScope?.let { scope ->
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = { onDecision(PermissionDecision.AllowAlways(scope)) }) {
                    Text(
                        "Always allow $scope",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
