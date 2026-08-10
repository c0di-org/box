package dev.localagent.workstation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary between a harness in the guest and Box's event model.
 *
 * Most of these pin *degradation*: the guest image and the APK are upgraded independently, and a
 * newer harness must be able to say things this build has never heard of without breaking the
 * transcript it is speaking into.
 */
class HarnessWireTest {
    private val context = HarnessWire.Context(
        sessionId = "s-1",
        harnessId = "claude-code",
        title = "Clone project and run",
    )

    private fun parse(line: String, ordinal: Long = 0) = HarnessWire.parse(line, context, ordinal)

    @Test
    fun `an event kind this build does not know is ignored, not fatal`() {
        // A guest image can be newer than the app installed over it.
        assertNull(parse("""{"v":1,"type":"quantum_entanglement","payload":{}}"""))
    }

    @Test
    fun `a line that is not JSON at all is ignored`() {
        // npm and friends can write to the same stream; a stray line must not kill a session.
        assertNull(parse("npm warn deprecated something@1.0.0"))
        assertNull(parse(""))
        assertNull(parse("{ this is not json"))
    }

    @Test
    fun `event ids come from position, so replaying a log reproduces them exactly`() {
        val line = """{"type":"message","messageId":"m1","text":"hello"}"""
        val first = parse(line, ordinal = 7)
        val again = parse(line, ordinal = 7)

        // AgentBackend.events promises that collecting twice yields the same prefix. It cannot,
        // if a replayed line gets a fresh id each time.
        assertEquals(first!!.eventId, again!!.eventId)
        assertEquals("s-1#7", first.eventId)
        assertTrue(parse(line, ordinal = 8)!!.eventId != first.eventId)
    }

    @Test
    fun `a shell tool call keeps its command and working directory`() {
        val event = parse(
            """{"type":"tool_started","callId":"c1",
               "tool":{"kind":"shell","command":"npm test","workingDirectory":"/workspace/app"}}""",
        ) as AgentEvent.ToolCallStarted

        val call = event.call as ToolCall.Shell
        assertEquals("c1", event.callId)
        assertEquals("npm test", call.command)
        assertEquals("/workspace/app", call.workingDirectory)
    }

    @Test
    fun `an unmodelled tool becomes a labelled card rather than being dropped`() {
        val event = parse(
            """{"type":"tool_started","callId":"c2",
               "tool":{"kind":"telepathy","name":"Telepathy","arguments":[["target","the user"]]}}""",
        ) as AgentEvent.ToolCallStarted

        val call = event.call as ToolCall.Generic
        assertEquals("Telepathy", call.name)
        assertEquals(listOf("target" to "the user"), call.arguments)
    }

    @Test
    fun `a finish with no readable status still finishes the card`() {
        val event = parse("""{"type":"tool_finished","callId":"c1","outcome":{}}""")
            as AgentEvent.ToolCallFinished

        // A card left spinning forever is worse than one that admits it does not know.
        assertTrue(event.outcome is ToolOutcome.Success)
    }

    @Test
    fun `a failed tool keeps its message and output`() {
        val event = parse(
            """{"type":"tool_finished","callId":"c1",
               "outcome":{"status":"failure","message":"exit 1","output":"boom","exitCode":1}}""",
        ) as AgentEvent.ToolCallFinished

        val outcome = event.outcome as ToolOutcome.Failure
        assertEquals("exit 1", outcome.message)
        assertEquals("boom", outcome.output)
        assertEquals(1, outcome.exitCode)
    }

    @Test
    fun `an edit request becomes a reviewable diff`() {
        val event = parse(
            """{"type":"permission_requested","requestId":"p1","ask":{"kind":"edit_file",
               "path":"/workspace/vite.config.js","changeKind":"modify",
               "patch":"@@ -3,1 +3,1 @@\n-  host: false\n+  host: true",
               "alwaysAllowScope":"edits in this project"}}""",
        ) as AgentEvent.PermissionRequested

        val ask = event.ask as PermissionAsk.EditFile
        assertEquals("/workspace/vite.config.js", ask.diff.path)
        assertEquals("edits in this project", ask.alwaysAllowScope)
        assertEquals(1, ask.diff.additions)
        assertEquals(1, ask.diff.deletions)
    }

