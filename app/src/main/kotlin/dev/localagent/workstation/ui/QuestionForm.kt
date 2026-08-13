package dev.localagent.workstation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.workstation.agent.Question

/**
 * The form an agent's question is answered on, wherever it is drawn.
 *
 * It began life inside the permission sheet, because a question is answered *through* the
 * permission result and the sheet was the surface that got to say yes. It is here instead because
 * the sheet stopped being the surface: a question stops the work either way, so a card in the
 * transcript is already the interruption — putting a modal on top of it interrupts the same person
 * twice for the same reason. The transcript is where the question was asked and where the answer
 * belongs, next to whatever the agent said just before it.
 *
 * Nothing here decides anything. The buttons and the round trip stay with the card; this is the
 * options, the ticks and the free-text box, and it is deliberately layout-agnostic so a wider
 * window can give it more room without a second copy of the rules.
 */

/**
 * What the user has ticked so far.
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
class Answers internal constructor() {
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
     * Blank means unanswered, which is the only state the buttons need to distinguish. A
     * "Something else" ticked but left empty is therefore still unanswered, and correctly so.
     */
    fun of(question: Question): String =
        (chosen[question.text].orEmpty() + listOfNotNull(written[question.text]?.trim()?.ifEmpty { null }))
            .joinToString(", ")

    fun complete(questions: List<Question>): Boolean =
        questions.isNotEmpty() && questions.all { of(it).isNotEmpty() }

    fun answeredCount(questions: List<Question>): Int = questions.count { of(it).isNotEmpty() }

    fun collected(questions: List<Question>): Map<String, String> =
        questions.associate { it.text to of(it) }
}

/**
 * Half-finished answers, held above the transcript rather than inside it.
 *
 * A question card lives in a `LazyColumn`, and a `LazyColumn` disposes what scrolls off the
 * screen — so ticks remembered inside the card would be gone the moment someone scrolled up to
 * re-read the paragraph the question was about, which is the most likely thing they will do. This
 * outlives the row and is scoped to the conversation pane that owns it.
 *
 * Deliberately a plain map of observable objects rather than an observable map: nothing watches
 * the set of live forms, and every read that matters is a read of one form's own state.
 */
@Stable
class AnswerStore {
    private val forms = mutableMapOf<String, Answers>()

    fun of(requestId: String): Answers = forms.getOrPut(requestId) { Answers() }

    /** Forgotten once answered, so a request id the harness reuses cannot inherit old ticks. */
    fun forget(requestId: String) {
        forms.remove(requestId)
    }
}

/**
 * One question: what is being asked, and everything it will take as an answer.
 *
 * [showText] is false for a lone question whose text is already the card's headline — repeating it
 * as the first line would ask the same thing twice, an inch apart.
 */
@Composable
fun QuestionBlock(
    question: Question,
    answers: Answers,
    showText: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 20.dp)) {
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
                fontSize = 15.sp,
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
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
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
