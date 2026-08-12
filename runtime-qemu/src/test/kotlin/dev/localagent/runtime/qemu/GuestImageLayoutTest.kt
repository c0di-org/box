package dev.localagent.runtime.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Where an image's files land, and what happens to a box that was set up before images had names.
 *
 * The migration case is the one worth having a test for at all. Every device that has ever run Box
 * has its `/workspace` at `disks/workspace.qcow2`, and the keyed layout does not look there. Get
 * this wrong and the user's Linux machine is replaced by an empty one on the next app update, with
 * the app reporting a perfectly successful provision.
 */
class GuestImageLayoutTest {

    @get:Rule val temporary = TemporaryFolder()

    private lateinit var images: File
    private lateinit var disks: File
    private lateinit var layout: GuestImageLayout

    private val claude = manifest("box-minimal-claude", "aaaaaaaaaaaaaaaa")
    private val claudeRebuilt = manifest("box-minimal-claude", "bbbbbbbbbbbbbbbb")
    private val ubuntu = manifest("bare-ubuntu", "cccccccccccccccc")

    private fun setUpLayout() {
        images = temporary.newFolder("images")
        disks = temporary.newFolder("disks")
        layout = GuestImageLayout(images, disks)
    }

    private fun manifest(id: String, version: String) = GuestImageManifest.parse(
        """
        {
          "schema": 1,
          "id": "$id",
          "version": "$version",
          "name": "$id",
          "description": "",
          "payloads": [
            { "role": "kernel", "file": "kernel", "sha256": "${"a".repeat(64)}", "bytes": 1 },
            { "role": "initrd", "file": "initrd.img", "sha256": "${"b".repeat(64)}", "bytes": 1 },
            { "role": "system", "file": "base-system.qcow2", "sha256": "${"c".repeat(64)}", "bytes": 1 },
            { "role": "workspace", "file": "workspace.qcow2", "sha256": "${"d".repeat(64)}", "bytes": 1 }
          ]
        }
        """.trimIndent(),
    )

    private fun File.write(contents: String) = apply {
        parentFile?.mkdirs()
        writeText(contents)
    }

    @Test
    fun `an image keeps its immutable files with the images and its disks with the disks`() {
        setUpLayout()

        assertEquals(File(images, "box-minimal-claude/kernel"), layout.fileFor(claude, GuestImageRole.KERNEL))
        assertEquals(File(images, "box-minimal-claude/initrd.img"), layout.fileFor(claude, GuestImageRole.INITRD))
        assertEquals(File(disks, "box-minimal-claude/system.qcow2"), layout.fileFor(claude, GuestImageRole.SYSTEM))
        assertEquals(File(disks, "box-minimal-claude/workspace.qcow2"), layout.fileFor(claude, GuestImageRole.WORKSPACE))
    }

    @Test
    fun `a new version of an image resolves to the same files, so an update is an update`() {
        setUpLayout()

        GuestImageRole.entries.forEach { role ->
            assertEquals(
                "role $role moved between versions",
                layout.fileFor(claude, role),
                layout.fileFor(claudeRebuilt, role),
            )
        }
    }

    @Test
    fun `two different images share nothing`() {
        setUpLayout()

        GuestImageRole.entries.forEach { role ->
            assertTrue(layout.fileFor(claude, role) != layout.fileFor(ubuntu, role))
        }
    }

    @Test
    fun `installing one image cannot be mistaken for having installed another`() {
        setUpLayout()
        layout.writeRecord(claude, """{ "id": "box-minimal-claude", "version": "aaaaaaaaaaaaaaaa" }""")

        assertEquals(claude.identity, layout.installedIdentity(claude))
        assertNull(layout.installedIdentity(ubuntu))
    }

    @Test
    fun `a record is read back as the identity it was written with`() {
        setUpLayout()
        layout.writeRecord(claude, RECORD_JSON)

        assertEquals(GuestImageIdentity("box-minimal-claude", "aaaaaaaaaaaaaaaa"), layout.installedIdentity(claude))
        // Written whole, not reduced to two fields: a picker will want to know what an installed
        // image contains, and by then the APK describes a different one.
        assertTrue(layout.recordFor(claude).readText().contains("\"desktop\": true"))
    }

    @Test
    fun `a truncated record is no identity rather than a wrong one`() {
        setUpLayout()
        layout.recordFor(claude).write("""{ "id": "box-minimal""")

        assertNull(layout.installedIdentity(claude))
    }

    @Test
    fun `migration carries the user's workspace into the keyed layout`() {
        setUpLayout()
        // A device provisioned by any build before this change.
        File(images, "kernel").write("old kernel")
        File(images, "initrd.img").write("old initrd")
        File(disks, "system.qcow2").write("old system")
        File(disks, "workspace.qcow2").write("THE USER'S WORK")

        layout.migrateLegacy(claude)

        assertEquals("THE USER'S WORK", layout.fileFor(claude, GuestImageRole.WORKSPACE).readText())
        assertEquals("old system", layout.fileFor(claude, GuestImageRole.SYSTEM).readText())
        assertEquals("old kernel", layout.fileFor(claude, GuestImageRole.KERNEL).readText())
        assertEquals("old initrd", layout.fileFor(claude, GuestImageRole.INITRD).readText())
        // Moved, not copied: half a gigabyte should not be duplicated to change a path.
        assertFalse(File(disks, "workspace.qcow2").exists())
        assertFalse(File(images, "kernel").exists())
    }

    @Test
    fun `a migrated device still reads as having no known image`() {
        setUpLayout()
        File(disks, "workspace.qcow2").write("THE USER'S WORK")

        layout.migrateLegacy(claude)

        // Nothing on that device ever said what those bytes were, so claiming to know would be a
        // lie that skips installing the image the APK is actually carrying.
        assertNull(layout.installedIdentity(claude))
    }

    @Test
    fun `the proof build's system disk is migrated through both layouts`() {
        setUpLayout()
        File(images, "base-system.qcow2").write("proof image")

        layout.migrateLegacy(claude)

        assertEquals("proof image", layout.fileFor(claude, GuestImageRole.SYSTEM).readText())
        assertFalse(File(images, "base-system.qcow2").exists())
    }

    @Test
    fun `migrating twice never overwrites what the first pass produced`() {
        setUpLayout()
        File(disks, "workspace.qcow2").write("THE USER'S WORK")
        layout.migrateLegacy(claude)
        // A second legacy file appearing afterwards must not win over the migrated copy: a
        // half-finished migration that runs again is the case, and the keyed copy is the newer one.
        File(disks, "workspace.qcow2").write("a stale duplicate")

        layout.migrateLegacy(claude)

        assertEquals("THE USER'S WORK", layout.fileFor(claude, GuestImageRole.WORKSPACE).readText())
    }

    @Test
    fun `migration on a device that never had a box does nothing at all`() {
        setUpLayout()

        layout.migrateLegacy(claude)

        assertEquals(emptySet<GuestImageRole>(), layout.presentRoles(claude))
    }

    @Test
    fun `present roles are the ones actually on disk`() {
        setUpLayout()
        layout.fileFor(claude, GuestImageRole.KERNEL).write("k")
        layout.fileFor(claude, GuestImageRole.WORKSPACE).write("w")

        assertEquals(
            setOf(GuestImageRole.KERNEL, GuestImageRole.WORKSPACE),
            layout.presentRoles(claude),
        )
    }

    private companion object {
        val RECORD_JSON = """
            {
              "schema": 1,
              "id": "box-minimal-claude",
              "version": "aaaaaaaaaaaaaaaa",
              "contains": { "desktop": true }
            }
        """.trimIndent()
    }
}
