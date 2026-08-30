package dev.localagent.workstation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parse, without a device.
 *
 * What is pinned here is the half of markdown rendering that can be wrong invisibly: which lines
 * became which blocks, and what happens to text that is only half-written because the agent is
 * still typing it.
 */
class MarkdownTest {

    @Test
    fun `headings lists and fences become their own blocks`() {
        val blocks = parseMarkdown(
            """
            ## What I found

            The build fails in two places:

            - `Config.kt` is missing a default
            - the test fixture is stale

            ```bash
            ./gradlew test
            ```
            """.trimIndent(),
        )

        assertEquals(MdBlock.Heading(2, "What I found"), blocks[0])
        assertEquals(MdBlock.Paragraph("The build fails in two places:"), blocks[1])
        assertEquals(MdBlock.Item(0, null, "`Config.kt` is missing a default"), blocks[2])
        assertEquals(MdBlock.Item(0, null, "the test fixture is stale"), blocks[3])
        assertEquals(MdBlock.Code("bash", "./gradlew test"), blocks[4])
    }

    @Test
    fun `an ordered item keeps its own number`() {
        val blocks = parseMarkdown("1. clone it\n2. build it")
        assertEquals(listOf("1.", "2."), blocks.map { (it as MdBlock.Item).marker })
    }

    @Test
    fun `an indented item nests`() {
        val blocks = parseMarkdown("- top\n  - under it")
        assertEquals(0, (blocks[0] as MdBlock.Item).depth)
        assertEquals(1, (blocks[1] as MdBlock.Item).depth)
    }

    /** Mid-stream, a fence is open more often than it is closed. */
    @Test
    fun `an unterminated fence is still a code block`() {
        val blocks = parseMarkdown("Try this:\n\n```sh\nnpm install\nnpm test")
        val code = blocks.last() as MdBlock.Code
        assertEquals("sh", code.language)
        assertEquals("npm install\nnpm test", code.code)
    }

    @Test
    fun `wrapped prose is one paragraph and a blank line ends it`() {
        val blocks = parseMarkdown("one line\nand its wrap\n\na second paragraph")
        assertEquals(
            listOf(MdBlock.Paragraph("one line and its wrap"), MdBlock.Paragraph("a second paragraph")),
            blocks,
        )
    }

    @Test
    fun `emphasis and inline code become spans`() {
        val spans = inlineMarkdown("run **npm test** with `--watch` on")

        assertEquals("run ", spans[0].text)
        assertTrue(spans[1].bold)
        assertEquals("npm test", spans[1].text)
        assertTrue(spans.single { it.code }.text == "--watch")
    }

    /** The reason this is not a naive scan: prose is full of characters markdown also uses. */
    @Test
    fun `arithmetic and snake case survive`() {
        assertEquals(
            "2 * 3 * 4 is some_variable_name",
            inlineMarkdown("2 * 3 * 4 is some_variable_name").joinToString("") { it.text },
        )
        assertTrue(inlineMarkdown("2 * 3 * 4 is some_variable_name").none { it.italic })
    }

    @Test
    fun `a link keeps its target and drops its brackets`() {
        val span = inlineMarkdown("see [the docs](https://example.com/x) first")[1]
        assertEquals("the docs", span.text)
        assertEquals("https://example.com/x", span.link)
    }

    @Test
    fun `half of a bold run is already bold`() {
        val spans = inlineMarkdown("this is **half writ")
        assertEquals("half writ", spans.last().text)
        assertTrue(spans.last().bold)
    }
    // ---- tables ----------------------------------------------------------

    @Test
    fun `a pipe table becomes a table block`() {
        val table = parseMarkdown(
            """
            | Stage | Time |
            |---|---|
            | `d8` | ~59 min |
            | `ecj` | ~16 min |
            """.trimIndent(),
        ).single() as MdBlock.Table

        assertEquals(listOf("Stage", "Time"), table.headers)
        assertEquals(listOf(listOf("`d8`", "~59 min"), listOf("`ecj`", "~16 min")), table.rows)
    }

    @Test
    fun `alignment markers are read from the separator`() {
        val table = parseMarkdown(
            """
            | a | b | c | d |
            |:--|:-:|--:|---|
            | 1 | 2 | 3 | 4 |
            """.trimIndent(),
        ).single() as MdBlock.Table

        assertEquals(
            listOf(MdAlign.Start, MdAlign.Center, MdAlign.End, MdAlign.Start),
            table.alignments,
        )
    }

    /** Tables arrive a line at a time, and every prefix has to render as something sane. */
    @Test
    fun `a header with no separator yet is still prose`() {
        val blocks = parseMarkdown("| Stage | Time |")

        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun `a table with no rows yet is a table`() {
        val table = parseMarkdown(
            """
            | Stage | Time |
            |---|---|
            """.trimIndent(),
        ).single() as MdBlock.Table

        assertEquals(listOf("Stage", "Time"), table.headers)
        assertTrue(table.rows.isEmpty())
    }

    /** A row still being typed is short; one cell too many is a slip. Both are shaped to fit. */
    @Test
    fun `ragged rows are squared off against the header`() {
        val table = parseMarkdown(
            """
            | a | b | c |
            |---|---|---|
            | 1 |
            | 1 | 2 | 3 | 4 |
            """.trimIndent(),
        ).single() as MdBlock.Table

        assertEquals(listOf("1", "", ""), table.rows[0])
        assertEquals(listOf("1", "2", "3"), table.rows[1])
    }

    /** The separator is what makes a table. Without it a pipe is punctuation. */
    @Test
    fun `prose containing a pipe is not a table`() {
        val blocks = parseMarkdown("Use `a | b` for either one.")

        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun `an escaped pipe stays inside its cell`() {
        val table = parseMarkdown(
            """
            | expression | meaning |
            |---|---|
            | a \| b | either |
            """.trimIndent(),
        ).single() as MdBlock.Table

        assertEquals(listOf("a | b", "either"), table.rows.single())
    }

    @Test
    fun `a table ends at the blank line and the text after it survives`() {
        val blocks = parseMarkdown(
            """
            | a |
            |---|
            | 1 |

            And that is the summary.
            """.trimIndent(),
        )

        assertTrue(blocks[0] is MdBlock.Table)
        assertEquals("And that is the summary.", (blocks[1] as MdBlock.Paragraph).text)
    }

    /** Without the table branch running first, this separator reads as a horizontal rule. */
    @Test
    fun `a separator is not mistaken for a horizontal rule`() {
        val blocks = parseMarkdown(
            """
            | a | b |
            |---|---|
            | 1 | 2 |
            """.trimIndent(),
        )

        assertTrue(blocks.none { it is MdBlock.Rule })
    }

}
