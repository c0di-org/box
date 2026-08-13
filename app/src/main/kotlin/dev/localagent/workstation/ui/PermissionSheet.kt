package dev.localagent.workstation.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HelpOutline
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
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
 *
 * It is opened, never raised. Box used to put it up by itself the moment anything was asked, which
 * on a phone meant arriving over whatever the user was doing — the keyboard went down with it and
 * came back after the answer. Every unanswered request draws its own Allow and Deny in the
 * transcript, next to the work it is about, so the modal was never the only way to answer; what it
 * is *for* is the case a one-line card cannot serve, which is reading a whole diff before
 * deciding, and that is something a person asks for by tapping the card.
 *
 * It is about one request at a time, which is not the same as there being only one: a turn can
 * block on two tools at once. So it says how many are still behind this one, and closing it
 * answers nothing — the request is still standing in the transcript with both buttons on it.
 *
 * ## The keyboard, and why Enter does not simply mean Allow
 *
 * On a Fold or in DeX there is a hardware keyboard, and reaching for the screen to answer every
 * request is the kind of friction that turns into "allow always" out of fatigue. But the obvious
 * binding — Enter approves — quietly undoes the first choice above: a sheet that appears under the
 * cursor while someone is typing would take a stray Return as consent, and consent is the one
 * thing this sheet exists to make deliberate.
 *
 * So the keyboard gets the same shape as the touch surface rather than a shortcut past it. The
 * sheet itself takes focus when it opens — focus lives somewhere, but on nothing that decides
 * anything. Tab and the arrow keys move onto Deny first and Allow second, and the focused button
 * says so with a ring, so Enter is always visibly attached to a choice the user made. Enter is
 * then not a binding this file adds at all: it is what a focused button already does.
 *
 * Esc dismisses. Not "deny" — dismissing is the gesture that already exists here (swipe the sheet
 * away), it produces `Abandoned`, and it must keep meaning exactly that: the agent is left
 * waiting, nothing is approved, and no refusal the user never made is put in their mouth.
 *
 * ## What this is not for
 *
 * A question. An agent's question is answered on its own card in the transcript, options and all —
 * see `QuestionForm.kt`. It briefly lived here, because a question travels back *through* the
 * permission result and this was the surface that got to say yes; it left because a question
 * already stops the work, so the card is the interruption, and a modal on top of it interrupts the
 * same person twice over the same thing. [PermissionSheet] is never handed one; `BoxApp` filters
 * them out before this can be opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    pending: PendingPermission,
    harnessName: String?,
    onDecision: (PermissionDecision) -> Unit,
    onDismiss: () -> Unit,
    /** How many other requests are blocked behind this one. */
    alsoWaiting: Int = 0,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetFocus = remember { FocusRequester() }
    // Somewhere for key events to land that is not a decision. Without this, Esc goes nowhere
    // until the user has already picked a button — which is the moment they least need it.
    LaunchedEffect(pending.requestId) { runCatching { sheetFocus.requestFocus() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp)
                .focusRequester(sheetFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // Preview, so it is caught wherever focus has since moved to — including on
                    // the Allow button, where Esc still has to mean back out rather than nothing.
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
        ) {
            PermissionHeader(pending.ask, harnessName)
            if (alsoWaiting > 0) {
                // One turn can block on several tools at once. Saying so is the difference between
                // "answer this" and "answer this, then the next one arrives" — and it stops the
                // sheet reappearing from looking like a bug.
                Spacer(Modifier.height(10.dp))
                Text(
                    if (alsoWaiting == 1) {
                        "One more request is waiting behind this one."
                    } else {
                        "$alsoWaiting more requests are waiting behind this one."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
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
    // Only because `when` must be exhaustive. A question never reaches this sheet.
    is PermissionAsk.Questions -> Icons.Outlined.HelpOutline
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

        // Never drawn: `BoxApp` keeps questions off this sheet, and the card in the transcript
        // is the whole surface for one. Left as a branch rather than an `else` so that adding an
        // ask kind is still a compile error here.
        is PermissionAsk.Questions -> Unit

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

/**
 * Shown only where there are keys to press.
 *
 * A phone with no keyboard attached has nothing to learn from this, and a line of shortcuts under
 * a security decision on a 6" screen is noise in the one place noise is expensive.
 */
@Composable
private fun KeyboardHint() {
    val hasKeys = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
    if (!hasKeys) return
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "Tab to choose · Enter to confirm · Esc to leave it unanswered",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun PermissionActions(
    ask: PermissionAsk,
    onDecision: (PermissionDecision) -> Unit,
) {
    val denyFocus = remember { MutableInteractionSource() }
    val allowFocus = remember { MutableInteractionSource() }
    val denyFocused by denyFocus.collectIsFocusedAsState()
    val allowFocused by allowFocus.collectIsFocusedAsState()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onDecision(PermissionDecision.Deny) },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(15.dp),
                // The ring is the honest part of the keyboard path: it is the only thing on screen
                // that says which of these two Enter is currently pointing at.
                border = if (denyFocused) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                },
                interactionSource = denyFocus,
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
                border = if (allowFocused) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimary)
                } else {
                    null
                },
                interactionSource = allowFocus,
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Allow", fontWeight = FontWeight.SemiBold)
            }
        }
        KeyboardHint()
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
