package dev.localagent.runtime.qemu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `image.json` is allowed to say, and what happens when it says something else.
 *
 * The manifest is the only thing standing between the app and four filenames it used to hardcode,
 * so a manifest it misreads is a guest that boots wrong on a phone rather than a build that fails
 * on a laptop. Everything rejected here is rejected loudly for that reason.
 */
class GuestImageManifestTest {

    private fun manifest(
        id: String = "box-minimal-claude",
        version: String = "5de1e0982b11479b",
        schema: Int = 1,
        payloads: String = DEFAULT_PAYLOADS,
        contains: String = DEFAULT_CONTAINS,
    ) = """
        {
          "schema": $schema,
          "id": "$id",
          "version": "$version",
          "name": "Box Minimal",
          "description": "Debian bookworm arm64 with agentd and the Claude Code harness.",
          "payloads": [$payloads],
          "contains": $contains
        }
    """.trimIndent()

    @Test
    fun `a complete manifest parses into roles rather than positions`() {
        val parsed = GuestImageManifest.parse(manifest())

        assertEquals("box-minimal-claude", parsed.id)
        assertEquals("5de1e0982b11479b", parsed.version)
        assertEquals("Box Minimal", parsed.name)
        assertEquals(
            GuestImageRole.entries.toSet(),
            parsed.payloads.map { it.role }.toSet(),
        )
        // The whole point: the system disk is the system disk because the manifest says so, not
        // because it is third in a list three files agreed on by hand.
        assertEquals("base-system.qcow2", parsed.payload(GuestImageRole.SYSTEM).assetName)
        assertEquals("workspace.qcow2", parsed.payload(GuestImageRole.WORKSPACE).assetName)
        assertEquals(446_693_376L, parsed.payload(GuestImageRole.SYSTEM).bytes)
    }

    @Test
    fun `digests are normalised so a manifest written in upper case still matches`() {
        val upper = manifest(
            payloads = DEFAULT_PAYLOADS.replace(SYSTEM_SHA, SYSTEM_SHA.uppercase()),
        )
        assertEquals(SYSTEM_SHA, GuestImageManifest.parse(upper).payload(GuestImageRole.SYSTEM).sha256)
    }

    @Test
    fun `what the image advertises is carried even though nothing reads it yet`() {
        val contents = GuestImageManifest.parse(manifest()).contents

        assertTrue(contents.desktop)
        assertEquals(1, contents.harnesses.size)
        assertEquals("claude-code", contents.harnesses.single().id)
        assertEquals(
            "/opt/local-agent/harness/box-claude-harness.mjs",
            contents.harnesses.single().entry,
        )
        assertEquals("/usr/src/box", contents.sourcePath)
    }

    @Test
    fun `an image that says nothing about itself is still installable`() {
        // `contains` is for a picker that does not exist yet; a manifest without it is not broken.
        val parsed = GuestImageManifest.parse(manifest(contains = "{}"))
        assertEquals(false, parsed.contents.desktop)
        assertEquals(emptyList<GuestHarness>(), parsed.contents.harnesses)
        assertNull(parsed.contents.sourceCommit)
    }

    @Test
    fun `the storage key is the id alone, so an update keeps the same workspace`() {
        // The load-bearing assertion in this file. Putting the version in the path would give
        // every rebuilt image a directory with no workspace in it, and the user's Linux machine
        // would be quietly replaced by an empty one on each update — the exact accident the
        // manifest exists to prevent.
        val first = GuestImageManifest.parse(manifest(version = "aaaaaaaaaaaaaaaa"))
        val second = GuestImageManifest.parse(manifest(version = "bbbbbbbbbbbbbbbb"))

        assertEquals("box-minimal-claude", first.storageKey)
        assertEquals(first.storageKey, second.storageKey)
        // ...while still being told apart, which is what decides whether to install.
        assertTrue(first.identity != second.identity)
    }

    @Test
    fun `a different image is a different key, so both can sit on disk`() {
        val claude = GuestImageManifest.parse(manifest(id = "box-minimal-claude"))
        val ubuntu = GuestImageManifest.parse(manifest(id = "bare-ubuntu"))
        assertTrue(claude.storageKey != ubuntu.storageKey)
    }

