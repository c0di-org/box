package dev.localagent.workstation.agent

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.runtime.qemu.IAgentSession
import dev.localagent.runtime.qemu.IAgentSessionCallback
import dev.localagent.runtime.qemu.IRuntimeControl
import dev.localagent.runtime.qemu.RuntimeService
import dev.localagent.runtime.qemu.RuntimeStateCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real [AgentBackend]: agent harnesses running in the guest.
 *
 * Replaces [FakeAgentBackend]. The shapes are the same because the fake was written against this
 * contract, but the ownership is not: a session belongs to the `:computer` process, not to this
 * object. This class attaches to sessions, translates their output, and sends decisions back —
 * and can be destroyed and rebuilt mid-session without the agent noticing.
 */
class GuestAgentBackend(
    context: Context,
    private val scope: CoroutineScope,
) : AgentBackend {
    private val appContext = context.applicationContext

    private val harnessesState = MutableStateFlow(INSTALLED_HARNESSES.map { it.descriptor })
    override val harnesses: StateFlow<List<HarnessDescriptor>> = harnessesState.asStateFlow()

    private val sessionsState = MutableStateFlow<List<SessionSummary>>(emptyList())
    override val sessions: StateFlow<List<SessionSummary>> = sessionsState.asStateFlow()

    private val records = ConcurrentHashMap<String, Record>()
    private val store = SessionStore(appContext)

    // The same file MainActivity and OpeningHistory use. Box's settings are a handful of keys
    // read in two processes; a store of its own would be more machinery than the thing stored.
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val modeState = MutableStateFlow(
        runCatching { AgentPermissionMode.valueOf(preferences.getString(MODE_KEY, null) ?: "") }
            .getOrDefault(AgentPermissionMode.Ask),
    )
    override val permissionMode: StateFlow<AgentPermissionMode> = modeState.asStateFlow()

    /**
     * Told to every harness that is already running, and to every one that starts afterwards.
     *
     * Written to disk before it is sent, because the guest process is the volatile half of this
     * pair: `:computer` can be killed at any moment and the mode has to survive that, while a
     * harness that never hears the change will be told again the moment it re-attaches.
     */
    override suspend fun setPermissionMode(mode: AgentPermissionMode) {
        modeState.value = mode
        preferences.edit().putString(MODE_KEY, mode.name).apply()
        records.values.forEach { it.write(permissionModeCommand(mode)) }
    }

    private fun permissionModeCommand(mode: AgentPermissionMode) =
        mapOf("type" to "permission_mode", "mode" to mode.wire)

    private val modelState = MutableStateFlow(
        AgentModel.ofName(preferences.getString(MODEL_KEY, null)),
    )
    override val agentModel: StateFlow<AgentModel> = modelState.asStateFlow()

    /**
     * Persisted and broadcast exactly like the permission mode, and for the same reasons.
     *
     * Worth one note of its own: this reaches a *running* session. The harness asks the SDK to
     * switch, so a conversation mid-task answers its next turn as the new model without being
     * restarted — which is the difference between a model setting and the machine size next to it
     * on the same sheet, where a change waits for the box to be reopened.
     */
    override suspend fun setAgentModel(model: AgentModel) {
        modelState.value = model
        preferences.edit().putString(MODEL_KEY, model.name).apply()
        records.values.forEach { it.write(modelCommand(model)) }
    }

    private fun modelCommand(model: AgentModel) =
        mapOf("type" to "model", "model" to model.wire)

    /**
     * The last window Box was read in, held only in memory.
     *
     * Not persisted, unlike the permission mode, and the asymmetry is deliberate: a mode the user
     * chose is still their choice after a restart, while a window size restored from disk describes
     * a window that no longer exists. Null until the UI has measured itself, which is the honest
     * state — a harness that is told nothing writes the way it always has.
     */
    @Volatile private var viewport: AgentViewport? = null

    override suspend fun setViewport(viewport: AgentViewport) {
        if (this.viewport == viewport) return
        this.viewport = viewport
        records.values.forEach { it.write(viewportCommand(viewport)) }
    }

    private fun viewportCommand(viewport: AgentViewport): Map<String, Any> = mapOf(
        "type" to "viewport",
        "layout" to viewport.layout.wire,
        "widthDp" to viewport.widthDp,
        "hardwareKeyboard" to viewport.hardwareKeyboard,
    )

    /** One attached session: its live chunks, its handle, and how the transcript reached it. */
    private class Record(
        val id: String,
        val harnessId: String,
        var title: String,
        val workingDirectory: String,
        /**
         * Read back from the session index at launch, rather than started in this process.
         *
         * Which is the same thing as saying `:computer` has a log for it: a session only reaches
         * the index once it has been given work. See [attachPlan] — this is what makes a
         * transcript readable with the box closed.
         */
        val restored: Boolean = false,
        /**
         * Whether [title] came from something the user actually said.
         *
         * A task started from the "New task" button has no prompt to be named after, so it gets a
         * placeholder — and until this existed it kept that placeholder forever, however much work
         * it went on to do. A list of tasks all called the same thing is not a list.
         *
         * Restored sessions count as named even when their stored title is a placeholder from an
         * older build: renaming a task the user has been looking at for a week, because a message
         * happened to arrive, would be worse than the dull name.
         */
        @Volatile var named: Boolean = false,
    ) {
        val chunks = MutableSharedFlow<Pair<Long, ByteArray>>(
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        val connection = MutableStateFlow<SessionConnection>(SessionConnection.Connecting)
        val logPath = CompletableDeferred<String>()
        @Volatile var handle: IAgentSession? = null

        /**
         * Whether this session has been opened against the guest that is running *now*.
         *
         * Not "has a log": those two used to be the same question, answered by whether the log
         * path had arrived, and they stopped being the same the moment a closed box was allowed to
         * fetch that path just to read the history. A session whose log has been read but whose
         * harness has never been started in this guest still needs opening, or the message the
         * user typed while the box was closed would be delivered to nothing.
         */
        @Volatile var opened: Boolean = false

        /** Last status published, so a live line only republishes when it changes something. */
        @Volatile var status: SessionStatus = SessionStatus.Idle

        /**
         * A second reader of the same bytes, kept apart from the transcript's.
         *
         * The list has to say "needs you" whether or not anyone is looking at the conversation, and
         * `events()` only runs while a collector is attached. This cursor is fed from the binder
         * callback instead, so a session that stopped to ask something is visible in the list even
         * when its transcript was never opened.
         */
        val statusCursor = SessionLogCursor()

        /** Claimed atomically: `events()` and `send()` both attach, and they race on a cold start. */
        val attached = AtomicBoolean(false)

        /**
         * Commands written before the guest process could take them.
         *
         * Not only a cold-boot concern. `openAgentSession` returns before `onAttached` arrives —
         * the VM has to start the process first — so even on a warm computer the first prompt of a
         * new session is written to a handle that does not exist yet. Without this queue that
         * prompt is silently dropped and the agent simply never begins.
         */
        val outbox = mutableListOf<String>()
    }

    init {
        // A restarted UI process starts knowing nothing. The index says which sessions exist; the
        // logs say what happened in them.
        val restored = store.load().map { summary ->
            // Nothing is running yet, whatever was running when this list was last saved.
            //
            // [SessionStatus.Active] means "the agent is doing something right now", and a status
            // read back from disk cannot mean that: the process it described belonged to a guest
            // that has since been shut, or to a Box that has since been restarted. Restoring it
            // verbatim is what left every task in the list wearing a live dot — including ones
            // whose harness had not existed for hours — which made the list unable to answer the
            // one question it exists to answer: which of these is actually working.
            //
            // [SessionStatus.NeedsYou] is deliberately kept. A session that stopped to ask
            // something is still waiting for an answer, and that is a fact about the conversation
            // rather than about any process that happens to be alive.
            if (summary.status is SessionStatus.Active) summary.copy(status = SessionStatus.Idle)
            else summary
        }
        restored.forEach { summary ->
            records[summary.id] = Record(
                summary.id, summary.harnessId, summary.title, summary.workingDirectory,
                restored = true,
                // Whatever it is called, it has been called that since before this process
                // started, and the user has seen it. See [Record.named].
                named = true,
            ).apply { status = summary.status }
        }
        sessionsState.value = restored
    }

    // ---- runtime binding ---------------------------------------------------

    private val controlState = MutableStateFlow<IRuntimeControl?>(null)
    private var bound = false

    /**
     * Whether the guest is actually up — which is *not* the same question as whether `:computer`
     * can be bound.
     *
     * The service binds instantly whether or not a VM is running, so a bind result says nothing
     * about readiness. Opening a session against a cold runtime does not queue or retry: it throws
     * inside the guest, the session ends before it began, and the conversation shows a failure the
     * user cannot act on. So readiness is tracked explicitly, from the one authority on it.
     */
    @Volatile private var runtimeReady = false

    /**
     * The computer reaching Ready is the moment a session that was waiting on it can finally open.
     *
     * Box lets you send a message to a computer that is off — it starts it and holds what you typed
     * — so something has to notice when the ~3 minute boot finishes. Listening here rather than
     * having the ViewModel poke the backend keeps that behaviour true even if the UI is elsewhere.
     */
    private val runtimeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val payload = intent?.getBundleExtra(RuntimeService.EXTRA_STATE) ?: return
            val state = RuntimeStateCodec.decode(payload) ?: return
            val ready = state == RuntimeState.Ready
            runtimeReady = ready
            if (!ready) {
                // The guest is gone or not there yet; anything held for it is held a while longer.
                records.values.forEach {
                    it.attached.set(false)
                    it.opened = false
                    it.handle = null
                }
                return
            }
            // Only the sessions actually waiting on the box — which is almost never all of them.
            //
            // Attaching every record meant opening the box started a harness process per
            // conversation, at once, whether or not anyone had been near them: on a two-core TCG
            // guest, eight idle tasks is eight `claude` processes that never exit, with the one
            // being typed into queued behind all of them. Measured on device: load average 14.9,
            // ~70% of the guest spent on conversations nobody had opened.
            //
            // Nothing is lost by waiting — [events] and [send] both attach, so a session opens the
            // moment it is looked at or spoken to. The only thing that cannot wait is one holding
            // something typed while the box was shut, which is what the outbox is for.
            scope.launch {
                records.values
                    .filter { synchronized(it.outbox) { it.outbox.isNotEmpty() } }
                    .forEach { attach(it) }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            controlState.value = IRuntimeControl.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // `:computer` died. Sessions in it died with it; their logs did not.
            controlState.value = null
            records.values.forEach {
                it.attached.set(false)
                it.opened = false
                it.handle = null
                it.connection.value = SessionConnection.Disconnected("The computer stopped", true)
            }
        }
    }

    init {
        // Registered after the receiver above exists, not in the constructor's first init block.
        ContextCompat.registerReceiver(
            appContext,
            runtimeStateReceiver,
            IntentFilter(RuntimeService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // A UI process that restarted while the VM kept running would otherwise believe the
        // computer is down until something happened to change its state. Asking cannot start
        // `:computer` — silence is a legitimate answer meaning no VM is running.
        appContext.sendBroadcast(
            Intent(RuntimeService.ACTION_QUERY_STATE).setPackage(appContext.packageName),
        )
    }

    private suspend fun control(): IRuntimeControl? {
        controlState.value?.let { return it }
        synchronized(this) {
            if (!bound) {
                bound = appContext.bindService(
                    Intent(appContext, RuntimeService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }
        }
        // Binding is fast when `:computer` is alive and pointless when it is not; either way the
        // caller gets an answer rather than hanging on a VM that may be 170 seconds from ready.
        return withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { controlState.filterNotNull().first() }
    }

    // ---- AgentBackend ------------------------------------------------------

    override fun events(sessionId: String): Flow<AgentEvent> = channelFlow {
        val record = records[sessionId] ?: return@channelFlow
        val cursor = SessionLogCursor()
        val context = HarnessWire.Context(
            sessionId = record.id,
            harnessId = record.harnessId,
            title = record.title,
            workingDirectory = record.workingDirectory,
        )
        var ordinal = 0L
        val gate = Mutex()
        var replayed = false
        var logFile: String? = null
        val held = mutableListOf<Pair<Long, ByteArray>>()

        suspend fun emitLines(lines: List<String>) {
            for (line in lines) {
                HarnessWire.parse(line, context, ordinal++)?.let { send(it) }
            }
        }

        /**
         * One live chunk, recovering first if anything was lost on the way here.
         *
         * A chunk can go missing — `record.chunks` is emitted with `tryEmit`, which returns false
         * on a full buffer, and the drop was reasoned away with "the log replay will still carry
         * it". It did not: the log is read once, at `replayed`, and after that the watermark moved
         * only on live chunks. The gap was then coerced to zero and the watermark pushed past
         * bytes nobody had read, so nothing could ever go back for them.
         *
         * This is what makes that comment true. The bytes are on disk before the callback fires —
         * `AgentSessionHost.consume` appends and flushes, *then* notifies — so re-reading from the
         * watermark recovers the lost chunk and this one together, in order, with the partial line
         * held in the cursor still valid because the re-read starts exactly where it left off.
         */
        suspend fun applyChunk(logPath: String?, offset: Long, bytes: ByteArray) {
            val missing = cursor.gapBefore(offset)
            if (missing > 0 && logPath != null) {
                Log.w(TAG, "session ${record.id}: $missing bytes never reached the reader; re-reading the log")
                emitLines(cursor.readFile(File(logPath)))
            }
            emitLines(cursor.accept(offset, bytes))
        }

        val subscribed = CompletableDeferred<Unit>()
        val live = launch {
            record.chunks
                .onSubscription { subscribed.complete(Unit) }
                .collect { chunk ->
                    gate.withLock {
                        // Anything arriving before the log has been replayed is held, not applied:
                        // letting it through first would move the cursor past history not yet read.
                        if (replayed) applyChunk(logFile, chunk.first, chunk.second)
                        else held += chunk
                    }
                }
        }

        // Subscribe before touching the log, so nothing falls into the gap between them.
        subscribed.await()
        attach(record)
        // Waited for however long it takes, which on a cold phone is the three minutes the box
        // needs to open. This used to give up after ten seconds and never come back: opening a
        // conversation while the computer was still booting read the log zero times, and when the
        // session finally attached the only thing left to show was whatever it said next. The
        // history was on disk the whole time, and the transcript said "Nothing yet".
        val log = record.logPath.await()
        // Published for the live collector above, which starts before the path is known and needs
        // it to recover a chunk that went missing. Written under the same lock that guards the
        // cursor, so a chunk cannot read a half-assigned path.
        gate.withLock {
            logFile = log
            emitLines(cursor.readFile(File(log)))
            held.forEach { applyChunk(log, it.first, it.second) }
            held.clear()
            replayed = true
            // Said once, inside the lock, so it lands between the last historical line and the
            // first live one and cannot be overtaken by either. What it buys is in
            // [AgentEvent.CaughtUp]: some events are a thing to act on when they arrive and a
            // thing to merely remember when they are read back.
            send(AgentEvent.CaughtUp("caught-up:${record.id}", record.id, System.currentTimeMillis()))
        }

        live.join()
    }

    override fun connection(sessionId: String): StateFlow<SessionConnection> =
        (records[sessionId]?.connection ?: MutableStateFlow(SessionConnection.Ended)).asStateFlow()

    override suspend fun startSession(
        harnessId: String,
        prompt: String?,
        attachments: List<Attachment>,
    ): String {
        require(harnessRuntime(harnessId) != null) { "Unknown harness: $harnessId" }
        val id = "s-" + System.currentTimeMillis().toString(36)
        val record = Record(
            id = id,
            harnessId = harnessId,
            title = prompt?.toTitle() ?: UNNAMED,
            workingDirectory = WORKSPACE,
            named = prompt != null,
        )
        records[id] = record
        publish(record, SessionStatus.Active, prompt)

        attach(record)
        if (prompt != null) send(id, prompt, attachments)
        return id
    }

    override suspend fun send(sessionId: String, text: String, attachments: List<Attachment>) {
        val record = records[sessionId] ?: return
        // The first thing the user says is the name of the task, the way it is in every chat app.
        // Only the first: a task is named after what it was for, not after the last thing said in
        // it, and a title that moved under the reader would make the list unreadable in the other
        // direction.
        if (!record.named) {
            text.toTitle()?.let { record.title = it }
            record.named = true
        }
        attach(record)
        record.write(promptCommand(text, attachments))
        publish(record, SessionStatus.Active, text)
    }

    /**
     * A turn, with anything the user showed alongside it.
     *
     * The files are not carried here. They were written into the shared folder before this was
     * called and reach the guest by the sync that folder already has, so all that crosses is the
     * path each one will be at. What the guest does *not* get is a promise that they have arrived
     * yet: the harness waits for them before handing the turn to the model, because the copy is a
     * second or so behind the keystroke and the alternative is an agent that looks too early and
     * tells the user it cannot see their picture.
     */
    private fun promptCommand(text: String, attachments: List<Attachment>): Map<String, Any> =
        if (attachments.isEmpty()) {
            mapOf("type" to "prompt", "text" to text)
        } else {
            mapOf(
                "type" to "prompt",
                "text" to text,
                "attachments" to attachments.map {
                    mapOf(
                        "guestPath" to it.guestPath,
                        "name" to it.name,
                        "mimeType" to it.mimeType,
                        "bytes" to it.bytes,
                    )
                },
            )
        }

    override suspend fun resolvePermission(
        sessionId: String,
        requestId: String,
        decision: PermissionDecision,
    ) {
        val record = records[sessionId] ?: return
        record.write(
            buildMap {
                put("type", "decision")
                put("requestId", requestId)
                put(
                    "decision",
                    when (decision) {
                        is PermissionDecision.Allow -> "allow"
                        // An answered question goes down as a plain allow carrying its answers,
                        // rather than as a decision word of its own. A guest image older than
                        // questions then reads an allow it already understands and drops a field
                        // it has never heard of — the answer is lost, which is exactly today's
                        // behaviour. A new word would have been read as "not allowed" and failed
                        // the call outright, which is worse than the bug being fixed.
                        is PermissionDecision.Answered -> "allow"
                        is PermissionDecision.AllowAlways -> "allow_always"
                        is PermissionDecision.Deny -> "deny"
                        is PermissionDecision.Abandoned -> "deny"
                    },
                )
                if (decision is PermissionDecision.Answered) put("answers", decision.answers)
            },
        )
    }

    override suspend fun resolveConnect(
        sessionId: String,
        requestId: String,
        outcome: ConnectOutcome,
    ) {
        val record = records[sessionId] ?: return
        record.write(
            buildMap {
                put("type", "connect_result")
                put("requestId", requestId)
                put("connected", outcome.connected)
                // Omitted rather than sent as null: the harness reads these to build a sentence,
                // and "connected as null" is worse than a sentence that does not mention a name.
                outcome.login?.let { put("login", it) }
                outcome.repositories?.let { put("repositories", it) }
            },
        )
    }

    override suspend fun interrupt(sessionId: String) {
        records[sessionId]?.write(mapOf("type" to "interrupt"))
    }

    /**
     * A command of its own, not an `interrupt` carrying a sub-agent id.
     *
     * The guest image is upgraded independently of the APK, so this can reach a harness that has
     * never heard of sub-agents — and an older harness reads an unknown *field* while acting on the
     * type it recognises. `{"type":"interrupt","subAgentId":…}` would therefore stop the whole
     * session on exactly the phones least able to explain why. An unknown *type* is dropped with a
     * diagnostic, which is the failure this should have.
     */
    override suspend fun interruptSubAgent(sessionId: String, subAgentId: String) {
        records[sessionId]?.write(mapOf("type" to "stop_subagent", "subAgentId" to subAgentId))
    }

    override suspend fun closeSession(sessionId: String) {
        val record = records.remove(sessionId) ?: return
        record.connection.value = SessionConnection.Ended
        runCatching { control()?.closeAgentSession(sessionId) }
        sessionsState.value = sessionsState.value.filterNot { it.id == sessionId }
        store.save(sessionsState.value)
    }

    // ---- attaching ---------------------------------------------------------

    /**
     * Idempotent, and safe with the box shut: see [attachPlan] for what it can do when.
     *
     * The one thing it must never do is start a VM. Binding `:computer` creates the process that
     * holds the session logs — nothing more — and QEMU is started by `ACTION_START` alone, which
     * is a thing the user asks for.
     */
    private suspend fun attach(record: Record) {
        if (record.attached.get()) return
        val plan = attachPlan(
            runtimeReady = runtimeReady,
            opened = record.opened,
            hasHistory = record.restored || record.logPath.isCompleted,
        )
        if (plan == AttachPlan.Wait) {
            // Not a failure, and deliberately not an attempt. The broadcast for Ready brings us
            // back here with whatever the user typed still in the outbox.
            record.connection.value =
                SessionConnection.Disconnected("The computer is still starting", true)
            return
        }
        // The log has already been handed over, and `events()` reads it from disk itself. There is
        // nothing left for a closed box to ask.
        if (plan == AttachPlan.ReadHistory && record.logPath.isCompleted) return
        val control = control() ?: run {
            record.connection.value =
                SessionConnection.Disconnected("The computer is still starting", true)
            return
        }
        if (!record.attached.compareAndSet(false, true)) return
        val callback = Listener(record, opening = plan == AttachPlan.Open)
        val harness = harnessRuntime(record.harnessId) ?: run {
            record.attached.set(false)
            record.connection.value = SessionConnection.Ended
            scope.launch { publish(record, SessionStatus.Failed("This harness is not installed.")) }
            return
        }
        runCatching {
            if (plan == AttachPlan.Open) {
                record.opened = true
                control.openAgentSession(
                    record.id,
                    harness.command,
                    record.workingDirectory,
                    Bundle().apply {
                        putString("BOX_SESSION_CWD", record.workingDirectory)
                        // Which task this is, so the harness can continue the conversation the
                        // transcript is about to replay rather than opening a fresh one behind it.
                        // Box's id and the agent's own session id are different things; the
                        // harness keeps the pairing on the workspace disk, because that is where
                        // the transcript it would resume also survives an update.
                        putString("BOX_SESSION_ID", record.id)
                        putString("HOME", GUEST_HOME)
                        if (harness.claudeEnvironment) {
                            // Also sent as a standing setting the moment this attaches, and the
                            // duplication is deliberate: the harness builds its query before it has
                            // read a single line of stdin, so a session that learned its model only
                            // from the command would open on the CLI's default and be corrected a
                            // round trip later — through a call an older Claude Code may not have.
                            // The command is what moves a session already running.
                            putString("BOX_MODEL", modelState.value.wire)
                            // The credential is read by the harness from this path. It is never placed
                            // in the environment itself, which would put it in an open payload.
                            putString("BOX_CREDENTIAL_FILE", CREDENTIAL_PATH)
                        } else {
                            // DSH keeps its own sessions/config on the persistent workspace disk.
                            // The API key itself is read by the wrapper from a file in .config and
                            // never crosses Android's session-open payload.
                            putString("DSH_HOME", DSH_HOME)
                            putString("DSH_SESSION_ROOT", DSH_SESSION_ROOT)
                            putString("BOX_DEEPSEEK_API_KEY_FILE", DEEPSEEK_API_KEY_PATH)
                        }
                    },
                    callback,
                )
            } else {
                // Both [AttachPlan.Reattach] and [AttachPlan.ReadHistory] are this call. The
                // service answers a session it is running by streaming it, and one it has never
                // heard of with the path to its log — which is precisely the read-back.
                control.attachAgentSession(record.id, callback)
            }
        }.onFailure {
            record.attached.set(false)
            record.opened = false
            record.connection.value = SessionConnection.Disconnected("Could not reach the computer", true)
        }
    }

    private inner class Listener(
        private val record: Record,
        /**
         * Whether this attachment is the one that started the process.
         *
         * A newly started harness has nothing in flight by definition, which is what lets
         * [onAttached] tell a session that has only been looked at from one that is working. False
         * for a reattachment, where the process has been running without us and may be mid-turn.
         */
        private val opening: Boolean,
    ) : IAgentSessionCallback.Stub() {
        /**
         * Whether there was ever a process behind this attachment.
         *
         * False for a transcript read back with the box closed. `attachAgentSession` answers a
         * session nothing is running with `onAttached(null)` and then `onClosed` — the same two
         * calls a session makes when it really does end, because from the service's side there is
         * nothing to tell apart. Taking that at face value would stamp a task that has been
         * waiting on the user since yesterday as Finished, and — since a summary carries the
         * moment it was published — jump it to the top of the list for having been *looked at*.
         */
        @Volatile private var live = false

        override fun onAttached(session: IAgentSession?, logPath: String) {
            record.handle = session
            live = session != null
            record.connection.value =
                if (session == null) SessionConnection.Ended else SessionConnection.Live
            if (!record.logPath.isCompleted) record.logPath.complete(logPath)
            if (session != null) {
                // Read before the flush below empties it, because it is the difference between a
                // session that has work and one that has only been looked at. See the publish.
                val waiting = synchronized(record.outbox) { record.outbox.isNotEmpty() }
                // Whatever the user asked for while the computer was still starting — with the
                // standing settings ahead of it. Every harness process starts out asking, on no
                // particular model, and knowing nothing about the window, including one that came
                // back after `:computer` died, so a prompt delivered before them would run its
                // first turn under a setting the user had already changed, on a model they had
                // changed away from, or write for a screen it cannot see. This is the one place
                // all of those orders meet, so it is the only place that can promise them.
                record.flushOutbox(
                    first = listOfNotNull(
                        permissionModeCommand(modeState.value),
                        modelCommand(modelState.value),
                        viewport?.let(::viewportCommand),
                    ),
                )
                // A session that failed to open earlier is no longer failed, and the list has to
                // stop saying so. What it must not say instead is that the agent is working.
                //
                // Attaching is not working. Box opens a session when its conversation is looked
                // at, so most attachments have nobody waiting on them, and reporting every one as
                // [SessionStatus.Active] put a live dot on tasks nobody had asked for anything —
                // next to a transcript stuck on "Waking the agent", which is how a warm session
                // came to be indistinguishable from a wedged one. See issue #71.
                //
                // Two exceptions, and both really are work. A turn queued in the outbox while the
                // box was shut is about to run. And a reattachment is to a process that has been
                // going without us, which may well be mid-turn — [opening] is false there, and
                // nothing else would put the status back.
                scope.launch {
                    publish(record, if (opening && !waiting) SessionStatus.Idle else SessionStatus.Active)
                }
            }
        }

        override fun onData(offset: Long, chunk: ByteArray) {
            // tryEmit rather than emit: this is a binder thread and must never block `:computer`.
            //
            // The claim in this log line is now true, and was not before. A dropped chunk leaves a
            // gap between the reader's watermark and the next chunk's offset, and `events()` sees
            // it and re-reads the log from the watermark. Until that existed the gap was coerced
            // to zero, the watermark was pushed past bytes nobody had read, and the next surviving
            // fragment was welded onto the truncated line this one ended in the middle of.
            if (!record.chunks.tryEmit(offset to chunk)) {
                Log.w(TAG, "dropped a live chunk at $offset; the reader will re-read the log for it")
            }
            readStatus(record, offset, chunk)
        }

        override fun onDiagnostic(text: String) {
            Log.w(TAG, "harness: $text")
        }

        override fun onClosed(exitCode: Int, error: String?) {
            record.handle = null
            record.attached.set(false)
            // Honest either way: a session with no process is over, whether it ended a second ago
            // or last week. The conversation shows it under the box's own "closed · Open" banner.
            record.connection.value = SessionConnection.Ended
            if (!live) return
            scope.launch {
                publish(record, if (error == null) SessionStatus.Finished else SessionStatus.Failed(error))
            }
        }
    }

    /**
     * Feeds the live stream through [sessionStatusFor], republishing only on a real change.
     *
     * Locked because the cursor carries a partial line between calls. Binder delivers one `oneway`
     * transaction to a node at a time, so these do not overlap — but they arrive on whichever pool
     * thread is free, and a half-line written by one thread has to be visible to the next.
     */
    private fun readStatus(record: Record, offset: Long, chunk: ByteArray) {
        val lines = synchronized(record.statusCursor) {
            runCatching { record.statusCursor.accept(offset, chunk) }.getOrElse { return }
        }
        val context = HarnessWire.Context(
            sessionId = record.id,
            harnessId = record.harnessId,
            title = record.title,
            workingDirectory = record.workingDirectory,
        )
        for (line in lines) {
            val next = sessionStatusFor(line, context) ?: continue
            if (record.status == next) continue
            scope.launch { publish(record, next) }
        }
    }

    /**
     * Send a command, or hold it until there is something to send it to.
     *
     * The lock covers the delivery as well as the decision, which matters more than it looks:
     * without it a message written just as the session attaches can overtake one that has been
     * queued since before the boot, and the agent would read the user's turns out of order.
     * `IAgentSession.write` is `oneway`, so nothing waits on the guest while the lock is held.
     *
     * Nothing is held for a session with no process unless it is a *turn*. The outbox is not only
     * a queue: it is Box's record of who was genuinely waiting on the box, and
     * [runtimeStateReceiver] opens a harness for every session that has one. A turn earns that —
     * somebody typed it at a shut box and is owed an answer the moment the guest is up. A standing
     * setting does not, and needs nothing held either, because [Listener.onAttached] states the
     * current mode and viewport to every harness ahead of anything else it will read.
     *
     * Holding settings here anyway is what brought the fan-out back after it was fixed. The UI
     * calls `setViewport` as soon as it has measured itself — on every launch, for every record —
     * so every restored conversation began life with a viewport command in its outbox, "was anyone
     * waiting on the box" became true of all of them, and the next open started a harness per
     * conversation. Measured on device: three `claude` processes in a two-core guest to answer one
     * "hi", and 455 s to a first reply. See docs/runtime.md.
     */
    private fun Record.write(command: Map<String, Any>) {
        val json = HarnessWire.encode(command)
        synchronized(outbox) {
            val live = handle
            if (live == null) {
                if (!isStandingSetting(command)) outbox += json
                return
            }
            // Ordering, and it applies to settings too: one sent past a queued turn would reach
            // the agent after work that was meant to run under it.
            if (outbox.isNotEmpty()) {
                outbox += json
                return
            }
            runCatching { live.write((json + "\n").toByteArray()) }
                .onFailure {
                    Log.e(TAG, "could not answer the harness", it)
                    outbox += json
                }
        }
    }


    /**
     * Everything written while the guest process was still starting, in the order it was written,
     * behind [first] — which exists so a session's standing settings can be stated to a brand new
     * process before the work it was queued to do, in the order given.
     */
    private fun Record.flushOutbox(first: List<Map<String, Any>> = emptyList()) {
        synchronized(outbox) {
            val live = handle ?: return
            val undelivered = mutableListOf<String>()
            for (json in first.map(HarnessWire::encode) + outbox) {
                runCatching { live.write((json + "\n").toByteArray()) }
                    .onFailure {
                        Log.e(TAG, "could not deliver a queued command", it)
                        undelivered += json
                    }
            }
            outbox.clear()
            outbox += undelivered
        }
    }

    // ---- session list ------------------------------------------------------

    private fun publish(record: Record, status: SessionStatus, preview: String? = null) {
        // A closed session does not go quiet the instant it is closed, and this is the one place
        // that would let it undo the closing. [closeSession] drops the record and rewrites the
        // list, but the callback still holds this same object and the guest process is still
        // being asked to stop; the lines already in flight arrive after the row is gone. Each one
        // used to land here and put it straight back — stamped with the current time, so it
        // sorted to the *top* of the list, and saved to disk, so it survived a restart. From the
        // outside that reads as a task refusing to be deleted: it disappears, reappears above
        // everything else marked off, and then comes back to life.
        //
        // Identity rather than the id: a genuinely new task reusing an id is a different object
        // and is welcome to publish. This only rejects a record the backend has let go of.
        if (records[record.id] !== record) return
        record.status = status
        val summary = SessionSummary(
            id = record.id,
            harnessId = record.harnessId,
            title = record.title,
            status = status,
            updatedAt = System.currentTimeMillis(),
            workingDirectory = record.workingDirectory,
            preview = preview?.take(120),
        )
        sessionsState.value = (sessionsState.value.filterNot { it.id == record.id } + summary)
            .sortedByDescending { it.updatedAt }
        store.save(sessionsState.value)
    }

    /**
     * A task's name, from the first line of what was said. Null when there is nothing to name it
     * after, so the caller keeps whatever it had rather than replacing a real title with a shrug.
     *
     * A whole first line, cut at [TITLE_CHARS] — not a summary. Summarising costs a model call and
     * would be wrong often enough to be worse than the user's own words, which they wrote and
     * therefore recognise.
     */
    private fun String.toTitle(): String? =
        trim().lineSequence().firstOrNull()?.trim()?.take(TITLE_CHARS)?.ifBlank { null }

    /**
     * Internal rather than private because the preference file is shared: [BoxViewModel] keeps the
     * "Open faster" switch in the same one, and two copies of a file name is how they drift apart.
     */
    internal companion object {
        const val TAG = "BoxAgentBackend"
        const val WORKSPACE = "/workspace"
        const val GUEST_HOME = "/home/agent"
        // On the workspace disk, not the agent's home: home is on the system disk and a
        // guest image update replaces it, which would quietly discard the credential and
        // make the user sign in again after every Box update.
        const val CREDENTIAL_PATH = "/workspace/.config/box/credentials.json"
        const val DSH_HOME = "/workspace/.config/dsh"
        const val DSH_SESSION_ROOT = "/workspace/.config/dsh/sessions"
        const val DEEPSEEK_API_KEY_PATH = "/workspace/.config/box/deepseek-api-key"
        const val BIND_TIMEOUT_MILLIS = 4_000L
        const val PREFERENCES = "box_product"
        const val MODE_KEY = "agent_permission_mode"
        const val MODEL_KEY = "agent_model"

        /** What a task is called before anybody has said anything in it. */
        const val UNNAMED = "New task"
        const val TITLE_CHARS = 60
    }
}

/**
 * What attaching to a session can do, given what is running.
 *
 * The row worth reading is [AttachPlan.ReadHistory]. A transcript is a log file in `:computer`'s
 * private storage, left there when the agent stopped — and "shut, with a week of work behind it"
 * is the ordinary state of someone coming back to Box. Reading it needs the `:computer` *process*,
 * which binding creates; it does not need a booted VM and must not cause one. This used to be
 * refused outright, and a task with a hundred messages in it opened onto "Nothing yet".
 *
 * [opened] is asked before [hasHistory] because a running computer beats a readable log: the
 * session the user is looking at should be one they can talk to.
 */
/**
 * Whether a command is a standing setting rather than a turn.
 *
 * The distinction decides one thing: whether a session with no process is left without one. Turns
 * are held in the outbox and open a harness the moment the guest is ready, because somebody typed
 * them at a shut box. Settings are not held at all — [GuestAgentBackend.Listener.onAttached]
 * states the current mode and viewport to every harness before anything else reaches it, so
 * queueing them buys nothing and costs a process per conversation.
 *
 * Derived from the command rather than passed in by the caller, so that a future setting
 * broadcast to `records.values` cannot reintroduce the fan-out by forgetting to say so.
 */
internal fun isStandingSetting(command: Map<String, Any>): Boolean =
    command["type"] == "permission_mode" ||
        command["type"] == "model" ||
        command["type"] == "viewport"

internal fun attachPlan(runtimeReady: Boolean, opened: Boolean, hasHistory: Boolean): AttachPlan =
    when {
        runtimeReady && opened -> AttachPlan.Reattach
        runtimeReady -> AttachPlan.Open
        hasHistory -> AttachPlan.ReadHistory
        else -> AttachPlan.Wait
    }

/** See [attachPlan]. */
internal enum class AttachPlan {
    /** Start the harness. Nothing is running this session in the guest that is up now. */
    Open,

    /** `:computer` is already running it; pick the stream up from where the log leaves off. */
    Reattach,

    /** Nothing is running, but something happened here once. Read it back from the log. */
    ReadHistory,

    /** A session with no history and no computer to run it. Nothing to show, nothing to fetch. */
    Wait,
}

/**
 * The session list's reading of one harness line, or null when the line says nothing about it.
 *
 * "Needs you" is the one fact a summary cannot get from the session's own lifecycle — everything
 * else comes from what Box did, while this comes from the agent mid-run. It is what the list
 * exists to show: with several agents working, the one that stopped to ask is the only one that
 * wants anything.
 *
 * Separate from the transcript's fold because `events()` only runs while someone is watching a
 * conversation, and this has to be true for the sessions nobody opened.
 *
 * The substring test is a gate, not a parse: this runs on a binder thread, so only a line that
 * might be a permission event reaches [HarnessWire], which stays the one place that knows the
 * vocabulary. A false positive costs one parse and decides nothing.
 */
internal fun sessionStatusFor(line: String, context: HarnessWire.Context): SessionStatus? {
    if (!line.contains(PERMISSION_HINT)) return null
    return when (val event = HarnessWire.parse(line, context, ordinal = 0)) {
        is AgentEvent.PermissionRequested -> SessionStatus.NeedsYou(reasonFor(event.ask))
        // Answered — by this user, or by a standing "always allow" that never raised a sheet.
        // Either way it is running again, and a list still saying "needs you" would send someone
        // looking for a question that is no longer being asked.
        is AgentEvent.PermissionResolved -> SessionStatus.Active
        else -> null
    }
}

/** Matches both `permission_requested` and `permission_resolved` without parsing either. */
private const val PERMISSION_HINT = "permission_"

/**
 * Why the agent stopped, in words that carry no payload.
 *
 * The ask itself — the diff, the command line — is in the transcript, one tap away. This string is
 * persisted to disk by [SessionStore], so it says the shape of the question and never its contents.
 */
private fun reasonFor(ask: PermissionAsk): String = when (ask) {
    is PermissionAsk.EditFile -> "It wants to change a file"
    is PermissionAsk.RunCommand -> "It wants to run a command"
    is PermissionAsk.NetworkAccess -> "It wants to reach the network"
    // The shape of it, like the rest: which question, and what the answers were, stay in the
    // transcript where this string is not allowed to follow them.
    is PermissionAsk.Questions -> "It asked you a question"
    is PermissionAsk.Generic -> "It needs your decision"
}
