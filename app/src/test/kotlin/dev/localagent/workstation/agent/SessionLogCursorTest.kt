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