    @Test
    fun `an id that is not usable as a directory name is refused`() {
        // This id becomes a path element under the app's private files. Nothing writes a manifest
        // Box did not build today, but the point of describing images is that one day something
        // else might, and `..` is the difference between a keyed layout and an arbitrary write.
        listOf("../../etc", "Box-Minimal", "box minimal", "", "box/minimal").forEach { id ->
            val failure = runCatching { GuestImageManifest.parse(manifest(id = id)) }.exceptionOrNull()
            assertTrue("expected '$id' to be refused", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `an id that would collide with a legacy file is refused`() {
        // `images/kernel` is where the pre-manifest layout put the kernel, and `images/<id>/` is
        // where the keyed layout wants a directory.
        val failure = runCatching { GuestImageManifest.parse(manifest(id = "kernel")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a manifest missing a role the runtime must boot is refused`() {
        val withoutWorkspace = PAYLOADS.filterNot { it.contains("workspace") }.joinToString(",\n")
        val failure = runCatching {
            GuestImageManifest.parse(manifest(payloads = withoutWorkspace))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("workspace"))
    }

    @Test
    fun `a manifest naming the same role twice is refused`() {
        val duplicated = "$DEFAULT_PAYLOADS, ${payload("system", "other.qcow2", SYSTEM_SHA)}"
        val failure = runCatching {
            GuestImageManifest.parse(manifest(payloads = duplicated))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a payload without a usable digest is refused`() {
        val unhashed = DEFAULT_PAYLOADS.replace(SYSTEM_SHA, "not-a-digest")
        val failure = runCatching {
            GuestImageManifest.parse(manifest(payloads = unhashed))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a payload naming a path rather than a file is refused`() {
        // Payload names are resolved against the APK's asset directory.
        val escaping = DEFAULT_PAYLOADS.replace("base-system.qcow2", "../../base-system.qcow2")
        val failure = runCatching {
            GuestImageManifest.parse(manifest(payloads = escaping))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a schema this build does not know is refused rather than guessed at`() {
        val failure = runCatching { GuestImageManifest.parse(manifest(schema = 2)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `text that is not json is refused with something readable`() {
        val failure = runCatching { GuestImageManifest.parse("not json at all") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("not valid JSON"))
    }

    @Test
    fun `an installed record from a newer build is still recognised`() {
        // A device can be downgraded. The record it left behind may use a schema and roles this
        // build has never heard of, and it must still be able to recognise its own image rather
        // than reinstall over a machine that was already correct.
        val future = """
            {
              "schema": 99,
              "id": "box-minimal-claude",
              "version": "5de1e0982b11479b",
              "payloads": [ { "role": "nvram", "file": "nvram.bin", "sha256": "$SYSTEM_SHA" } ],
              "somethingNew": { "invented": "later" }
            }
        """.trimIndent()

        assertEquals(
            GuestImageIdentity("box-minimal-claude", "5de1e0982b11479b"),
            GuestImageManifest.parseIdentity(future),
        )
        // ...but it is still not something this build could install.
        assertTrue(runCatching { GuestImageManifest.parse(future) }.isFailure)
    }

    @Test
    fun `an unreadable record is no identity rather than a crash`() {
        assertNull(GuestImageManifest.parseIdentity("{ truncated"))
        assertNull(GuestImageManifest.parseIdentity("""{ "id": "box-minimal-claude" }"""))
    }

    private companion object {
        const val SYSTEM_SHA = "9c759460220887bdcf9935f470ec7eea2bf06896009e22a47a16fdbabb458ecb"
        const val KERNEL_SHA = "19923759e30bbc81a152863727b30ccc6b05c9c0b2597dc2704ea98679115b9b"
        const val INITRD_SHA = "8e06a1168f865e4af6ffa82b9fe5543cca47089fff85d8eb88c29947c4b5afe3"
        const val WORKSPACE_SHA = "12b16e23060248e2f2a10693dd119ccb573dc1e0fc6fe2496cdd85a0b6cbda30"

        fun payload(role: String, file: String, sha: String, bytes: Long = 1) =
            """{ "role": "$role", "file": "$file", "sha256": "$sha", "bytes": $bytes }"""

        val PAYLOADS = listOf(
            payload("kernel", "kernel", KERNEL_SHA, 20_000_000),
            payload("initrd", "initrd.img", INITRD_SHA, 30_000_000),
            payload("system", "base-system.qcow2", SYSTEM_SHA, 446_693_376),
            payload("workspace", "workspace.qcow2", WORKSPACE_SHA, 2_000_000),
        )

        val DEFAULT_PAYLOADS = PAYLOADS.joinToString(",\n")

        val DEFAULT_CONTAINS = """
            {
              "desktop": true,
              "harnesses": [
                {
                  "id": "claude-code",
                  "name": "Claude Code",
                  "entry": "/opt/local-agent/harness/box-claude-harness.mjs"
                }
              ],
              "source": { "commit": "deadbeef", "path": "/usr/src/box" }
            }
        """.trimIndent()
    }
}
