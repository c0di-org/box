package dev.localagent.runtime.qemu.shared

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * A whole sync, with no VM and no phone.
 *
 * The box is a map in memory rather than a second directory, so a test can hand a file a stamp
 * that a real filesystem would never let it have — which is the only way to reach the disagreement
 * case deliberately instead of by racing two clocks.
 */
class SharedFolderSyncTest {

    @get:Rule val temporary = TemporaryFolder()

    /** A box that remembers what it was given, and stamps each write with a rising clock. */
    private class FakeBox : BoxFiles {
        val files = linkedMapOf<String, ByteArray>()
        val stamps = linkedMapOf<String, SharedSync.Stamp>()
        var clock = 100L
        var refuse: String? = null

        fun put(path: String, text: String, modified: Long = clock++) {
            files[path] = text.toByteArray()
            stamps[path] = SharedSync.Stamp(text.length.toLong(), modified)
        }

        fun text(path: String) = files[path]?.decodeToString()

        override suspend fun snapshot() = LinkedHashMap(stamps)
        override suspend fun read(path: String) = files.getValue(path)
        override suspend fun write(path: String, bytes: ByteArray) {
            if (path == refuse) throw IOException("File exceeds the 64 MiB limit")
            files[path] = bytes
            stamps[path] = SharedSync.Stamp(bytes.size.toLong(), clock++)
        }
    }

    private lateinit var folder: File
    private lateinit var box: FakeBox
    private lateinit var records: SharedSyncRecords
    private lateinit var sync: SharedFolderSync

    private fun setUpSync() {
        folder = temporary.newFolder("shared")
        box = FakeBox()
        // Deliberately outside the shared folder: it must never appear in the user's Files app.
        records = SharedSyncRecords(File(temporary.root, "shared-sync.json"))
        sync = SharedFolderSync(folder, box, records)
    }

    private fun onPhone(path: String, text: String) {
        val file = File(folder, path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun phoneText(path: String) = File(folder, path).takeIf { it.isFile }?.readText()

    @Test
    fun `files on the phone go into the box, and a second pass has nothing to do`() {
        setUpSync()
        onPhone("notes.md", "hello")
        onPhone("src/main.kt", "fun main() {}")

        val first = runBlocking { sync.run() }
        assertEquals(listOf("notes.md", "src/main.kt"), first.pushedIn)
        assertEquals("hello", box.text("notes.md"))
        assertEquals("fun main() {}", box.text("src/main.kt"))

        val second = runBlocking { sync.run() }
        assertTrue("a settled folder should be quiet", second.quiet)
    }

    @Test
    fun `a file the agent made is brought out to the phone`() {
        setUpSync()
        runBlocking { sync.run() }

        box.put("report.md", "what I found")
        val outcome = runBlocking { sync.run() }

        assertEquals(listOf("report.md"), outcome.broughtOut)
        assertEquals("what I found", phoneText("report.md"))
        // Nothing else appeared: a file is staged outside the folder and moved in, so a partial
        // write is never visible to the Files app and never mistaken for one of the user's own.
        assertEquals(listOf("report.md"), folder.list()?.sorted())
        assertTrue(runBlocking { sync.run() }.quiet)
    }

    @Test
    fun `when both sides changed the phone wins and the box's version is kept, never deleted`() {
        setUpSync()
        onPhone("notes.md", "mine")
        runBlocking { sync.run() }

        // Both sides move before the next pass: the user edits on the phone while the agent
        // rewrites the same file in the box.
        onPhone("notes.md", "mine, edited")
        box.put("notes.md", "the agent's rewrite")

        val outcome = runBlocking { sync.run() }

        assertEquals(listOf("notes.md.from-box"), outcome.kept)
        assertEquals("mine, edited", phoneText("notes.md"))
        assertEquals("the agent's rewrite", phoneText("notes.md.from-box"))
        assertEquals("the phone's version wins in the box", "mine, edited", box.text("notes.md"))

        // The copy is an ordinary file: the next pass carries it into the box like any other.
        val after = runBlocking { sync.run() }
        assertEquals(listOf("notes.md.from-box"), after.pushedIn)
        assertEquals("the agent's rewrite", box.text("notes.md.from-box"))
    }

    @Test
    fun `two sides that drifted to the same bytes are not a disagreement`() {
        setUpSync()
        onPhone("notes.md", "same")
        runBlocking { sync.run() }

        onPhone("notes.md", "same")
        box.put("notes.md", "same")

        val outcome = runBlocking { sync.run() }

        assertTrue(outcome.kept.isEmpty())
        assertFalse(File(folder, "notes.md.from-box").exists())
        assertTrue(runBlocking { sync.run() }.quiet)
    }

    @Test
    fun `deleting a file on the phone does not bring it back from the box`() {
        setUpSync()
        onPhone("notes.md", "hello")
        runBlocking { sync.run() }

        assertTrue(File(folder, "notes.md").delete())
        val outcome = runBlocking { sync.run() }

        assertTrue(outcome.quiet)
        assertEquals("the box keeps its copy", "hello", box.text("notes.md"))
        assertEquals("the phone stays deleted", null, phoneText("notes.md"))
        // And it is not resurrected on the pass after that either.
        assertTrue(runBlocking { sync.run() }.quiet)
    }

    @Test
    fun `a copy that fails is reported and retried on the next pass`() {
        setUpSync()
        onPhone("big.bin", "pretend this is enormous")
        box.refuse = "big.bin"

        val first = runBlocking { sync.run() }
        assertEquals(listOf("big.bin"), first.trouble.map { it.path })
        assertEquals("File exceeds the 64 MiB limit", first.trouble.single().reason)

        box.refuse = null
        val second = runBlocking { sync.run() }
        assertEquals(listOf("big.bin"), second.pushedIn)
    }

    @Test
    fun `a guest path that climbs out of the folder is refused`() {
        setUpSync()
        // agentd's own guard makes this unreachable in practice. It is checked again here because
        // this is the side that would write the file, and a listing is not a trusted input.
        box.put("../escaped", "no")

        val outcome = runBlocking { sync.run() }

        assertEquals(listOf("../escaped"), outcome.trouble.map { it.path })
        assertFalse(File(temporary.root, "escaped").exists())
    }

    @Test
    fun `the last pass is remembered so the app can say what happened`() {
        setUpSync()
        onPhone("notes.md", "hello")
        val outcome = runBlocking { sync.run() }

        val remembered = records.lastOutcome()
        assertEquals(outcome.atMillis, remembered?.atMillis)
        assertEquals(listOf("notes.md"), remembered?.pushedIn)
    }
}
