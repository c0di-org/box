package dev.localagent.workstation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fold is the contract between the event log and the screen, and a mistake in it is invisible
 * until a transcript renders wrong. These cover the four collapses the UI depends on.
 */
class TranscriptBuilderTest {

    @Test
    fun `streamed message collapses into one item`() {
        val transcript = listOf(
            message("e1", "m1", "I'm set", complete = false),
            message("e2", "m1", "I'm setting up", complete = false),
            message("e3", "m1", "I'm setting up the project.", complete = true),
        ).toTranscript(SESSION)

        assertEquals(1, transcript.items.size)
        val item = transcript.items.single() as TranscriptItem.Agent
        assertEquals("I'm setting up the project.", item.text)
        assertEquals(false, item.streaming)
    }

    @Test
    fun `tool call folds together with its progress and outcome`() {
        val transcript = listOf(
            AgentEvent.ToolCallStarted("e1", SESSION, 1, "c1", ToolCall.Shell("npm install")),
            AgentEvent.ToolCallProgress("e2", SESSION, 2, "c1", "added 512 packages\n"),
            AgentEvent.ToolCallFinished(
                "e3", SESSION, 3, "c1",
                ToolOutcome.Success(summary = "512 packages", output = "found 0 vulnerabilities\n"),
            ),
        ).toTranscript(SESSION)

        val tool = transcript.items.single() as TranscriptItem.Tool
        assertEquals("added 512 packages\nfound 0 vulnerabilities\n", tool.output)
        assertEquals(false, tool.running)
    }

    /** A result whose start event was lost to a reconnect still has to render. */
    @Test
    fun `orphaned tool result is kept rather than dropped`() {
        val transcript = listOf(
            AgentEvent.ToolCallFinished("e1", SESSION, 1, "c9", ToolOutcome.Success(summary = "done")),
        ).toTranscript(SESSION)

        val tool = transcript.items.single() as TranscriptItem.Tool
        assertEquals("c9", tool.callId)
        assertEquals(false, tool.running)
    }

    @Test
    fun `checklist updates in place instead of appending`() {
        val transcript = listOf(
            AgentEvent.TaskProgress("e1", SESSION, 1, "plan", listOf(TaskItem("clone", TaskState.Running))),
            message("e2", "m1", "working on it"),
            AgentEvent.TaskProgress("e3", SESSION, 3, "plan", listOf(TaskItem("clone", TaskState.Done))),
        ).toTranscript(SESSION)

        assertEquals(2, transcript.items.size)
        val checklist = transcript.items.first() as TranscriptItem.Checklist
        assertEquals(TaskState.Done, checklist.items.single().state)
        // Position is the first emission's, so the block does not jump down the transcript.
        assertEquals(1L, checklist.at)
    }

    @Test
    fun `permission clears once resolved and records the decision`() {
        val ask = PermissionAsk.RunCommand(command = "rm -rf build", destructive = true)
        val requested = listOf(
            AgentEvent.PermissionRequested("e1", SESSION, 1, "p1", ask),
        ).toTranscript(SESSION)

        assertEquals("p1", requested.pendingPermission?.requestId)
        assertTrue(requested.activity is AgentActivity.AwaitingPermission)

        val resolved = listOf(
            AgentEvent.PermissionRequested("e1", SESSION, 1, "p1", ask),
            AgentEvent.PermissionResolved("e2", SESSION, 2, "p1", PermissionDecision.Deny),
        ).toTranscript(SESSION)

        assertNull(resolved.pendingPermission)
        val record = resolved.items.single() as TranscriptItem.Permission
        assertEquals(PermissionDecision.Deny, record.decision)
    }

    @Test
    fun `consecutive artifact offers merge into one row`() {
        val transcript = listOf(
            AgentEvent.ArtifactOffered("e1", SESSION, 1, Artifact.Computer),
            AgentEvent.ArtifactOffered("e2", SESSION, 2, Artifact.Preview("http://localhost:5173/", 5173)),
        ).toTranscript(SESSION)

        val artifacts = transcript.items.single() as TranscriptItem.Artifacts
        assertEquals(2, artifacts.artifacts.size)
    }

    /**
     * A crash, an interrupt or a truncated replay all end a session with calls still open, and
     * nothing can ever arrive for them afterwards.
     */
    @Test
    fun `a session that ends closes the calls that never reported back`() {
        val transcript = listOf(
            AgentEvent.ToolCallStarted("e1", SESSION, 1, "c1", ToolCall.Shell("npm test")),
            AgentEvent.ToolCallStarted("e2", SESSION, 2, "c2", ToolCall.Shell("npm run build")),
            AgentEvent.ToolCallFinished("e3", SESSION, 3, "c2", ToolOutcome.Success(summary = "built")),
            AgentEvent.SessionEnded("e4", SESSION, 4, SessionOutcome.Interrupted),
        ).toTranscript(SESSION)

        val tools = transcript.items.filterIsInstance<TranscriptItem.Tool>()
        assertEquals(2, tools.size)
        assertTrue(tools.none { it.running })
        assertEquals(ToolOutcome.Cancelled, tools.first { it.callId == "c1" }.outcome)
        // The one that did report keeps what it said; only the orphan is settled.
        assertTrue(tools.first { it.callId == "c2" }.outcome is ToolOutcome.Success)
    }

    private fun message(id: String, messageId: String, text: String, complete: Boolean = true) =
        AgentEvent.AgentMessage(id, SESSION, 1, messageId, text, complete)

    private companion object {
        const val SESSION = "s-test"
    }
}
