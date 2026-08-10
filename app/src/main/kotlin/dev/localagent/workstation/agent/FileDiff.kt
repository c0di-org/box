package dev.localagent.workstation.agent

import androidx.compose.runtime.Immutable

/**
 * A parsed file change, ready to render. Box never displays a raw unified diff — [UnifiedDiff]
 * turns patch text into this so the permission sheet and the transcript share one renderer.
 */
@Immutable
data class FileDiff(
    val path: String,
    val hunks: List<DiffHunk>,
    val additions: Int,
    val deletions: Int,
    val kind: ChangeKind = ChangeKind.Modify,
) {
    val fileName: String get() = path.substringAfterLast('/')
    val language: CodeLanguage get() = CodeLanguage.forPath(path)
}

enum class ChangeKind { Create, Modify, Delete, Rename }

@Immutable
data class DiffHunk(
    val oldStart: Int,
    val newStart: Int,
    val lines: List<DiffLine>,
    val heading: String? = null,
)

@Immutable
data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldNumber: Int?,
    val newNumber: Int?,
)

enum class DiffLineKind { Context, Added, Removed }

/** Languages the highlighter knows. Everything else renders as plain monospace. */
enum class CodeLanguage {
    Kotlin, JavaScript, Python, Json, Shell, Xml, Markdown, Plain;

    companion object {
        fun forPath(path: String): CodeLanguage = when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts", "java", "swift" -> Kotlin
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "css", "scss" -> JavaScript
            "py", "rb" -> Python
            "json", "lock" -> Json
            "sh", "bash", "zsh", "env", "conf", "toml", "ini" -> Shell
            "xml", "html", "htm", "svg", "gradle" -> Xml
            "md", "markdown", "txt" -> Markdown
            else -> Plain
        }
    }
}

/**
 * Minimal unified-diff parser. Handles the `@@ -a,b +c,d @@` form that git and every agent
 * harness emits; anything it cannot parse becomes context lines rather than an exception, because
 * a permission sheet that crashes is worse than one that under-highlights.
 */
object UnifiedDiff {
    private val hunkHeader = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@\s*(.*)$""")

    fun parse(path: String, patch: String, kind: ChangeKind = ChangeKind.Modify): FileDiff {
        val hunks = mutableListOf<DiffHunk>()
        var current: MutableList<DiffLine>? = null
        var oldStart = 0
        var newStart = 0
        var heading: String? = null
        var oldLine = 0
        var newLine = 0
        var additions = 0
        var deletions = 0

        fun flush() {
            val lines = current ?: return
            if (lines.isNotEmpty()) hunks += DiffHunk(oldStart, newStart, lines.toList(), heading)
            current = null
        }

        patch.lineSequence().forEach { raw ->
            val header = hunkHeader.find(raw)
            if (header != null) {
                flush()
                oldStart = header.groupValues[1].toIntOrNull() ?: 1
                newStart = header.groupValues[3].toIntOrNull() ?: 1
                heading = header.groupValues[5].takeIf { it.isNotBlank() }
                oldLine = oldStart
                newLine = newStart
                current = mutableListOf()
                return@forEach
            }
            val lines = current ?: return@forEach
            when {
                raw.startsWith("+") -> {
                    lines += DiffLine(DiffLineKind.Added, raw.drop(1), null, newLine++)
                    additions++
                }
                raw.startsWith("-") -> {
                    lines += DiffLine(DiffLineKind.Removed, raw.drop(1), oldLine++, null)
                    deletions++
                }
                raw.startsWith("\\") -> Unit // "\ No newline at end of file"
                else -> lines += DiffLine(DiffLineKind.Context, raw.removePrefix(" "), oldLine++, newLine++)
            }
        }
        flush()
        return FileDiff(path, hunks, additions, deletions, kind)
    }

    /** Convenience for a brand-new file: every line is an addition. */
    fun created(path: String, content: String): FileDiff {
        val lines = content.lines().mapIndexed { index, text ->
            DiffLine(DiffLineKind.Added, text, null, index + 1)
        }
        return FileDiff(
            path = path,
            hunks = listOf(DiffHunk(0, 1, lines)),
            additions = lines.size,
            deletions = 0,
            kind = ChangeKind.Create,
        )
    }
}
