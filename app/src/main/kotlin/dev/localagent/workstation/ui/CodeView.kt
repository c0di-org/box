package dev.localagent.workstation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.workstation.agent.ChangeKind
import dev.localagent.workstation.agent.CodeLanguage
import dev.localagent.workstation.agent.DiffLine
import dev.localagent.workstation.agent.DiffLineKind
import dev.localagent.workstation.agent.FileDiff

// ---------------------------------------------------------------------------
// Palette
// ---------------------------------------------------------------------------

/** Syntax colours, tuned against [BoxTerminal] rather than the app background. */
object CodeColors {
    val plain = Color(0xFFD7DED9)
    val muted = Color(0xFF7C867F)
    val keyword = Color(0xFFC792EA)
    val string = Color(0xFF9BD98A)
    val number = Color(0xFFF2B880)
    val comment = Color(0xFF6B7A70)
    val punctuation = Color(0xFF9AA79F)

    val addedInk = Color(0xFFC6F3B4)
    val addedWash = Color(0x262AB80C)
    val addedGutter = Color(0xFF3E8F2A)
    val removedInk = Color(0xFFFFC2BB)
    val removedWash = Color(0x26E5484D)
    val removedGutter = Color(0xFFA9524F)
}

private val Punctuation = setOf('{', '}', '(', ')', '[', ']', ';', ',', '.', ':', '=', '<', '>', '+', '-', '*', '/', '|', '&', '!', '?')

private val KotlinKeywords = setOf(
    "package", "import", "fun", "val", "var", "class", "object", "interface", "data", "sealed",
    "override", "private", "internal", "public", "protected", "return", "if", "else", "when",
    "for", "while", "in", "is", "as", "null", "true", "false", "this", "super", "companion",
    "suspend", "const", "lateinit", "by", "enum", "typealias", "operator", "inline", "try",
    "catch", "finally", "throw", "do", "break", "continue", "vararg", "out", "reified",
)

private val JsKeywords = setOf(
    "import", "from", "export", "default", "const", "let", "var", "function", "return", "if",
    "else", "for", "while", "class", "extends", "new", "await", "async", "try", "catch",
    "finally", "throw", "typeof", "instanceof", "null", "undefined", "true", "false", "this",
    "switch", "case", "break", "continue", "of", "in", "delete", "yield", "static", "get", "set",
)

private val PythonKeywords = setOf(
    "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as",
    "with", "try", "except", "finally", "raise", "yield", "lambda", "None", "True", "False",
    "and", "or", "not", "in", "is", "pass", "break", "continue", "global", "async", "await",
)

private val ShellKeywords = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
    "export", "local", "return", "source", "set", "unset", "echo", "cd", "true", "false",
)

private fun keywordsFor(language: CodeLanguage): Set<String> = when (language) {
    CodeLanguage.Kotlin -> KotlinKeywords
    CodeLanguage.JavaScript -> JsKeywords
    CodeLanguage.Python -> PythonKeywords
    CodeLanguage.Shell -> ShellKeywords
    else -> emptySet()
}

private fun commentPrefix(language: CodeLanguage): String? = when (language) {
    CodeLanguage.Kotlin, CodeLanguage.JavaScript -> "//"
    CodeLanguage.Python, CodeLanguage.Shell -> "#"
    else -> null
}

/**
 * Single-line syntax highlighting.
 *
 * A per-character colour buffer keeps this readable and total: passes run in priority order and
 * the last one wins, so a keyword inside a string ends up string-coloured without any of the
 * bookkeeping a real lexer would need. Good enough for reviewing a diff on a phone; deliberately
 * not a parser.
 */
fun highlightLine(text: String, language: CodeLanguage): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    if (language == CodeLanguage.Plain || language == CodeLanguage.Markdown) {
        return AnnotatedString(text)
    }

    val colors = Array(text.length) { CodeColors.plain }

    // Identifiers: keywords, then bare punctuation.
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char.isLetter() || char == '_' || char == '$' -> {
                var end = index
                while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$')) end++
                val word = text.substring(index, end)
                val color = when {
                    word in keywordsFor(language) -> CodeColors.keyword
                    language == CodeLanguage.Json && index > 0 && text[index - 1] == '"' -> CodeColors.plain
                    else -> CodeColors.plain
                }
                for (i in index until end) colors[i] = color
                index = end
            }

            char.isDigit() -> {
                var end = index
                while (end < text.length && (text[end].isDigit() || text[end] == '.' || text[end] == '_')) end++
                for (i in index until end) colors[i] = CodeColors.number
                index = end
            }

            char in Punctuation -> {
                colors[index] = CodeColors.punctuation
                index++
            }

            else -> index++
        }
    }

    // Strings override identifiers.
    index = 0
    while (index < text.length) {
        val quote = text[index]
        if (quote == '"' || quote == '\'' || quote == '`') {
            var end = index + 1
            while (end < text.length && text[end] != quote) {
                if (text[end] == '\\') end++
                end++
            }
            val last = (end).coerceAtMost(text.length - 1)
            for (i in index..last) colors[i] = CodeColors.string
            index = last + 1
        } else {
            index++
        }
    }

    // Comments override everything, to end of line.
    commentPrefix(language)?.let { prefix ->
        val start = text.indexOf(prefix)
        if (start >= 0 && !insideString(text, start)) {
            for (i in start until text.length) colors[i] = CodeColors.comment
        }
    }

    return buildAnnotatedString {
        var runStart = 0
        while (runStart < text.length) {
            var runEnd = runStart + 1
            while (runEnd < text.length && colors[runEnd] == colors[runStart]) runEnd++
            withStyle(SpanStyle(color = colors[runStart])) {
                append(text.substring(runStart, runEnd))
            }
            runStart = runEnd
        }
    }
}

