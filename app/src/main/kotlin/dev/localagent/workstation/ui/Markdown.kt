package dev.localagent.workstation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.workstation.agent.CodeLanguage

// ---------------------------------------------------------------------------
// The document
// ---------------------------------------------------------------------------

/**
 * Markdown, rendered rather than recited.
 *
 * Claude writes markdown whether or not anyone asked, so a transcript drawing agent prose as plain
 * text shows the user the punctuation instead of the answer.
 *
 * Hand-rolled and small on purpose. A markdown library is a parser, a renderer, its own theming and
 * usually its own image loading, bought for a problem that is one screen of code at the subset an
 * agent actually emits: headings, lists, emphasis, inline code, fences, quotes, rules. Deliberately
 * absent: tables, images, footnotes, HTML — anything unrecognised falls through as its own literal
 * text, which is what the whole transcript did before. Degraded, never wrong.
 *
 * The parse is a pure function over lines so it can be tested without a device; styling lives in
 * the composables below, where the theme is.
 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock

    data class Paragraph(val text: String) : MdBlock

    /** [marker] is null for a bullet and the literal "3." for an ordered item, so it can count. */
    data class Item(val depth: Int, val marker: String?, val text: String) : MdBlock

    data class Code(val language: String?, val code: String) : MdBlock

    data class Quote(val text: String) : MdBlock

    data object Rule : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val ITEM = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
private val RULE = Regex("^\\s{0,3}(-{3,}|\\*{3,}|_{3,})\\s*$")
private const val FENCE = "```"

/**
 * One pass over the lines, because that is all this needs.
 *
 * The one rule worth stating: an unterminated fence runs to the end of the text rather than being
 * discarded. Text arrives here mid-stream, so a code block is *usually* unterminated at the moment
 * it is first drawn, and treating that as "not really a code block" would make every fence flicker
 * from prose into code as its closing line arrived.
 */
internal fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    val quote = StringBuilder()

    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString())
            paragraph.setLength(0)
        }
        if (quote.isNotEmpty()) {
            blocks += MdBlock.Quote(quote.toString())
            quote.setLength(0)
        }
    }

    val lines = source.lines()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()

        if (trimmed.startsWith(FENCE)) {
            flush()
            val info = trimmed.removePrefix(FENCE).trim().ifEmpty { null }
            val code = StringBuilder()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith(FENCE)) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[index])
                index++
            }
            // Past the closing fence, or past the end if the stream has not written one yet.
            index++
            blocks += MdBlock.Code(info, code.toString())
            continue
        }

        when {
            trimmed.isEmpty() -> flush()

            RULE.matches(line) -> {
                flush()
                blocks += MdBlock.Rule
            }

            HEADING.matches(line) -> {
                flush()
                val (hashes, text) = HEADING.find(line)!!.destructured
                blocks += MdBlock.Heading(hashes.length, text.trim())
            }

            trimmed.startsWith(">") -> {
                if (paragraph.isNotEmpty()) flush()
                if (quote.isNotEmpty()) quote.append(' ')
                quote.append(trimmed.removePrefix(">").trim())
            }

            ITEM.matches(line) -> {
                flush()
                val (indent, marker, text) = ITEM.find(line)!!.destructured
                blocks += MdBlock.Item(
                    // Two spaces is one level in every list an agent writes; four is one level in
                    // the spec. Halving and capping covers both without a nesting model.
                    depth = (indent.length / 2).coerceAtMost(3),
                    marker = marker.takeIf { it.first().isDigit() },
                    text = text.trim(),
                )
            }

            else -> {
                if (quote.isNotEmpty()) flush()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
        index++
    }
    flush()
    return blocks
}

// ---------------------------------------------------------------------------
// Inline
// ---------------------------------------------------------------------------

/** A run of text and what markdown said about it. Deliberately style-free, so it can be tested. */
internal data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/**
 * Emphasis, inline code and links, as toggles rather than matched pairs.
 *
 * Toggles are what makes streaming look right: half of `**a bold phrase` exists on screen for a
 * moment before its closing marker arrives, and a pair-matching parser would show the asterisks
 * until it did, then reflow. The guards below are the whole reason this is not naive — an opening
 * marker has to be followed by something other than a space (so `2 * 3 * 4` is arithmetic), and an
 * underscore only counts at a word boundary (so `some_variable_name` survives).
 */
