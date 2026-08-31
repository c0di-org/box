package dev.localagent.workstation.agent

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Re-attaching to a session that ran while the UI process was dead.
 *
 * Android kills the UI whenever it likes, and Box's promise is that you can start work, pocket the
 * phone, and come back to it. That only holds if the transcript you come back to is exactly what
 * happened — every line once, in order, whether it arrived from the log or over the wire.
 */
class SessionLogCursorTest {
    @get:Rule val folder = TemporaryFolder()

    private fun log(contents: String) = folder.newFile().apply { writeText(contents) }

    @Test
    fun `a log written while nobody was attached is replayed in full`() {
        val cursor = SessionLogCursor()

        val lines = cursor.readFile(log("one\ntwo\nthree\n"))

        assertEquals(listOf("one", "two", "three"), lines)
    }

    @Test
    fun `a chunk already in the file is not delivered twice`() {
        val cursor = SessionLogCursor()
        val file = log("one\ntwo\n")

        cursor.readFile(file)
        // The live callback re-announces both lines: it does not know what the reader already saw.
        val replayed = cursor.accept(offset = 0, bytes = "one\ntwo\n".toByteArray())

        assertEquals(emptyList<String>(), replayed)
    }

    @Test
    fun `a chunk straddling the point the file was read contributes only its tail`() {
        val cursor = SessionLogCursor()
        // The file was read mid-write: it ends part-way through the second line.
        val file = log("one\ntw")

        val fromFile = cursor.readFile(file)
        val fromWire = cursor.accept(offset = 4, bytes = "two\nthree\n".toByteArray())

        assertEquals(listOf("one"), fromFile)
        // "tw" was held back as a partial line and completed by the wire's tail — not duplicated.
        assertEquals(listOf("two", "three"), fromWire)
    }

    @Test
    fun `a line split across two chunks is held back until it is whole`() {
        val cursor = SessionLogCursor()

        val first = cursor.accept(0, """{"type":"mes""".toByteArray())
        val second = cursor.accept(12, """sage","text":"hi"}""".toByteArray())
        val third = cursor.accept(30, "\n".toByteArray())

        // A half-line is not JSON. Emitting it would put a parse failure in the transcript.
        assertEquals(emptyList<String>(), first)
        assertEquals(emptyList<String>(), second)
        assertEquals(listOf("""{"type":"message","text":"hi"}"""), third)
    }

    @Test
    fun `a multi-byte character split across chunks survives`() {
        val cursor = SessionLogCursor()
        val text = "don't panic — it's fine\n".toByteArray(Charsets.UTF_8)
        // The em dash is three bytes; cut through the middle of it.
        val split = text.indexOf('—'.code.toByte()).let { if (it > 0) it else 14 }

        cursor.accept(0, text.copyOfRange(0, split + 1))
        val lines = cursor.accept((split + 1).toLong(), text.copyOfRange(split + 1, text.size))

        // Decoding per chunk would have produced a replacement character here.
        assertEquals(listOf("don't panic — it's fine"), lines)
    }

    @Test
    fun `a second read picks up only what was appended since the first`() {
        val cursor = SessionLogCursor()
        val file = log("one\n")

        val first = cursor.readFile(file)
        file.appendText("two\n")
        val second = cursor.readFile(file)

        assertEquals(listOf("one"), first)
        assertEquals(listOf("two"), second)
    }

    @Test
    fun `a session with no log yet reads as empty rather than failing`() {
        val cursor = SessionLogCursor()

        // A session that has produced nothing, or whose log was never created, is a normal state.
        assertEquals(emptyList<String>(), cursor.readFile(folder.root.resolve("missing.ndjson")))
        assertEquals(0L, cursor.consumed)
    }

    @Test
    fun `the whole transcript is delivered once across a dead and restarted reader`() {
        val file = log("")
        val written = StringBuilder()
        fun write(line: String) {
            val at = written.length.toLong()
            written.append(line).append('\n')
            file.writeText(written.toString())
            check(at >= 0)
        }

        // Session runs while a first reader watches.
        val first = SessionLogCursor()
        write("a")
        val seenByFirst = first.readFile(file)

        // UI process dies; the agent keeps working.
        write("b")
        write("c")

        // A new process attaches and reads from scratch.
        val second = SessionLogCursor()
        val seenBySecond = second.readFile(file)

        assertEquals(listOf("a"), seenByFirst)
        assertEquals(listOf("a", "b", "c"), seenBySecond)
    }

    @Test
    fun `a chunk that never arrived is reported rather than coerced away`() {
        val cursor = SessionLogCursor()
        cursor.accept(0, "one\n".toByteArray())

        // The next chunk starts four bytes further on than the reader has got to. Those four bytes
        // are a chunk `tryEmit` dropped on a full buffer.
        assertEquals(4L, cursor.gapBefore(offset = 8))
        // An overlap is not a gap, and neither is a chunk that lands exactly on the watermark.
        assertEquals(0L, cursor.gapBefore(offset = 4))
        assertEquals(0L, cursor.gapBefore(offset = 0))
    }

