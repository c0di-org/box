package dev.localagent.workstation.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The export is the one artefact of a session that leaves the phone, so its shape is settled here
 * rather than by opening one on a device. What these actually protect: that a saved file says what
 * the screen said, that a half-finished turn is marked as half-finished instead of just stopping,
 * and that one runaway `apt` cannot produce a file no editor will open.
 */
class TranscriptMarkdownTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `renders the exchange in order`() {
        val markdown = listOf(
            AgentEvent.UserMessage("e1", SESSION, AT, "What is in here?"),
            AgentEvent.AgentMessage("e2", SESSION, AT, "m1", "A real Debian.", complete = true),
        ).toTranscript(SESSION).toMarkdown("Look inside", "Claude Code", AT, utc)

        assertTrue(markdown.startsWith("# Look inside"))
        assertTrue(markdown.contains("**Agent:** Claude Code"))
        assertTrue(markdown.indexOf("What is in here?") < markdown.indexOf("A real Debian."))
    }

    @Test
    fun `a shell call carries its command and output`() {
        val markdown = listOf(
            AgentEvent.ToolCallStarted("e1", SESSION, AT, "c1", ToolCall.Shell("uname -a")),
            AgentEvent.ToolCallFinished(
                "e2", SESSION, AT, "c1",
                ToolOutcome.Success(summary = "aarch64", output = "Linux box 6.1.0\n"),
            ),
        ).toTranscript(SESSION).toMarkdown("Probe", timeZone = utc)

        assertTrue(markdown.contains("```sh\nuname -a\n```"))
        assertTrue(markdown.contains("Linux box 6.1.0"))
        assertTrue(markdown.contains("_aarch64_"))
    }

    /** Saving mid-turn is the normal case in Box, so the file has to admit it. */
    @Test
    fun `an unfinished call is marked rather than dropped`() {
        val markdown = listOf(
            AgentEvent.ToolCallStarted("e1", SESSION, AT, "c1", ToolCall.Shell("apt update")),
        ).toTranscript(SESSION).toMarkdown("Halfway", timeZone = utc)

        assertTrue(markdown.contains("_Still running when this was saved._"))
    }

    @Test
    fun `runaway output is trimmed from the middle`() {
        val markdown = listOf(
            AgentEvent.ToolCallStarted("e1", SESSION, AT, "c1", ToolCall.Shell("apt install")),
            AgentEvent.ToolCallFinished(
                "e2", SESSION, AT, "c1",
                ToolOutcome.Success(output = "HEAD" + "x".repeat(50_000) + "TAIL"),
            ),
        ).toTranscript(SESSION).toMarkdown("Noisy", timeZone = utc)

        assertTrue(markdown.length < 12_000)
        assertTrue(markdown.contains("characters trimmed"))
        assertTrue(markdown.contains("HEAD"))
        assertTrue(markdown.contains("TAIL"))
    }

    @Test
    fun `a permission decision is recorded with its answer`() {
        val ask = PermissionAsk.RunCommand(command = "rm -rf build", destructive = true)
        val markdown = listOf(
            AgentEvent.PermissionRequested("e1", SESSION, AT, "r1", ask),
            AgentEvent.PermissionResolved("e2", SESSION, AT, "r1", PermissionDecision.Deny),
        ).toTranscript(SESSION).toMarkdown("Careful", timeZone = utc)

        assertTrue(markdown.contains("rm -rf build"))
        assertTrue(markdown.contains("**You declined this.**"))
    }

    @Test
    fun `a sub-agent nests under its parent`() {
        val markdown = listOf(
            AgentEvent.ToolCallStarted("e1", SESSION, AT, "a1", ToolCall.Task("Find the parser")),
            AgentEvent.AgentMessage("e2", SESSION, AT, "m1", "Found it.", complete = true, subAgentId = "a1"),
        ).toTranscript(SESSION).toMarkdown("Delegated", timeZone = utc)

        assertTrue(markdown.contains("## Sub-agent · Find the parser"))
        // One level deeper than the card that contains it.
        assertTrue(markdown.contains("### Agent"))
    }

    @Test
    fun `an empty transcript still names itself`() {
        val markdown = Transcript(SESSION).toMarkdown("Nothing yet", timeZone = utc)

        assertTrue(markdown.startsWith("# Nothing yet"))
        assertFalse(markdown.contains("Still working"))
    }

    @Test
    fun `the suggested file name is a slug and a stamp`() {
        val name = transcriptFileName("Fix the login bug!", AT)
        assertTrue(name, name.startsWith("fix-the-login-bug-"))
        assertTrue(name.endsWith(".md"))
        // Six words is the cap, so a sentence of a title does not become a sentence of a file name.
        assertTrue(transcriptFileName("a b c d e f g h", AT).startsWith("a-b-c-d-e-f-"))
        assertTrue(transcriptFileName("", AT).startsWith("box-task-"))
    }

    private companion object {
        const val SESSION = "s-test"
        const val AT = 1_700_000_000_000L
    }
}
