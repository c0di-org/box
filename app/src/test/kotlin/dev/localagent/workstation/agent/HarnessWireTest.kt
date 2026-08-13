package dev.localagent.workstation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `a sub-agent arrives as a task tool call carrying what it was asked to do`() {
        val event = parse(
            """{"type":"tool_started","callId":"a1","tool":{"kind":"task",
               "description":"Audit runtime-api","prompt":"List the public declarations.",
               "agentType":"Explore"}}""",
        ) as AgentEvent.ToolCallStarted

        val call = event.call as ToolCall.Task
        assertEquals("Audit runtime-api", call.description)
        assertEquals("Explore", call.agentType)
        // The call id is the sub-agent's name; everything it does comes back stamped with it.
        assertEquals("a1", event.callId)
        assertNull(event.subAgentId)
    }

    @Test
    fun `what a sub-agent does is attributed to it, and what the agent does is not`() {
        val delegated = parse(
            """{"type":"tool_started","callId":"c1","subAgentId":"a1",
               "tool":{"kind":"shell","command":"rg public"}}""",
        ) as AgentEvent.ToolCallStarted
        val said = parse("""{"type":"message","messageId":"m1","text":"hi","subAgentId":"a1"}""")
            as AgentEvent.AgentMessage
        val own = parse("""{"type":"message","messageId":"m2","text":"hi"}""") as AgentEvent.AgentMessage

        assertEquals("a1", delegated.subAgentId)
        assertEquals("a1", said.subAgentId)
        // Absent means the session's own agent, not "unknown": a harness that has never heard of
        // sub-agents keeps working, and every line it writes has exactly one author.
        assertNull(own.subAgentId)
    }

    @Test
    fun `a task with nothing to say for itself is still a sub-agent worth showing`() {
        val event = parse("""{"type":"tool_started","callId":"a1","tool":{"kind":"task"}}""")
            as AgentEvent.ToolCallStarted

        val call = event.call as ToolCall.Task
        assertEquals("Sub-agent", call.description)
        assertNull(call.prompt)
        assertNull(call.agentType)
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
    fun `a question arrives as something the sheet can answer`() {
        val event = parse(
            """{"type":"permission_requested","requestId":"p4","ask":{"kind":"question",
               "questions":[{"text":"Which model should the sub-agent use?","header":"Model",
               "multiSelect":false,"options":[{"label":"Sonnet","description":"Faster."},
               {"label":"Opus","description":"Better at long reasoning."}]}]}}""",
        ) as AgentEvent.PermissionRequested

        val ask = event.ask as PermissionAsk.Questions
        val question = ask.questions.single()
        assertEquals("Which model should the sub-agent use?", question.text)
        assertEquals("Model", question.header)
        assertEquals(listOf("Sonnet", "Opus"), question.options.map { it.label })
        assertEquals("Faster.", question.options.first().description)
        // Answering future questions nobody has read is the one thing this must not offer.
        assertNull(ask.alwaysAllowScope)
    }

    @Test
    fun `a question with no way to answer it degrades to an ask that can still be refused`() {
        // Options are what makes a question answerable. Without them the sheet would be a dead
        // end, so it falls back to the one shape that always works rather than drawing nothing.
        val event = parse(
            """{"type":"permission_requested","requestId":"p5","ask":{"kind":"question",
               "questions":[{"text":"Which one?","header":"Pick","options":[]}]}}""",
        ) as AgentEvent.PermissionRequested

        assertTrue(event.ask is PermissionAsk.Generic)
    }

    @Test
    fun `an answered question comes back carrying the answer, not just a yes`() {
        val event = parse(
            """{"type":"permission_resolved","requestId":"p4","decision":"answer",
               "answers":{"Which model should the sub-agent use?":"Opus"}}""",
        ) as AgentEvent.PermissionResolved

        val decision = event.decision as PermissionDecision.Answered
        assertEquals(mapOf("Which model should the sub-agent use?" to "Opus"), decision.answers)
    }

    @Test
    fun `an answer with nothing in it is an allow, and never an answer nobody gave`() {
        // The whole point of this path is that the agent is never told a person chose something
        // they did not. An empty payload has to lose the claim, not keep it.
        val event = parse(
            """{"type":"permission_resolved","requestId":"p4","decision":"answer","answers":{}}""",
        ) as AgentEvent.PermissionResolved

        assertEquals(PermissionDecision.Allow, event.decision)
    }

    @Test
    fun `an answered decision goes down as an allow a guest that predates questions still obeys`() {
        val line = HarnessWire.encode(
            mapOf(
                "type" to "decision",
                "requestId" to "p4",
                "decision" to "allow",
                "answers" to mapOf("Which model?" to "Opus"),
            ),
        )

        // An older harness reads the allow and drops the field it has never heard of: the answer
        // is lost, which is exactly today's behaviour. A decision word it did not know would have
        // been read as "not allowed" and failed the call outright.
        val parsed = org.json.JSONObject(line)
        assertEquals("allow", parsed.getString("decision"))
        assertEquals("Opus", parsed.getJSONObject("answers").getString("Which model?"))
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

    // ---- artifacts ----------------------------------------------------------

    @Test
    fun `a document artifact carries the path the agent wrote`() {
        val event = parse(
            """{"type":"artifact","kind":"document","guestPath":"/workspace/report.md","name":"report.md","mimeType":"text/markdown"}""",
        ) as AgentEvent.ArtifactOffered
        val document = event.artifact as Artifact.Document

        assertEquals("/workspace/report.md", document.guestPath)
        assertEquals("report.md", document.name)
        assertEquals("text/markdown", document.mimeType)
    }

    @Test
    fun `a document with no name is named after its path`() {
        val event = parse(
            """{"type":"artifact","kind":"document","guestPath":"/workspace/out/chart.png","mimeType":"image/png"}""",
        ) as AgentEvent.ArtifactOffered

        assertEquals("chart.png", (event.artifact as Artifact.Document).name)
    }

    @Test
    fun `the computer and a forwarded port still parse`() {
        assertEquals(
            Artifact.Computer,
            (parse("""{"type":"artifact","kind":"computer"}""") as AgentEvent.ArtifactOffered).artifact,
        )
        assertEquals(
            Artifact.Preview("http://localhost:5173/", 5173),
            (parse("""{"type":"artifact","kind":"preview","url":"http://localhost:5173/","guestPort":5173}""")
                as AgentEvent.ArtifactOffered).artifact,
        )
    }

    @Test
    fun `an artifact this build cannot open is dropped rather than drawn`() {
        // Unlike a tool call, an artifact is a button. A row offering to open something Box has no
        // way to open is worse than no row, so this is the one place the labelled-card rule does
        // not apply.
        assertNull(parse("""{"type":"artifact","kind":"hologram","url":"x"}"""))
        assertNull(parse("""{"type":"artifact","kind":"document"}"""))
        assertNull(parse("""{"type":"artifact","kind":"preview","guestPort":5173}"""))
    }

    // ---- the other direction -----------------------------------------------

    @Test
    fun `a command keeps its types on the wire`() {
        val line = HarnessWire.encode(
            mapOf(
                "type" to "viewport",
                "layout" to "wide",
                "widthDp" to 1280,
                "hardwareKeyboard" to true,
            ),
        )

        // Quoting the number would push the decision about what it means into the harness, which
        // is the half of the pair that ships in the guest image and cannot be corrected from here.
        assertEquals(
            """{"type":"viewport","layout":"wide","widthDp":1280,"hardwareKeyboard":true}""",
            line,
        )
    }

    @Test
    fun `a command survives a round trip through a real parser`() {
        val line = HarnessWire.encode(mapOf("type" to "prompt", "text" to "say \"hi\"\nthen stop"))
        val parsed = org.json.JSONObject(line)

        assertEquals("prompt", parsed.getString("type"))
        assertEquals("say \"hi\"\nthen stop", parsed.getString("text"))
    }

    @Test
    fun `a viewport reaches the harness as something it will accept`() {
        // The one command whose reader validates before acting: the guest drops a viewport whose
        // layout it does not know, so the two vocabularies have to agree here and not just compile.
        val wire = ViewportLayout.entries.map { it.wire }.toSet()
        assertEquals(setOf("compact", "wide"), wire)
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

    // ---- an agent asking for an account ------------------------------------

    @Test
    fun `a request for an account carries the agent's own reason for it`() {
        val event = parse(
            """{"type":"connect_requested","requestId":"connect-1","service":"github","reason":"to clone garfbargle/box"}""",
        ) as AgentEvent.ConnectRequested

        assertEquals("connect-1", event.requestId)
        assertEquals(ConnectService.GitHub, event.service)
        // The only explanation the person gets before deciding, so it survives the wire intact.
        assertEquals("to clone garfbargle/box", event.reason)
    }

    @Test
    fun `an agent that gave no reason is still a request, not a dropped line`() {
        val event = parse(
            """{"type":"connect_requested","requestId":"connect-2","service":"github","reason":null}""",
        ) as AgentEvent.ConnectRequested

        assertNull(event.reason)
    }

    @Test
    fun `an account this build cannot connect is dropped rather than drawn`() {
        // The harness in the guest is upgraded on its own schedule, so it can ask for a service
        // this app has never heard of. A button that opens nothing is worse than no button.
        assertNull(parse("""{"type":"connect_requested","requestId":"c-3","service":"gitlab"}"""))
    }

    @Test
    fun `a request that ended says so, which is what stops a replay reopening it`() {
        val event = parse(
            """{"type":"connect_resolved","requestId":"connect-1","connected":true}""",
        ) as AgentEvent.ConnectResolved

        assertEquals("connect-1", event.requestId)
        assertEquals(true, event.connected)
    }

    @Test
    fun `declining is an ending too, and reads as one`() {
        val event = parse(
            """{"type":"connect_resolved","requestId":"connect-2","connected":false}""",
        ) as AgentEvent.ConnectResolved

        assertEquals(false, event.connected)
    }

    @Test
    fun `an ending is read even for a service this build cannot draw`() {
        // Deliberately unlike the request, which is dropped when the service is unknown. A card
        // that was never drawn is harmless to take down; one that cannot be taken down is not.
        val event = parse(
            """{"type":"connect_resolved","requestId":"c-3","connected":false}""",
        )
        assertNotNull(event as? AgentEvent.ConnectResolved)
    }
}