    @Test
    fun `a chunk ahead of the watermark does not move it`() {
        val cursor = SessionLogCursor()
        cursor.accept(0, "one\n".toByteArray())

        // Refused, because consuming it would push the watermark past the missing bytes and make
        // the loss permanent -- the later re-read would skip them too.
        assertEquals(emptyList<String>(), cursor.accept(offset = 8, bytes = "three\n".toByteArray()))
        assertEquals(4L, cursor.consumed)
        // So the gap is still reported, and the next chunk recovers it rather than compounding it.
        assertEquals(4L, cursor.gapBefore(offset = 8))
    }

    @Test
    fun `a chunk dropped mid-line is recovered whole from the log, never spliced`() {
        // The positive test #75 asked for, and the shape that made the old behaviour dangerous:
        // the drop falls *inside* a line, so the truncated head was held in `pending` and welded
        // onto the next survivor's first fragment. The result either vanished at the parser or --
        // worse -- parsed, and became a transcript line nobody emitted.
        val file = log("")
        val cursor = SessionLogCursor()

        // The writer appends and flushes before it announces, so every byte is on disk by the time
        // the reader hears about it. That is what makes recovery possible at all.
        val one = """{"type":"text","text":"first"}""" + "\n"
        val two = """{"type":"text","text":"second"}""" + "\n"
        val three = """{"type":"text","text":"third"}""" + "\n"
        file.writeText(one + two + three)

        val seen = mutableListOf<String>()
        fun announce(offset: Long, bytes: ByteArray) {
            // Exactly what `events()` does with a live chunk.
            if (cursor.gapBefore(offset) > 0) seen += cursor.readFile(file)
            seen += cursor.accept(offset, bytes)
        }

        // The first chunk is a whole line and half of the next.
        val firstChunk = (one + two.take(12)).toByteArray()
        announce(0, firstChunk)
        // The middle chunk -- the rest of line two and the start of line three -- is dropped. It is
        // never announced at all.
        val dropped = (two.drop(12) + three.take(8)).toByteArray()
        // The chunk after it arrives with an offset past everything that went missing.
        announce((firstChunk.size + dropped.size).toLong(), three.drop(8).toByteArray())

        // Every line, once, whole. Not a `second` welded to a `third`.
        assertEquals(listOf(one, two, three).map { it.trimEnd('\n') }, seen)
    }

    @Test
    fun `a reader with no log to go back to skips the gap rather than stalling on it`() {
        // The status reader's case: it watches the live stream only, and a reattachment hands it a
        // first chunk stamped far past zero because the log already holds a conversation. There is
        // nothing behind that it could read even if it wanted to.
        val cursor = SessionLogCursor()

        assertEquals(400L, cursor.gapBefore(offset = 400))
        cursor.resyncTo(400)

        assertEquals(0L, cursor.gapBefore(offset = 400))
        assertEquals(listOf("live"), cursor.accept(400, "live\n".toByteArray()))
    }

    @Test
    fun `a skipped gap does not weld the line it interrupted onto the next one`() {
        val cursor = SessionLogCursor()
        cursor.accept(0, """{"type":"tex""".toByteArray())

        // Those twelve bytes are the head of a line whose tail is inside the bytes being skipped.
        // Keeping them would splice them onto whatever arrives next.
        cursor.resyncTo(80)

        val whole = """{"type":"whole"}"""
        assertEquals(listOf(whole), cursor.accept(80, (whole + "\n").toByteArray()))
    }

    @Test
    fun `a resumed session continues the log's numbering rather than starting over`() {
        // What went wrong on a real phone: the UI process was replaced, the session was re-opened
        // against the same log, and the new writer counted its bytes from zero while the file
        // already held thirteen kilobytes. Every chunk it announced looked, to this cursor, like
        // something it had read long ago -- so a live conversation went silent while its log kept
        // growing. The fix belongs to the writer (AgentSessionHost starts at the file's length);
        // this pins the rule the writer has to satisfy.
        val file = log("one\ntwo\n")
        val cursor = SessionLogCursor()
        assertEquals(listOf("one", "two"), cursor.readFile(file))

        val resumedAt = file.length()
        assertEquals(resumedAt, cursor.consumed)
        assertEquals(listOf("three"), cursor.accept(resumedAt, "three\n".toByteArray()))

        // And the failure it replaces: numbering restarted at zero, silently swallowed.
        assertEquals(emptyList<String>(), SessionLogCursor().apply { readFile(file) }.accept(0L, "three\n".toByteArray()))
    }
}
