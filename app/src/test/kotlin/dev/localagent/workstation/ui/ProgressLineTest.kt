package dev.localagent.workstation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one line a running tool card gets to say for itself.
 *
 * It replaces "Running…", which was true for twenty minutes at a stretch and told nobody anything,
 * so the bar it has to clear is that the line is worth reading — the newest real output, not the
 * blank after it and not a carriage-return progress bar's entire history.
 */
class ProgressLineTest {

    @Test
    fun `the newest line wins`() {
        assertEquals("linking", progressLine("compiling\nassembling\nlinking"))
    }

    /** Tools end their output with a newline; the answer is never the empty string after it. */
    @Test
    fun `trailing blank lines are skipped`() {
        assertEquals("done", progressLine("working\ndone\n\n   \n"))
    }

    /** Every downloader redraws one line. Quoting the whole redraw is quoting its history. */
    @Test
    fun `a redrawn progress line keeps only its final state`() {
        assertEquals("94%", progressLine("fetching\n 12%\r 47%\r 94%"))
    }

    @Test
    fun `a long line is cut to fit a phone`() {
        val line = progressLine("x".repeat(200))!!

        assertEquals(81, line.length)
        assertEquals('…', line.last())
    }

    @Test
    fun `a line that fits is left exactly alone`() {
        assertEquals("added 512 packages", progressLine("added 512 packages"))
    }

    /** Before a tool has said anything, the card falls back to naming what it is doing. */
    @Test
    fun `nothing to quote is null rather than empty`() {
        assertNull(progressLine(""))
        assertNull(progressLine("\n\n"))
        assertNull(progressLine("   \n\t\n"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("built", progressLine("  built  \n"))
    }
}