internal fun inlineMarkdown(source: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    val buffer = StringBuilder()
    var bold = false
    var italic = false
    var index = 0

    fun emit(link: String? = null, code: Boolean = false, text: String = buffer.toString()) {
        if (text.isEmpty()) return
        spans += MdSpan(text, bold = bold, italic = italic, code = code, link = link)
    }

    fun cut() {
        emit()
        buffer.setLength(0)
    }

    fun opens(at: Int, width: Int): Boolean = source.getOrNull(at + width)?.isWhitespace() == false

    while (index < source.length) {
        val char = source[index]
        val next = source.getOrNull(index + 1)
        val previous = source.getOrNull(index - 1)

        when {
            char == '\\' && next != null -> {
                buffer.append(next)
                index += 2
            }

            char == '`' -> {
                val end = source.indexOf('`', index + 1)
                if (end < 0) {
                    buffer.append(char)
                    index++
                } else {
                    cut()
                    emit(code = true, text = source.substring(index + 1, end))
                    index = end + 1
                }
            }

            char == '[' -> {
                val label = source.indexOf(']', index + 1)
                val open = label + 1
                val close = if (label > 0 && source.getOrNull(open) == '(') source.indexOf(')', open) else -1
                if (close < 0) {
                    buffer.append(char)
                    index++
                } else {
                    cut()
                    emit(link = source.substring(open + 1, close), text = source.substring(index + 1, label))
                    index = close + 1
                }
            }

            char == '*' && next == '*' && (bold || opens(index, 2)) -> {
                cut()
                bold = !bold
                index += 2
            }

            char == '*' && (italic || opens(index, 1)) -> {
                cut()
                italic = !italic
                index++
            }

            char == '_' && next == '_' && wordEdge(previous, source.getOrNull(index + 2), bold) -> {
                cut()
                bold = !bold
                index += 2
            }

            char == '_' && wordEdge(previous, next, italic) -> {
                cut()
                italic = !italic
                index++
            }

            else -> {
                buffer.append(char)
                index++
            }
        }
    }
    cut()
    return spans
}

/**
 * Whether an underscore here is emphasis rather than part of a name.
 *
 * Opening: the character before must not be part of a word, and the one after must exist and not
 * be a space. Closing: only that the marker is currently open — a run that started as emphasis
 * ends where it says it does.
 */
private fun wordEdge(previous: Char?, next: Char?, open: Boolean): Boolean = when {
    open -> true
    previous != null && (previous.isLetterOrDigit() || previous == '_') -> false
    else -> next != null && !next.isWhitespace()
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

/**
 * Agent prose, drawn.
 *
 * Everything is a `Text` over an [AnnotatedString] except fenced code, which goes through the same
 * [CodeBlock] the tool cards and the file preview use — one renderer for code in this app, so the
 * snippet in an explanation looks like the output it is explaining.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineAnnotated(block.text, color),
                    style = MaterialTheme.typography.titleMedium,
                    // Six levels of heading in a chat bubble is a document pretending to be a
                    // reply; three sizes carry the structure and the rest reuse the smallest.
                    fontSize = when (block.level) {
                        1 -> 19.sp
                        2 -> 17.sp
                        else -> 15.sp
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    modifier = Modifier.padding(top = 4.dp),
                )

                is MdBlock.Paragraph -> Text(
                    inlineAnnotated(block.text, color),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                )

                is MdBlock.Item -> Row(Modifier.padding(start = (block.depth * 14).dp)) {
                    Text(
                        block.marker ?: "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inlineAnnotated(block.text, color),
                        style = MaterialTheme.typography.bodyLarge,
                        color = color,
                    )
                }

                is MdBlock.Code -> CodeBlock(
                    text = block.code,
                    modifier = Modifier.fillMaxWidth(),
                    language = fenceLanguage(block.language),
                    // The whole bubble is already inside a SelectionContainer, and a second one
                    // nested in it would fight the first for the drag.
                    selectable = false,
                )

                // Height from the text it marks, not a guess: a quote is one line or ten.
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        inlineAnnotated(block.text, MaterialTheme.colorScheme.onSurfaceVariant),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

@Composable
private fun inlineAnnotated(text: String, color: Color): AnnotatedString {
    val codeInk = MaterialTheme.colorScheme.primary
    val codeWash = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val linkInk = MaterialTheme.colorScheme.primary
    return remember(text, color, codeInk, codeWash, linkInk) {
        buildAnnotatedString {
            inlineMarkdown(text).forEach { span ->
                val style = when {
                    span.code -> SpanStyle(
                        color = codeInk,
                        background = codeWash,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                    // Links are styled but not tappable: the guest has no browser, and opening
                    // one for a URL the agent chose is a decision the transcript should not make
                    // on the user's behalf. Seeing where it points is the whole job here.
                    span.link != null -> SpanStyle(
                        color = linkInk,
                        textDecoration = TextDecoration.Underline,
                    )

                    else -> SpanStyle(
                        color = color,
                        fontWeight = if (span.bold) FontWeight.SemiBold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                    )
                }
                withStyle(style) { append(span.text) }
            }
        }
    }
}

/** Fence info strings, which are language names rather than file extensions. */
private fun fenceLanguage(info: String?): CodeLanguage =
    when (info?.substringBefore(' ')?.lowercase()) {
        "kotlin", "kt", "kts", "java", "swift" -> CodeLanguage.Kotlin
        "js", "javascript", "mjs", "ts", "typescript", "tsx", "jsx", "css" -> CodeLanguage.JavaScript
        "py", "python", "rb", "ruby" -> CodeLanguage.Python
        "json" -> CodeLanguage.Json
        "sh", "bash", "zsh", "shell", "console", "terminal" -> CodeLanguage.Shell
        "xml", "html", "svg" -> CodeLanguage.Xml
        "md", "markdown" -> CodeLanguage.Markdown
        else -> CodeLanguage.Plain
    }
