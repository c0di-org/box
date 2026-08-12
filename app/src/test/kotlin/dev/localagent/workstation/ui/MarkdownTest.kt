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
}