private fun insideString(text: String, position: Int): Boolean {
    var quotes = 0
    for (i in 0 until position) {
        if (text[i] == '"' || text[i] == '\'') quotes++
    }
    return quotes % 2 == 1
}

// ---------------------------------------------------------------------------
// Diff rendering
// ---------------------------------------------------------------------------

/**
 * The unified-diff renderer shared by the permission sheet and the transcript. One renderer means
 * the review the user approves looks exactly like the record they read afterwards.
 */
@Composable
fun DiffView(
    diff: FileDiff,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    maxLines: Int? = null,
) {
    val horizontal = rememberScrollState()
    Surface(
        modifier = modifier,
        color = BoxTerminal,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column {
            if (showHeader) {
                DiffHeader(diff)
            }
            val lines = remember(diff, maxLines) {
                val all = diff.hunks.flatMap { hunk -> hunk.lines }
                if (maxLines != null && all.size > maxLines) all.take(maxLines) else all
            }
            val hidden = remember(diff, maxLines) {
                val total = diff.hunks.sumOf { it.lines.size }
                if (maxLines != null && total > maxLines) total - maxLines else 0
            }
            SelectionContainer {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontal),
                ) {
                    // Intrinsic width makes every row as wide as the longest one, so the
                    // added/removed wash runs edge to edge instead of stopping at the text.
                    Column(
                        Modifier
                            .width(IntrinsicSize.Max)
                            .padding(vertical = 8.dp),
                    ) {
                        lines.forEach { line -> DiffRow(line, diff.language) }
                    }
                }
            }
            if (hidden > 0) {
                Text(
                    "+$hidden more lines",
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = CodeColors.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun DiffHeader(diff: FileDiff) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF121614))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                diff.fileName,
                color = CodeColors.plain,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                diff.path.substringBeforeLast('/'),
                color = CodeColors.muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (diff.kind == ChangeKind.Create) {
            ChangeBadge("new file", CodeColors.addedInk, CodeColors.addedWash)
            Spacer(Modifier.width(6.dp))
        }
        if (diff.additions > 0) {
            ChangeBadge("+${diff.additions}", CodeColors.addedInk, CodeColors.addedWash)
            Spacer(Modifier.width(6.dp))
        }
        if (diff.deletions > 0) {
            ChangeBadge("−${diff.deletions}", CodeColors.removedInk, CodeColors.removedWash)
        }
    }
}

@Composable
private fun ChangeBadge(text: String, ink: Color, wash: Color) {
    Surface(color = wash, contentColor = ink, shape = CircleShape) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DiffRow(line: DiffLine, language: CodeLanguage) {
    val wash = when (line.kind) {
        DiffLineKind.Added -> CodeColors.addedWash
        DiffLineKind.Removed -> CodeColors.removedWash
        DiffLineKind.Context -> Color.Transparent
    }
    val sign = when (line.kind) {
        DiffLineKind.Added -> "+"
        DiffLineKind.Removed -> "−"
        DiffLineKind.Context -> " "
    }
    val signColor = when (line.kind) {
        DiffLineKind.Added -> CodeColors.addedGutter
        DiffLineKind.Removed -> CodeColors.removedGutter
        DiffLineKind.Context -> CodeColors.muted
    }
    Row(
        Modifier.fillMaxWidth().background(wash).padding(horizontal = 10.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        GutterNumber(line.oldNumber)
        GutterNumber(line.newNumber)
        Text(
            sign,
            Modifier.width(14.dp),
            color = signColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Text(
            highlightLine(line.text, language),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            softWrap = false,
        )
    }
}

@Composable
private fun GutterNumber(number: Int?) {
    Text(
        number?.toString().orEmpty(),
        Modifier.width(30.dp),
        color = CodeColors.muted.copy(alpha = 0.7f),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 18.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

/**
 * Monospace output block used by tool cards, the file preview and fenced code in agent prose.
 *
 * [selectable] exists for that last caller: a code fence inside a message is already inside the
 * bubble's own `SelectionContainer`, and nesting a second one there would give one drag two
 * managers to answer to.
 */
@Composable
fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    language: CodeLanguage = CodeLanguage.Plain,
    maxLines: Int = Int.MAX_VALUE,
    selectable: Boolean = true,
) {
    val horizontal = rememberScrollState()
    val lines = remember(text, maxLines) {
        val all = text.trimEnd().lines()
        if (all.size > maxLines) all.takeLast(maxLines) else all
    }
    Surface(modifier = modifier, color = BoxTerminal, shape = RoundedCornerShape(14.dp)) {
        MaybeSelectable(selectable) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontal)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                lines.forEach { line ->
                    if (line.isEmpty()) {
                        Spacer(Modifier.height(9.dp))
                    } else {
                        Text(
                            highlightLine(line, language),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaybeSelectable(selectable: Boolean, content: @Composable () -> Unit) {
    if (selectable) SelectionContainer { content() } else content()
}

@Composable
fun MonospaceLine(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CodeColors.plain,
) {
    Box(modifier) {
        Text(
            text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
