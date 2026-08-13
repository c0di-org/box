package dev.localagent.workstation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fold is the contract between the event log and the screen, and a mistake in it is invisible
 * until a transcript renders wrong. These cover the collapses the UI depends on — including the
 * newest one, where a sub-agent's whole transcript folds into a single card in its parent's.
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

    // ---- several requests at once ------------------------------------------

    /**
     * The bug this pins was reported from a phone: an agent asked to run two commands, one was
     * approved, and the other could not be answered by anything — the sheet would not come back
     * because the transcript no longer knew the request existed.
     */
    @Test
    fun `two requests wait together, and answering one leaves the other waiting`() {
        val first = PermissionAsk.RunCommand(command = "npm install")
        val second = PermissionAsk.RunCommand(command = "npm run build")
        val transcript = listOf(
            AgentEvent.PermissionRequested("e1", SESSION, 1, "p1", first),
            AgentEvent.PermissionRequested("e2", SESSION, 2, "p2", second),
            AgentEvent.PermissionResolved("e3", SESSION, 3, "p2", PermissionDecision.Allow),
        ).toTranscript(SESSION)

        assertEquals(listOf("p1"), transcript.pendingPermissions.map { it.requestId })
        // And the agent is still blocked, which is what keeps the composer honest.
        assertEquals(AgentActivity.AwaitingPermission("p1"), transcript.activity)
        val answered = transcript.items
            .filterIsInstance<TranscriptItem.Permission>()
            .single { it.requestId == "p2" }
        assertEquals(PermissionDecision.Allow, answered.decision)
    }

    /** Oldest first: the sheet works through them in the order the agent asked. */
    @Test
    fun `the sheet is offered the request that has waited longest`() {
        val transcript = listOf(
            AgentEvent.PermissionRequested("e1", SESSION, 1, "p1", PermissionAsk.RunCommand("one")),
            AgentEvent.PermissionRequested("e2", SESSION, 2, "p2", PermissionAsk.RunCommand("two")),
        ).toTranscript(SESSION)

        assertEquals("p1", transcript.pendingPermission?.requestId)
        assertEquals(2, transcript.pendingPermissions.size)
    }

    /**
     * A parallel turn narrates itself while it is blocked. That narration used to wipe every
     * outstanding request, so a live question vanished from the UI while the agent kept waiting.
     */
    @Test
    fun `an activity line does not lose a request the agent is still blocked on`() {
        val transcript = listOf(
            AgentEvent.PermissionRequested("e1", SESSION, 1, "p1", PermissionAsk.RunCommand("one")),
            AgentEvent.ActivityChanged("e2", SESSION, 2, AgentActivity.Working("Installing")),
        ).toTranscript(SESSION)

        assertEquals("p1", transcript.pendingPermission?.requestId)
    }

    /** A run cannot be idle while it owes the user an answer, whatever else it has finished. */
    @Test
    fun `going idle with a request outstanding still reads as waiting on you`() {
        val transcript = listOf(
            AgentEvent.PermissionRequested("e1", SESSION, 1, "p1", PermissionAsk.RunCommand("one")),
            AgentEvent.ActivityChanged("e2", SESSION, 2, AgentActivity.Idle),
        ).toTranscript(SESSION)

        assertEquals(AgentActivity.AwaitingPermission("p1"), transcript.activity)
    }

    @Test
    fun `answering everything clears the block`() {
        val transcript = listOf(
            AgentEvent.PermissionRequested("e1", SESSION, 1, "p1", PermissionAsk.RunCommand("one")),
            AgentEvent.PermissionRequested("e2", SESSION, 2, "p2", PermissionAsk.RunCommand("two")),
            AgentEvent.PermissionResolved("e3", SESSION, 3, "p1", PermissionDecision.Allow),
            AgentEvent.PermissionResolved("e4", SESSION, 4, "p2", PermissionDecision.Deny),
        ).toTranscript(SESSION)

        assertTrue(transcript.pendingPermissions.isEmpty())
        assertEquals(AgentActivity.Idle, transcript.activity)
    }

    /** A session that ends with a question outstanding stops waiting for an answer to it. */
    @Test
    fun `ending the session drops what was still being asked`() {
        val transcript = listOf(
            AgentEvent.PermissionRequested("e1", SESSION, 1, "p1", PermissionAsk.RunCommand("one")),
            AgentEvent.SessionEnded("e2", SESSION, 2, SessionOutcome.Interrupted),
        ).toTranscript(SESSION)

        assertTrue(transcript.pendingPermissions.isEmpty())
    }

    // ---- is anything happening -------------------------------------------

    /**
     * The transcript is busy from the moment the user speaks, not from the moment the harness
     * gets round to saying so.
     *
     * `isBusy` is what draws the working indicator and puts Stop in the header, and there is a
     * gap after a turn is handed over where nothing has come back yet — sometimes a long one, on
     * a phone emulating a computer. Drawing an idle conversation across it is the version that is
     * actually wrong.
     */
    @Test
    fun `a message sent into a quiet session says the agent is working`() {
        val transcript = listOf(
            AgentEvent.ActivityChanged("e1", SESSION, 1, AgentActivity.Idle),
            AgentEvent.UserMessage("e2", SESSION, 2, "and now the tests"),
        ).toTranscript(SESSION)

        assertTrue(transcript.isBusy)
    }

    /** Speaking again reopens a session that had ended; the finished rule must not outlive it. */
    @Test
    fun `a message after the session ended puts the conversation back to work`() {
        val transcript = listOf(
            AgentEvent.SessionEnded("e1", SESSION, 1, SessionOutcome.Completed()),
            AgentEvent.UserMessage("e2", SESSION, 2, "one more thing"),
        ).toTranscript(SESSION)

        assertTrue(transcript.isBusy)
        assertNull(transcript.outcome)
    }

    /** What the harness is saying always outranks the guess above. */
    @Test
    fun `a message while an agent is mid-task does not flatten what it is doing`() {
        val transcript = listOf(
            AgentEvent.ActivityChanged("e1", SESSION, 1, AgentActivity.Working("Installing")),
            AgentEvent.UserMessage("e2", SESSION, 2, "also check the lockfile"),
        ).toTranscript(SESSION)

        assertEquals(AgentActivity.Working("Installing"), transcript.activity)
    }

    /** And it never talks over a question the user is being asked. */
    @Test
    fun `a message with a request outstanding still reads as waiting on you`() {
        val transcript = listOf(
            AgentEvent.PermissionRequested("e1", SESSION, 1, "p1", PermissionAsk.RunCommand("one")),
            AgentEvent.ActivityChanged("e2", SESSION, 2, AgentActivity.Idle),
            AgentEvent.UserMessage("e3", SESSION, 3, "go on then"),
        ).toTranscript(SESSION)

        assertEquals(AgentActivity.AwaitingPermission("p1"), transcript.activity)
        assertEquals("p1", transcript.pendingPermission?.requestId)
    }

    // ---- sub-agents --------------------------------------------------------

    @Test
    fun `a sub-agent's work collapses into its own card instead of the parent's transcript`() {
        val transcript = listOf(
            message("e1", "m1", "I'll send a sub-agent to read the module."),
            AgentEvent.ToolCallStarted(
                "e2", SESSION, 2, "a1",
                ToolCall.Task("Audit runtime-api", prompt = "List the public declarations."),
            ),
            AgentEvent.AgentMessage("e3", SESSION, 3, "m2", "Starting from the entry points.", subAgentId = "a1"),
            AgentEvent.ToolCallStarted(
                "e4", SESSION, 4, "c1", ToolCall.Search("public "), subAgentId = "a1",
            ),
            AgentEvent.ToolCallFinished(
                "e5", SESSION, 5, "c1", ToolOutcome.Success(summary = "63 matches"), subAgentId = "a1",
            ),
        ).toTranscript(SESSION)

        // The parent sees its own message and one card — not four rows in arrival order.
        assertEquals(2, transcript.items.size)
        val agent = transcript.items.last() as TranscriptItem.SubAgent
        assertEquals("a1", agent.subAgentId)
        assertEquals("Audit runtime-api", agent.task.description)
        assertTrue(agent.running)
        // And inside, folded by the same rules: the call and its result are one card.
        assertEquals(2, agent.items.size)
        assertEquals("63 matches", (agent.items.last() as TranscriptItem.Tool).outcome.let {
            (it as ToolOutcome.Success).summary
        })
        assertEquals("Search “public ”", agent.latest)
    }

    @Test
    fun `finishing the task that named a sub-agent finishes its card, not a tool card`() {
        val events = listOf(
            AgentEvent.ToolCallStarted("e1", SESSION, 1, "a1", ToolCall.Task("Audit runtime-api")),
            AgentEvent.AgentMessage("e2", SESSION, 2, "m1", "22 of 63 are undocumented.", subAgentId = "a1"),
            AgentEvent.ToolCallFinished("e3", SESSION, 3, "a1", ToolOutcome.Success(summary = "22 gaps")),
        )

        val agent = events.toTranscript(SESSION).items.single() as TranscriptItem.SubAgent
        assertEquals(false, agent.running)
        assertEquals(false, agent.stopped)
        assertEquals("22 gaps", (agent.outcome as ToolOutcome.Success).summary)
    }

    /** Stopping one sub-agent has to be visible as *that*, not as a sub-agent that simply ended. */
    @Test
    fun `a stopped sub-agent says it was stopped`() {
        val agent = listOf(
            AgentEvent.ToolCallStarted("e1", SESSION, 1, "a1", ToolCall.Task("Audit runtime-api")),
            AgentEvent.ToolCallFinished("e2", SESSION, 2, "a1", ToolOutcome.Cancelled),
        ).toTranscript(SESSION).items.single() as TranscriptItem.SubAgent

        assertTrue(agent.stopped)
        assertEquals(false, agent.running)
    }

    /** A reconnect can lose the call that named the sub-agent. Its work still has to show up. */
    @Test
    fun `an orphaned sub-agent event opens a card rather than vanishing`() {
        val agent = listOf(
            AgentEvent.AgentMessage("e1", SESSION, 1, "m1", "Reading the module.", subAgentId = "a9"),
        ).toTranscript(SESSION).items.single() as TranscriptItem.SubAgent

        assertEquals("a9", agent.subAgentId)
        assertEquals("Reading the module.", (agent.items.single() as TranscriptItem.Agent).text)
    }

    /** The card keeps its place: a sub-agent that talks for a minute must not walk down the list. */
    @Test
    fun `a sub-agent card stays where it first appeared`() {
        val transcript = listOf(
            AgentEvent.ToolCallStarted("e1", SESSION, 10, "a1", ToolCall.Task("Audit")),
            message("e2", "m1", "meanwhile, I'm reading the docs"),
            AgentEvent.AgentMessage("e3", SESSION, 90, "m2", "Half way through.", subAgentId = "a1"),
        ).toTranscript(SESSION)

        val agent = transcript.items.first() as TranscriptItem.SubAgent
        assertEquals(10L, agent.at)
    }

    /** A sub-agent of a sub-agent nests inside it, and the fold must terminate while doing so. */
    @Test
    fun `a sub-agent's own sub-agent nests one level deeper`() {
        val transcript = listOf(
            AgentEvent.ToolCallStarted("e1", SESSION, 1, "a1", ToolCall.Task("Audit the modules")),
            AgentEvent.ToolCallStarted(
                "e2", SESSION, 2, "a2", ToolCall.Task("Audit runtime-api"), subAgentId = "a1",
            ),
            AgentEvent.AgentMessage("e3", SESSION, 3, "m1", "63 declarations.", subAgentId = "a2"),
        ).toTranscript(SESSION)

        val outer = transcript.items.single() as TranscriptItem.SubAgent
        val inner = outer.items.single() as TranscriptItem.SubAgent
        assertEquals("a2", inner.subAgentId)
        assertEquals("63 declarations.", (inner.items.single() as TranscriptItem.Agent).text)
    }

    /** A delegate cannot outlive the session that sent it, and neither can its calls. */
    @Test
    fun `a session that ends closes a sub-agent and the work inside it`() {
        val transcript = listOf(
            AgentEvent.ToolCallStarted("e1", SESSION, 1, "a1", ToolCall.Task("Audit runtime-api")),
            AgentEvent.ToolCallStarted(
                "e2", SESSION, 2, "c1", ToolCall.Search("public "), subAgentId = "a1",
            ),
            AgentEvent.SessionEnded("e3", SESSION, 3, SessionOutcome.Interrupted),
        ).toTranscript(SESSION)

        val agent = transcript.items.filterIsInstance<TranscriptItem.SubAgent>().single()
        assertEquals(false, agent.running)
        assertTrue(agent.stopped)
        val inner = agent.items.single() as TranscriptItem.Tool
        assertEquals(false, inner.running)
    }

    private fun message(id: String, messageId: String, text: String, complete: Boolean = true) =
        AgentEvent.AgentMessage(id, SESSION, 1, messageId, text, complete)

    private companion object {
        const val SESSION = "s-test"
    }
}
