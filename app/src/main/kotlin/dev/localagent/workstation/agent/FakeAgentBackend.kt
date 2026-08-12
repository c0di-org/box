package dev.localagent.workstation.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A scripted [AgentBackend] with no VM behind it.
 *
 * Exists so the entire conversation surface — streaming prose, tool cards, checklists, diffs, the
 * permission sheet, disconnection — can be built, demoed and screenshotted before the harness
 * transport lands. The headline script is the mockup's "clone project and run" flow, and it
 * deliberately pauses on a permission request so the sheet is reachable in two taps from a cold
 * start. A second one — [runAuditScript] — delegates to a sub-agent and parks there, so the card
 * and its Stop button are reachable the same way.
 */
class FakeAgentBackend(
    private val scope: CoroutineScope,
    /** Multiplies every scripted delay. Lower is faster; 0 makes the script effectively instant. */
    private val pace: Float = 1f,
) : AgentBackend {

    private val now = AtomicLong(System.currentTimeMillis() - 20 * 60_000L)
    private val ids = AtomicLong()
    private val logs = ConcurrentHashMap<String, MutableStateFlow<List<AgentEvent>>>()
    private val connections = ConcurrentHashMap<String, MutableStateFlow<SessionConnection>>()
    private val scripts = ConcurrentHashMap<String, Job>()
    private val awaitingDecision = ConcurrentHashMap<String, CompletableDeferred<PermissionDecision>>()

    /** Scripted sub-agents still running, by session and id, so one can be stopped on its own. */
    private val subAgents = ConcurrentHashMap<Pair<String, String>, Job>()

    private val harnessList = MutableStateFlow(
        listOf(
            HarnessDescriptor("claude", "Claude", "claude", HarnessMarkKind.Burst),
            HarnessDescriptor("chatgpt", "ChatGPT", "codex", HarnessMarkKind.Knot),
            HarnessDescriptor("cursor", "Cursor", "cursor-agent", HarnessMarkKind.Prism),
        ),
    )
    override val harnesses: StateFlow<List<HarnessDescriptor>> = harnessList.asStateFlow()

    private val sessionList = MutableStateFlow(seedSessions())
    override val sessions: StateFlow<List<SessionSummary>> = sessionList.asStateFlow()

    init {
        seedTranscripts()
    }

    // -----------------------------------------------------------------------
    // AgentBackend
    // -----------------------------------------------------------------------

    override fun events(sessionId: String): Flow<AgentEvent> {
        ensureScript(sessionId)
        // The log is cumulative, so conflation cannot drop an event: each snapshot is a superset
        // of the last and the cursor walks whatever arrived since.
        return flow {
            var cursor = 0
            log(sessionId).collect { snapshot ->
                while (cursor < snapshot.size) {
                    emit(snapshot[cursor])
                    cursor++
                }
            }
        }
    }

    override fun connection(sessionId: String): StateFlow<SessionConnection> =
        connections.getOrPut(sessionId) { MutableStateFlow(SessionConnection.Live) }.asStateFlow()

    override suspend fun startSession(harnessId: String, prompt: String?): String {
        val id = "s-${ids.incrementAndGet()}"
        val title = prompt?.take(48)?.ifBlank { null } ?: "New conversation"
        sessionList.update {
            listOf(
                SessionSummary(
                    id = id,
                    harnessId = harnessId,
                    title = title,
                    status = SessionStatus.Idle,
                    updatedAt = System.currentTimeMillis(),
                ),
            ) + it
        }
        connections[id] = MutableStateFlow(SessionConnection.Connecting)
        emit(
            AgentEvent.SessionStarted(
                eventId = next(),
                sessionId = id,
                at = System.currentTimeMillis(),
                harnessId = harnessId,
                title = title,
                workingDirectory = "/workspace",
            ),
        )
        scope.launch {
            delay((900 * pace).toLong())
            connections[id]?.value = SessionConnection.Live
            if (prompt != null) runGenericScript(id, prompt)
        }
        return id
    }

    override suspend fun send(sessionId: String, text: String) {
        emit(
            AgentEvent.UserMessage(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                text = text,
            ),
        )
        touch(sessionId, SessionStatus.Active)
        scripts[sessionId]?.cancel()
        scripts[sessionId] = scope.launch { runGenericScript(sessionId, text) }
    }

    override suspend fun resolvePermission(
        sessionId: String,
        requestId: String,
        decision: PermissionDecision,
    ) {
        awaitingDecision.remove(requestId)?.complete(decision)
    }

    override suspend fun interrupt(sessionId: String) {
        scripts.remove(sessionId)?.cancel()
        // Stopping the session stops everything it delegated. The cards say so rather than being
        // left spinning by a script that will never speak again.
        subAgents.keys.filter { it.first == sessionId }.forEach { stopSubAgent(sessionId, it.second) }
        awaitingDecision.keys.forEach { awaitingDecision.remove(it)?.complete(PermissionDecision.Abandoned) }
        emit(
            AgentEvent.ActivityChanged(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                activity = AgentActivity.Idle,
            ),
        )
        touch(sessionId, SessionStatus.Idle)
    }

    override suspend fun interruptSubAgent(sessionId: String, subAgentId: String) {
        stopSubAgent(sessionId, subAgentId)
    }

    override suspend fun closeSession(sessionId: String) {
        scripts.remove(sessionId)?.cancel()
        logs.remove(sessionId)
        connections.remove(sessionId)
        sessionList.update { list -> list.filterNot { it.id == sessionId } }
    }

    // -----------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------

    private fun log(sessionId: String) =
        logs.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

    private fun emit(event: AgentEvent) {
        log(event.sessionId).update { it + event }
    }

    private fun next(): String = "e-${ids.incrementAndGet()}"

    private fun stamp(minutesAgo: Long = 0): Long = now.addAndGet(37_000) - minutesAgo * 60_000

    private suspend fun beat(millis: Long) {
        val scaled = (millis * pace).toLong()
        if (scaled > 0) delay(scaled)
    }

    private fun touch(sessionId: String, status: SessionStatus, preview: String? = null) {
        sessionList.update { list ->
            list.map { summary ->
                if (summary.id != sessionId) {
                    summary
                } else {
                    summary.copy(
                        status = status,
                        updatedAt = System.currentTimeMillis(),
                        preview = preview ?: summary.preview,
                    )
                }
            }
        }
    }

    private fun ensureScript(sessionId: String) {
        if (scripts.containsKey(sessionId)) return
        val script: (suspend () -> Unit) = when (sessionId) {
            CLONE_SESSION -> ({ runCloneScript(sessionId) })
            AUDIT_SESSION -> ({ runAuditScript(sessionId) })
            else -> return
        }
        scripts[sessionId] = scope.launch { script() }
    }

    /** Streams [text] into one message id so the UI exercises its streaming path. */
    private suspend fun stream(sessionId: String, text: String, chunk: Int = 18) {
        val messageId = "m-${ids.incrementAndGet()}"
        val at = System.currentTimeMillis()
        var shown = 0
        while (shown < text.length) {
            shown = (shown + chunk).coerceAtMost(text.length)
            emit(
                AgentEvent.AgentMessage(
                    eventId = next(),
                    sessionId = sessionId,
                    at = at,
                    messageId = messageId,
                    text = text.take(shown),
                    complete = shown == text.length,
                ),
            )
            beat(55)
        }
    }

    private suspend fun activity(sessionId: String, activity: AgentActivity) {
        emit(
            AgentEvent.ActivityChanged(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                activity = activity,
            ),
        )
    }

    private suspend fun tool(
        sessionId: String,
        call: ToolCall,
        output: List<String>,
        outcome: ToolOutcome,
        stepMillis: Long = 320,
    ) {
        val callId = "c-${ids.incrementAndGet()}"
        emit(
            AgentEvent.ToolCallStarted(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                callId = callId,
                call = call,
            ),
        )
        output.forEach { line ->
            beat(stepMillis)
            emit(
                AgentEvent.ToolCallProgress(
                    eventId = next(),
                    sessionId = sessionId,
                    at = System.currentTimeMillis(),
                    callId = callId,
                    chunk = line + "\n",
                ),
            )
        }
        beat(stepMillis)
        emit(
            AgentEvent.ToolCallFinished(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                callId = callId,
                outcome = outcome,
            ),
        )
    }

    private fun plan(sessionId: String, items: List<TaskItem>) {
        emit(
            AgentEvent.TaskProgress(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                planId = "$sessionId-plan",
                items = items,
            ),
        )
    }

    /**
     * Ends a scripted sub-agent the way a real stop ends one: cancelled, and said out loud.
     *
     * The card is closed here rather than by the cancelled script, because a cancelled coroutine
     * is in no position to emit anything — which is exactly how a stopped sub-agent ends up
     * spinning forever on the screen that asked it to stop.
     */
    private fun stopSubAgent(sessionId: String, subAgentId: String) {
        val running = subAgents.remove(sessionId to subAgentId) ?: return
        running.cancel()
        emit(
            AgentEvent.ToolCallFinished(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                callId = subAgentId,
                outcome = ToolOutcome.Cancelled,
            ),
        )
    }

    /**
     * Everything a sub-agent emits, stamped with its own id.
     *
     * Deliberately the same [emit] and the same event types the parent uses: the nesting the UI
     * draws is a property of the log, not of a second channel, so a scripted sub-agent exercises
     * exactly the path the harness will.
     */
    private suspend fun subAgentTool(
        sessionId: String,
        subAgentId: String,
        call: ToolCall,
        summary: String,
        stepMillis: Long = 420,
    ) {
        val callId = "c-${ids.incrementAndGet()}"
        emit(
            AgentEvent.ToolCallStarted(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                callId = callId,
                call = call,
                subAgentId = subAgentId,
            ),
        )
        beat(stepMillis)
        emit(
            AgentEvent.ToolCallFinished(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                callId = callId,
                outcome = ToolOutcome.Success(summary = summary),
                subAgentId = subAgentId,
            ),
        )
    }

    private fun subAgentSays(sessionId: String, subAgentId: String, text: String) {
        emit(
            AgentEvent.AgentMessage(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                messageId = "m-${ids.incrementAndGet()}",
                text = text,
                subAgentId = subAgentId,
            ),
        )
    }

    private suspend fun ask(sessionId: String, ask: PermissionAsk): PermissionDecision {
        val requestId = "p-${ids.incrementAndGet()}"
        val gate = CompletableDeferred<PermissionDecision>()
        awaitingDecision[requestId] = gate
        emit(
            AgentEvent.PermissionRequested(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                requestId = requestId,
                ask = ask,
            ),
        )
        touch(sessionId, SessionStatus.NeedsYou("Waiting for approval"))
        val decision = gate.await()
        emit(
            AgentEvent.PermissionResolved(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                requestId = requestId,
                decision = decision,
            ),
        )
        return decision
    }

    // -----------------------------------------------------------------------
    // Scripts
    // -----------------------------------------------------------------------

    /** The mockup flow, verbatim, ending at a permission request the user has to answer. */
    private suspend fun runCloneScript(sessionId: String) {
        val tasks = mutableListOf(
            TaskItem("cloned repo", TaskState.Pending),
            TaskItem("installed dependencies", TaskState.Pending),
            TaskItem("starting dev server", TaskState.Pending),
        )
        fun mark(index: Int, state: TaskState) {
            tasks[index] = tasks[index].copy(state = state)
            plan(sessionId, tasks.toList())
        }

        emit(
            AgentEvent.UserMessage(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                text = "Clone my project and get it running.",
            ),
        )
        touch(sessionId, SessionStatus.Active)
        beat(500)

        activity(sessionId, AgentActivity.Thinking("Planning the setup"))
        beat(900)
        stream(sessionId, "I'm setting up the project on your local computer.")
        plan(sessionId, tasks.toList())
        beat(400)

        activity(sessionId, AgentActivity.Working("Cloning the repository"))
        mark(0, TaskState.Running)
        tool(
            sessionId = sessionId,
            call = ToolCall.Shell("git clone https://github.com/example/awesome-app.git", "/workspace"),
            output = listOf(
                "Cloning into 'awesome-app'...",
                "remote: Enumerating objects: 342, done.",
                "remote: Counting objects: 100% (342/342), done.",
                "Receiving objects: 100% (342/342), 2.48 MiB | 12.3 MiB/s, done.",
                "Resolving deltas: 100% (213/213), done.",
            ),
            outcome = ToolOutcome.Success(summary = "342 objects, 2.5 MB"),
        )
        mark(0, TaskState.Done)
        beat(350)

        activity(sessionId, AgentActivity.Working("Installing dependencies"))
        mark(1, TaskState.Running)
        tool(
            sessionId = sessionId,
            call = ToolCall.Shell("npm install", "/workspace/awesome-app"),
            output = listOf(
                "added 512 packages, and audited 513 packages in 8s",
                "74 packages are looking for funding",
                "found 0 vulnerabilities",
            ),
            outcome = ToolOutcome.Success(summary = "512 packages, 0 vulnerabilities"),
        )
        mark(1, TaskState.Done)
        beat(400)

        stream(
            sessionId,
            "Vite only binds to localhost by default, so the preview wouldn't be reachable from " +
                "your phone. I'd like to open it up to the Box network.",
        )
        beat(250)
        mark(2, TaskState.Running)
        activity(sessionId, AgentActivity.AwaitingPermission("pending"))

        val decision = ask(
            sessionId,
            PermissionAsk.EditFile(
                diff = UnifiedDiff.parse(
                    path = "/workspace/awesome-app/vite.config.js",
                    patch = VITE_CONFIG_PATCH,
                ),
                rationale = "Lets the dev server accept connections from Box's preview pane " +
                    "instead of only from inside the VM.",
            ),
        )

        when (decision) {
            is PermissionDecision.Deny, PermissionDecision.Abandoned -> {
                mark(2, TaskState.Skipped)
                stream(
                    sessionId,
                    "Left the config untouched. I can still start the dev server, but the preview " +
                        "won't load outside the VM. Want me to start it anyway?",
                )
                activity(sessionId, AgentActivity.AwaitingInput)
                touch(sessionId, SessionStatus.NeedsYou("Waiting for your reply"), "Left the config untouched.")
            }

            else -> {
                emit(
                    AgentEvent.FileChanged(
                        eventId = next(),
                        sessionId = sessionId,
                        at = System.currentTimeMillis(),
                        callId = null,
                        diff = UnifiedDiff.parse(
                            path = "/workspace/awesome-app/vite.config.js",
                            patch = VITE_CONFIG_PATCH,
                        ),
                    ),
                )
                beat(400)
                activity(sessionId, AgentActivity.Working("Starting the dev server"))
                tool(
                    sessionId = sessionId,
                    call = ToolCall.Shell("npm run dev", "/workspace/awesome-app"),
                    output = listOf(
                        "> awesome-app@1.0.0 dev",
                        "> vite",
                        "",
                        "  VITE v5.2.8  ready in 217 ms",
                        "",
                        "  ➜  Local:   http://localhost:5173/",
                        "  ➜  Network: http://10.0.2.15:5173/",
                    ),
                    outcome = ToolOutcome.Success(summary = "ready in 217 ms"),
                )
                mark(2, TaskState.Done)
                beat(300)
                stream(
                    sessionId,
                    "The dev server is up on port 5173. You can watch it work or open the preview.",
                )
                emit(
                    AgentEvent.ArtifactOffered(
                        eventId = next(),
                        sessionId = sessionId,
                        at = System.currentTimeMillis(),
                        artifact = Artifact.Computer,
                    ),
                )
                emit(
                    AgentEvent.ArtifactOffered(
                        eventId = next(),
                        sessionId = sessionId,
                        at = System.currentTimeMillis(),
                        artifact = Artifact.Preview("http://localhost:5173/", 5173),
                    ),
                )
                activity(sessionId, AgentActivity.Idle)
                touch(sessionId, SessionStatus.Idle, "The dev server is up on port 5173.")
            }
        }
    }

    /**
     * The sub-agent demo: one agent delegating, and a delegate you can stop.
     *
     * Kept out of the clone script on purpose. That one exists to reach the permission sheet in two
     * taps from a cold start, and anything added before the ask pushes the sheet further away.
     *
     * The delegate parks instead of finishing, which is the only way a Stop button is reachable by
     * hand: a scripted sub-agent that completes in eight seconds cannot be stopped by anyone, and a
     * control nobody can press is not demoable. Stopping it is what lets the rest of this run.
     */
    private suspend fun runAuditScript(sessionId: String) {
        emit(
            AgentEvent.UserMessage(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                text = "Go through the public API and tell me what isn't documented.",
            ),
        )
        touch(sessionId, SessionStatus.Active)
        beat(500)

        activity(sessionId, AgentActivity.Thinking("Planning the audit"))
        beat(800)
        stream(
            sessionId,
            "There are four modules and a few hundred public declarations. I'll send a sub-agent " +
                "to read the runtime module while I go through what the docs already cover.",
        )
        beat(300)

        val subAgentId = "a-${ids.incrementAndGet()}"
        emit(
            AgentEvent.ToolCallStarted(
                eventId = next(),
                sessionId = sessionId,
                at = System.currentTimeMillis(),
                callId = subAgentId,
                call = ToolCall.Task(
                    description = "Audit runtime-api for missing docs",
                    prompt = "List every public declaration in runtime-api and note which of them " +
                        "carry KDoc. Report the gaps, grouped by file.",
                    agentType = "Explore",
                ),
            ),
        )
        activity(sessionId, AgentActivity.Working("Auditing runtime-api"))

        // Launched on the backend's own scope rather than inside this script, so stopping the
        // sub-agent is not the same act as stopping the run that spawned it.
        subAgents[sessionId to subAgentId] = scope.launch { runSubAgent(sessionId, subAgentId) }

        // The parent keeps working while its delegate does. This is the interleaving the card
        // exists to untangle: both are talking, and only one of them is the one you can stop.
        tool(
            sessionId = sessionId,
            call = ToolCall.Search("@param", "/workspace/docs"),
            output = listOf("docs/runtime.md: 12 matches", "docs/development.md: 3 matches"),
            outcome = ToolOutcome.Success(summary = "15 matches in 2 files"),
            stepMillis = 500,
        )

        subAgents[sessionId to subAgentId]?.join()

        val stopped = log(sessionId).value
            .filterIsInstance<AgentEvent.ToolCallFinished>()
            .lastOrNull { it.callId == subAgentId }
            ?.outcome is ToolOutcome.Cancelled
        beat(400)
        stream(
            sessionId,
            if (stopped) {
                "Stopped the audit. It had already got through the state machine, and everything " +
                    "it read is in its card above — I can pick it back up from there whenever."
            } else {
                "The audit came back: 22 of 63 public declarations in runtime-api have no KDoc, " +
                    "and they cluster in the state machine. That is where I'd start."
            },
        )
        activity(sessionId, AgentActivity.Idle)
        touch(
            sessionId,
            SessionStatus.Idle,
            if (stopped) "Audit stopped partway." else "22 of 63 declarations are undocumented.",
        )
    }

    /** What the delegate does, in its own voice, under its own id. */
    private suspend fun runSubAgent(sessionId: String, subAgentId: String) {
        subAgentSays(sessionId, subAgentId, "Starting from the module's entry points.")
        beat(500)
        subAgentTool(
            sessionId, subAgentId,
            ToolCall.Search("public ", "/workspace/runtime-api/src"),
            summary = "63 matches in 9 files",
        )
        subAgentTool(
            sessionId, subAgentId,
            ToolCall.ReadFile("/workspace/runtime-api/src/main/kotlin/ComputerRuntime.kt"),
            summary = "118 lines",
        )
        subAgentSays(
            sessionId, subAgentId,
            "The interface itself is documented. The state machine underneath it is where the " +
                "gaps are — reading that next.",
        )
        beat(500)
        subAgentTool(
            sessionId, subAgentId,
            ToolCall.ReadFile("/workspace/runtime-api/src/main/kotlin/RuntimeState.kt"),
            summary = "74 lines",
        )
        // Parked, not finished. See runAuditScript: a delegate that finishes on its own before a
        // hand reaches the Stop button is a feature nobody in the demo can try.
        awaitCancellation()
    }

    /** Anything the user types after the script. Short, honest, and never claims to have run. */
    private suspend fun runGenericScript(sessionId: String, prompt: String) {
        activity(sessionId, AgentActivity.Thinking())
        beat(700)
        tool(
            sessionId = sessionId,
            call = ToolCall.Shell("ls -la", "/workspace"),
            output = listOf("total 12", "drwxr-xr-x  3 agent agent 4096 May 20 10:41 awesome-app"),
            outcome = ToolOutcome.Success(summary = "1 directory"),
            stepMillis = 220,
        )
        beat(250)
        stream(
            sessionId,
            "This session is running against Box's scripted demo backend, so I can't actually " +
                "act on “$prompt” yet. Every surface you see — tool cards, diffs, the permission " +
                "sheet — is wired to the same event contract the real harness will emit.",
        )
        activity(sessionId, AgentActivity.Idle)
        touch(sessionId, SessionStatus.Idle, "Running against the scripted demo backend.")
    }

    // -----------------------------------------------------------------------
    // Seed data
    // -----------------------------------------------------------------------

    private fun seedSessions(): List<SessionSummary> {
        val base = System.currentTimeMillis()
        return listOf(
            SessionSummary(CLONE_SESSION, "chatgpt", "Clone project and run", SessionStatus.Active, base, preview = "Setting up the project…"),
            SessionSummary("s-review", "cursor", "Review PR #42", SessionStatus.Active, base - 4 * 60_000, preview = "Reading the diff…"),
            SessionSummary(AUDIT_SESSION, "claude", "Audit the public API", SessionStatus.Active, base - 2 * 60_000, preview = "A sub-agent is reading runtime-api…"),
            SessionSummary("s-auth", "claude", "Refactor auth flow", SessionStatus.Finished, base - 42 * 60_000, preview = "Split the token refresh out of the interceptor."),
            SessionSummary("s-logs", "claude", "Summarize logs", SessionStatus.Finished, base - 96 * 60_000, preview = "Three distinct crash signatures."),
            SessionSummary("s-layout", "chatgpt", "Fix mobile layout", SessionStatus.Finished, base - 130 * 60_000, preview = "The grid needed a min-width, not a media query."),
            SessionSummary("s-tests", "chatgpt", "Investigate test failures", SessionStatus.Finished, base - 210 * 60_000, preview = "Flaky clock in the scheduler suite."),
            SessionSummary("s-types", "cursor", "Clean up types", SessionStatus.Finished, base - 300 * 60_000, preview = "Removed 14 anys."),
        )
    }

    private fun seedTranscripts() {
        canned(
            "s-auth",
            "Refactor the auth flow so token refresh isn't tangled in the interceptor.",
            ToolCall.ReadFile("/workspace/api/src/auth/interceptor.kt"),
            listOf("142 lines"),
            "Split token refresh into `TokenStore` and left the interceptor doing one thing. " +
                "The retry path no longer re-enters itself when two requests race a refresh.",
            SessionOutcome.Completed("2 files changed"),
        )
        canned(
            "s-logs",
            "Summarize yesterday's crash logs.",
            ToolCall.Shell("journalctl --since yesterday | tail -n 2000", "/workspace"),
            listOf("2000 lines read"),
            "Three distinct signatures: an OOM in the image decoder (61 hits), a null document " +
                "id after sign-out (12), and one SSL handshake timeout. The OOM is the only one " +
                "trending up.",
            SessionOutcome.Completed(),
        )
        canned(
            "s-layout",
            "The settings screen overflows on a folded phone.",
            ToolCall.EditFile("/workspace/web/src/settings.css"),
            listOf("1 hunk applied"),
            "The grid was fixed at three columns. Swapped it for `auto-fit` with a min-width so " +
                "it collapses on its own instead of needing another breakpoint.",
            SessionOutcome.Completed("1 file changed"),
        )
        canned(
            "s-tests",
            "Why is the scheduler suite flaky?",
            ToolCall.Shell("./gradlew :scheduler:test --tests '*Retry*'", "/workspace"),
            listOf("14 tests, 2 failed"),
            "Both failures read the wall clock instead of the injected one, so they lose whenever " +
                "the run crosses a second boundary. Injecting the test clock fixes them.",
            SessionOutcome.Completed(),
        )
        canned(
            "s-types",
            "Clean up the loose types in the API client.",
            ToolCall.Search("any", "/workspace/web/src"),
            listOf("14 matches"),
            "Replaced all 14 with real response types generated from the OpenAPI document.",
            SessionOutcome.Completed("6 files changed"),
        )

        // An in-flight session the OS killed underneath. Disconnected is a normal state in Box:
        // the VM takes ~90s to boot and Android reclaims it whenever it feels like it.
        val reviewAt = stamp(6)
        emit(AgentEvent.SessionStarted(next(), "s-review", reviewAt, "cursor", "Review PR #42", "/workspace/awesome-app"))
        emit(AgentEvent.UserMessage(next(), "s-review", reviewAt, "Review PR #42 and tell me what you'd push back on."))
        emit(
            AgentEvent.AgentMessage(
                next(), "s-review", stamp(6), "m-review-1",
                "Pulling the diff now — it touches the payment retry path, so I'll read the " +
                    "surrounding code before I judge it.",
            ),
        )
        val reviewCall = "c-review-1"
        emit(AgentEvent.ToolCallStarted(next(), "s-review", stamp(5), reviewCall, ToolCall.Shell("gh pr diff 42", "/workspace/awesome-app")))
        emit(AgentEvent.ToolCallProgress(next(), "s-review", stamp(5), reviewCall, "4 files changed, 118 insertions(+), 31 deletions(-)\n"))
        emit(
            AgentEvent.AgentError(
                next(), "s-review", stamp(5),
                message = "Lost the connection to the computer",
                detail = "Android stopped the runtime process while Box was in the background.",
                recoverable = true,
            ),
        )
        connections["s-review"] = MutableStateFlow(
            SessionConnection.Disconnected("The computer stopped while Box was in the background", retrying = false),
        )
    }

    private fun canned(
        sessionId: String,
        prompt: String,
        call: ToolCall,
        output: List<String>,
        reply: String,
        outcome: SessionOutcome,
    ) {
        val at = stamp()
        emit(AgentEvent.SessionStarted(next(), sessionId, at, "claude", prompt.take(40), "/workspace"))
        emit(AgentEvent.UserMessage(next(), sessionId, at, prompt))
        val callId = "c-${ids.incrementAndGet()}"
        emit(AgentEvent.ToolCallStarted(next(), sessionId, at, callId, call))
        emit(AgentEvent.ToolCallFinished(next(), sessionId, at, callId, ToolOutcome.Success(summary = output.joinToString())))
        emit(AgentEvent.AgentMessage(next(), sessionId, at, "m-$sessionId", reply))
        emit(AgentEvent.SessionEnded(next(), sessionId, at, outcome))
        connections[sessionId] = MutableStateFlow(SessionConnection.Ended)
    }

    private companion object {
        const val CLONE_SESSION = "s-clone"
        const val AUDIT_SESSION = "s-audit"

        val VITE_CONFIG_PATCH = """
            @@ -1,8 +1,12 @@
             import { defineConfig } from 'vite'
             import vue from '@vitejs/plugin-vue'

             export default defineConfig({
               plugins: [vue()],
            -  server: {
            -    port: 5173,
            -  },
            +  server: {
            +    // Box forwards this port to the preview pane, so bind every interface.
            +    host: true,
            +    port: 5173,
            +    strictPort: true,
            +  },
             })
        """.trimIndent()
    }
}
