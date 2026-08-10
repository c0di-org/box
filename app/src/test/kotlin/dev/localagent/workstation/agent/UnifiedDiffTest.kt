package dev.localagent.workstation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDiffTest {

    @Test
    fun `parses hunk line numbers and change counts`() {
        val diff = UnifiedDiff.parse(
            path = "/workspace/app/vite.config.js",
            patch = """
                @@ -1,4 +1,5 @@
                 export default {
                -  port: 5173,
                +  host: true,
                +  port: 5173,
                 }
            """.trimIndent(),
        )

        assertEquals(2, diff.additions)
        assertEquals(1, diff.deletions)
        assertEquals(CodeLanguage.JavaScript, diff.language)
        assertEquals("vite.config.js", diff.fileName)

        val lines = diff.hunks.single().lines
        assertEquals(listOf(1, 2, null, null, 3), lines.map { it.oldNumber })
        assertEquals(listOf(1, null, 2, 3, 4), lines.map { it.newNumber })
    }

    /** A permission sheet that crashes is worse than one that under-highlights. */
    @Test
    fun `content outside any hunk header is ignored rather than throwing`() {
        val diff = UnifiedDiff.parse("/workspace/x.txt", "diff --git a/x b/x\nnot a hunk at all")

        assertTrue(diff.hunks.isEmpty())
        assertEquals(0, diff.additions)
    }

    @Test
    fun `created file marks every line as an addition`() {
        val diff = UnifiedDiff.created("/workspace/notes.md", "one\ntwo")

        assertEquals(ChangeKind.Create, diff.kind)
        assertEquals(2, diff.additions)
        assertTrue(diff.hunks.single().lines.all { it.kind == DiffLineKind.Added })
    }
}
