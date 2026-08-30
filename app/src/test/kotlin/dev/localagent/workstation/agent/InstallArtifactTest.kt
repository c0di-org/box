package dev.localagent.workstation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one artifact whose button installs software, so the wire checks it a second time.
 *
 * The harness validates before emitting, and that would normally be enough. It is not enough here:
 * the guest image and the APK ship independently, so the app can meet a guest older or newer than
 * itself, and "the sender promised" is not a property the receiving side can verify. The cost of
 * re-checking is two conditions; the cost of trusting is an install button pointed at whatever a
 * line in a log said.
 */
class InstallArtifactTest {

    @Test
    fun `an apk in the shared folder becomes an install button`() {
        val artifact = artifact("""{"type":"artifact","kind":"install","guestPath":"/workspace/shared/app.apk","name":"app.apk"}""")

        assertEquals(Artifact.Install("/workspace/shared/app.apk", "app.apk"), artifact)
    }

    /** The shared folder is the only route bytes leave the box by. Elsewhere there is nothing to install. */
    @Test
    fun `an apk outside the shared folder is refused`() {
        assertNull(artifact("""{"type":"artifact","kind":"install","guestPath":"/workspace/build/app.apk"}"""))
        assertNull(artifact("""{"type":"artifact","kind":"install","guestPath":"/tmp/app.apk"}"""))
        assertNull(artifact("""{"type":"artifact","kind":"install","guestPath":"/workspace/sharedish/app.apk"}"""))
    }

    @Test
    fun `something that is not an apk is refused`() {
        assertNull(artifact("""{"type":"artifact","kind":"install","guestPath":"/workspace/shared/notes.md"}"""))
        assertNull(artifact("""{"type":"artifact","kind":"install","guestPath":"/workspace/shared/app.apk.txt"}"""))
    }

    @Test
    fun `a missing path is refused rather than defaulted`() {
        assertNull(artifact("""{"type":"artifact","kind":"install","name":"app.apk"}"""))
    }

    /** Agents name files however they like; the check is on the extension, not its case. */
    @Test
    fun `the extension check is case insensitive`() {
        val artifact = artifact("""{"type":"artifact","kind":"install","guestPath":"/workspace/shared/App.APK"}""")

        assertEquals("App.APK", (artifact as Artifact.Install).name)
    }

    @Test
    fun `the name falls back to the file name`() {
        val artifact = artifact("""{"type":"artifact","kind":"install","guestPath":"/workspace/shared/out/box.apk"}""")

        assertEquals("box.apk", (artifact as Artifact.Install).name)
    }

    private fun artifact(line: String): Artifact? =
        (HarnessWire.parse(
            line,
            HarnessWire.Context(
                sessionId = "s",
                harnessId = "claude-code",
                title = "A task",
                workingDirectory = "/workspace",
            ),
            ordinal = 1,
        ) as? AgentEvent.ArtifactOffered)?.artifact
}