    @Test
    fun `an edit with no diff still asks, and says why it has nothing to show`() {
        val event = parse(
            """{"type":"permission_requested","requestId":"p2","ask":{"kind":"edit_file",
               "path":"/workspace/a.txt","rationale":"Box could not read the file."}}""",
        ) as AgentEvent.PermissionRequested

        // Never a blank sheet: the person still has to be able to decide.
        val ask = event.ask as PermissionAsk.Generic
        assertEquals("Edit a.txt", ask.headline)
        assertEquals("Box could not read the file.", ask.description)
        assertEquals(listOf("File" to "/workspace/a.txt"), ask.details)
    }

    @Test
    fun `a command request offers no blanket approval`() {
        val event = parse(
            """{"type":"permission_requested","requestId":"p3","ask":{"kind":"run_command",
               "command":"rm -rf build","workingDirectory":"/workspace"}}""",
        ) as AgentEvent.PermissionRequested

        val ask = event.ask as PermissionAsk.RunCommand
        assertEquals("rm -rf build", ask.command)
        assertNull(ask.alwaysAllowScope)
    }

    @Test
    fun `an unanswered request resolves as abandoned rather than as an allow`() {
        val abandoned = parse("""{"type":"permission_resolved","requestId":"p1","decision":"?"}""")
            as AgentEvent.PermissionResolved
        val denied = parse("""{"type":"permission_resolved","requestId":"p1","decision":"deny"}""")
            as AgentEvent.PermissionResolved

        // Anything Box cannot read as a yes must never become one.
        assertEquals(PermissionDecision.Abandoned, abandoned.decision)
        assertEquals(PermissionDecision.Deny, denied.decision)
    }

    @Test
    fun `a checklist maps the harness's own task vocabulary`() {
        val event = parse(
            """{"type":"task_progress","planId":"plan-1","items":[
               {"text":"Clone the project","state":"completed"},
               {"text":"Install dependencies","state":"in_progress"},
               {"text":"Start the server","state":"pending"}]}""",
        ) as AgentEvent.TaskProgress

        assertEquals(
            listOf(TaskState.Done, TaskState.Running, TaskState.Pending),
            event.items.map { it.state },
        )
        assertEquals("Clone the project", event.items.first().text)
    }

    @Test
    fun `a session ends with the outcome the harness reported`() {
        val done = parse("""{"type":"session_ended","outcome":{"status":"completed","summary":"ok"}}""")
            as AgentEvent.SessionEnded
        val stopped = parse("""{"type":"session_ended","outcome":{"status":"interrupted"}}""")
            as AgentEvent.SessionEnded

        assertEquals(SessionOutcome.Completed("ok"), done.outcome)
        assertEquals(SessionOutcome.Interrupted, stopped.outcome)
    }

    @Test
    fun `a session start takes its working directory from the guest`() {
        val event = parse("""{"type":"session_started","cwd":"/workspace/awesome-app"}""")
            as AgentEvent.SessionStarted

        assertEquals("/workspace/awesome-app", event.workingDirectory)
        assertEquals("claude-code", event.harnessId)
        assertEquals("Clone project and run", event.title)
    }

    @Test
    fun `an error carries its detail and whether the session can continue`() {
        val event = parse(
            """{"type":"error","message":"Not signed in","detail":"Add a key","recoverable":false}""",
        ) as AgentEvent.AgentError

        assertEquals("Not signed in", event.message)
        assertEquals("Add a key", event.detail)
        assertEquals(false, event.recoverable)
    }
}
