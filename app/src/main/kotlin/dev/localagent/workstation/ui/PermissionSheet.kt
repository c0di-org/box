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
import dev.localagent.workstation.agent.Question

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
 * It is about one request at a time, which is not the same as there being only one: a turn can block
 * on two tools at once. The sheet takes the oldest and says how many are behind it, and each request
 * also carries its own inline decision in the transcript, so nothing depends on this modal being the
 * only way to answer.
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
 * ## The sheet that also answers questions
 *
 * A [PermissionAsk.Questions] turns this into a form. That is not a second job bolted on: the
 * question tool is answered *through* the permission result, so the surface that gets to say yes
 * is the only surface that can say "this one". Everything above still holds — nothing is
 * preselected, dismissing still abandons — with one addition and one removal. Added: the primary
 * button stays disabled until every question has an answer, because "Answer" that sends nothing is
 * the failure this whole path exists to end. Removed: "Always allow", which for a question would
 * mean answering ones nobody has read yet.
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
    // Keyed on the request, so the next question to land in this slot starts blank rather than
    // wearing the last one's ticks.
    val answers = remember(pending.requestId) { Answers() }
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
                    PermissionEvidence(pending.ask, answers)
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
            PermissionActions(pending.ask, answers, onDecision)
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
                when (ask) {
                    // "Wants permission" over a question would be the sheet's own version of the
                    // bug: it describes the channel rather than what is actually being asked.
                    is PermissionAsk.Questions ->
                        harnessName?.let { "$it is asking" } ?: "A question for you"
                    else -> harnessName?.let { "$it wants permission" } ?: "Permission needed"
                },
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
    is PermissionAsk.Questions -> Icons.Outlined.HelpOutline
    is PermissionAsk.Generic -> Icons.Outlined.Lock
}

/**
 * What the user has ticked so far — the one thing on this sheet that is not read-only.
 *
 * Answers are keyed by question text rather than by index, because that is the key the tool's own
 * `answers` field uses and the key the harness checks them against. Keying by position would have
 * worked right up until a question was dropped on the way through, and then answered the wrong one.
 *
 * A free-text answer lives in [written] and its key being present is what "Something else" being
 * ticked means. It is not one of the options because it is not the agent's to offer: the question
 * tool tells the model not to write an "Other" choice precisely because the surface doing the
 * asking is expected to supply one.
 */
@Stable
private class Answers {
    var chosen by mutableStateOf(emptyMap<String, Set<String>>())
        private set
    var written by mutableStateOf(emptyMap<String, String>())
        private set

    fun toggle(question: Question, label: String) {
        val current = chosen[question.text].orEmpty()
        chosen = if (question.multiSelect) {
            chosen + (question.text to if (label in current) current - label else current + label)
        } else {
            written = written - question.text
            chosen + (question.text to if (label in current) emptySet() else setOf(label))
        }
    }

    fun toggleWritten(question: Question) {
        written = if (question.text in written) {
            written - question.text
        } else {
            if (!question.multiSelect) chosen = chosen - question.text
            written + (question.text to "")
        }
    }

    fun write(question: Question, text: String) {
        if (question.text !in written) return
        written = written + (question.text to text)
    }

    /**
     * One question's answer, in the shape the tool documents: chosen labels joined by ", ".
     *
     * Blank means unanswered, which is the only state the buttons below need to distinguish. A
     * "Something else" ticked but left empty is therefore still unanswered, and correctly so.
     */
    fun of(question: Question): String =
        (chosen[question.text].orEmpty() + listOfNotNull(written[question.text]?.trim()?.ifEmpty { null }))
            .joinToString(", ")

    fun complete(questions: List<Question>): Boolean =
        questions.isNotEmpty() && questions.all { of(it).isNotEmpty() }

    fun collected(questions: List<Question>): Map<String, String> =
        questions.associate { it.text to of(it) }
}

@Composable
private fun PermissionEvidence(ask: PermissionAsk, answers: Answers) {
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

        is PermissionAsk.Questions -> {
            ask.questions.forEachIndexed { index, question ->
                // The headline already carries a lone question, so repeating it as the first
                // block would ask the same thing twice on one screen.
                QuestionBlock(
                    question = question,
                    answers = answers,
                    showText = ask.questions.size > 1,
                    last = index == ask.questions.lastIndex,
                )
            }
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
private fun QuestionBlock(question: Question, answers: Answers, showText: Boolean, last: Boolean) {
    Column(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 22.dp)) {
        if (question.header.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.tertiary,
            ) {
                Text(
                    question.header,
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                )
            }
            Spacer(Modifier.height(9.dp))
        }
        if (showText) {
            Text(
                question.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (question.multiSelect) {
            Text(
                "Choose as many as apply.",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        question.options.forEach { option ->
            OptionRow(
                label = option.label,
                description = option.description,
                selected = option.label in answers.chosen[question.text].orEmpty(),
                multiSelect = question.multiSelect,
                onClick = { answers.toggle(question, option.label) },
            )
            Spacer(Modifier.height(8.dp))
        }
        val text = answers.written[question.text]
        OptionRow(
            label = "Something else",
            description = if (text == null) "Answer in your own words." else null,
            selected = text != null,
            multiSelect = question.multiSelect,
            onClick = { answers.toggleWritten(question) },
        )
        if (text != null) {
            Spacer(Modifier.height(8.dp))
            WrittenAnswer(text) { answers.write(question, it) }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    description: String?,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // `selectable` rather than a bare click, so the screen reader says what this is and
            // whether it is on — the two facts a row of near-identical options depends on.
            .selectable(
                selected = selected,
                onClick = onClick,
                role = if (multiSelect) Role.Checkbox else Role.RadioButton,
            ),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                description?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Outlined.Check, contentDescription = null, Modifier.size(18.dp), tint = accent)
            }
        }
    }
}

@Composable
private fun WrittenAnswer(text: String, onChange: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    ) {
        BasicTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            maxLines = 4,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            "Type your answer",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp,
                        )
                    }
                    inner()
                }
            },
        )
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
    answers: Answers,
    onDecision: (PermissionDecision) -> Unit,
) {
    if (ask is PermissionAsk.Questions) {
        QuestionActions(ask, answers, onDecision)
        return
    }
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

/**
 * Answer, or say you would rather not.
 *
 * "Rather not say" is a denial rather than a dismissal, and the difference is the point:
 * dismissing leaves the agent waiting on an answer that may still arrive, while this tells it now
 * that none is coming. Both are honest and neither invents a preference — which is what the
 * sheet's usual Allow would have done here, since a question allowed but unanswered is precisely
 * the result that made this whole path worth building.
 */
@Composable
private fun QuestionActions(
    ask: PermissionAsk.Questions,
    answers: Answers,
    onDecision: (PermissionDecision) -> Unit,
) {
    val skipFocus = remember { MutableInteractionSource() }
    val answerFocus = remember { MutableInteractionSource() }
    val skipFocused by skipFocus.collectIsFocusedAsState()
    val answerFocused by answerFocus.collectIsFocusedAsState()
    val ready = answers.complete(ask.questions)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onDecision(PermissionDecision.Deny) },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(15.dp),
                border = if (skipFocused) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                },
                interactionSource = skipFocus,
            ) {
                Text("Rather not say", fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = { onDecision(PermissionDecision.Answered(answers.collected(ask.questions))) },
                modifier = Modifier.weight(1f).height(52.dp),
                // Off until every question has something in it. A live "Answer" that sends a blank
                // map would hand the agent the same non-answer it used to invent for itself.
                enabled = ready,
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                border = if (answerFocused && ready) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.onPrimary)
                } else {
                    null
                },
                interactionSource = answerFocus,
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Answer", fontWeight = FontWeight.SemiBold)
            }
        }
        if (!ready && ask.questions.size > 1) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "${ask.questions.count { answers.of(it).isNotEmpty() }} of ${ask.questions.size} answered",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        KeyboardHint()
    }
}
