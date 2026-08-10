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

    private val harnessesState = MutableStateFlow(listOf(CLAUDE_CODE))
    override val harnesses: StateFlow<List<HarnessDescriptor>> = harnessesState.asStateFlow()

    private val sessionsState = MutableStateFlow<List<SessionSummary>>(emptyList())
    override val sessions: StateFlow<List<SessionSummary>> = sessionsState.asStateFlow()

    private val records = ConcurrentHashMap<String, Record>()
    private val store = SessionStore(appContext)

    /** One attached session: its live chunks, its handle, and how the transcript reached it. */
    private class Record(
        val id: String,
        val harnessId: String,
        var title: String,
        val workingDirectory: String,
    ) {
        val chunks = MutableSharedFlow<Pair<Long, ByteArray>>(
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        val connection = MutableStateFlow<SessionConnection>(SessionConnection.Connecting)
        val logPath = CompletableDeferred<String>()
        @Volatile var handle: IAgentSession? = null

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
        val restored = store.load()
        restored.forEach { summary ->
            records[summary.id] = Record(
                summary.id, summary.harnessId, summary.title, summary.workingDirectory,
            )
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
                    it.handle = null
                }
                return
            }
            scope.launch { records.values.forEach { attach(it) } }
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
        val held = mutableListOf<Pair<Long, ByteArray>>()

        suspend fun emitLines(lines: List<String>) {
            for (line in lines) {
                HarnessWire.parse(line, context, ordinal++)?.let { send(it) }
            }
        }

        val subscribed = CompletableDeferred<Unit>()
        val live = launch {
            record.chunks
                .onSubscription { subscribed.complete(Unit) }
                .collect { chunk ->
                    gate.withLock {
                        // Anything arriving before the log has been replayed is held, not applied:
                        // letting it through first would move the cursor past history not yet read.
                        if (replayed) emitLines(cursor.accept(chunk.first, chunk.second))
                        else held += chunk
                    }
                }
        }

        // Subscribe before touching the log, so nothing falls into the gap between them.
        subscribed.await()
        attach(record)
        val log = withTimeoutOrNull(LOG_PATH_TIMEOUT_MILLIS) { record.logPath.await() }
        gate.withLock {
            if (log != null) emitLines(cursor.readFile(File(log)))
            held.forEach { emitLines(cursor.accept(it.first, it.second)) }
            held.clear()
            replayed = true
        }

        live.join()
    }

    override fun connection(sessionId: String): StateFlow<SessionConnection> =
        (records[sessionId]?.connection ?: MutableStateFlow(SessionConnection.Ended)).asStateFlow()

    override suspend fun startSession(harnessId: String, prompt: String?): String {
        val id = "s-" + System.currentTimeMillis().toString(36)
        val record = Record(
            id = id,
            harnessId = harnessId,
            title = prompt?.toTitle() ?: "New conversation",
            workingDirectory = WORKSPACE,
        )
        records[id] = record
        publish(record, SessionStatus.Active, prompt)

        attach(record)
        if (prompt != null) send(id, prompt)
        return id
    }

    override suspend fun send(sessionId: String, text: String) {
        val record = records[sessionId] ?: return
        attach(record)
        record.write(mapOf("type" to "prompt", "text" to text))
        publish(record, SessionStatus.Active, text)
    }

    override suspend fun resolvePermission(
        sessionId: String,
        requestId: String,
        decision: PermissionDecision,
    ) {
        val record = records[sessionId] ?: return
        record.write(
            mapOf(
                "type" to "decision",
                "requestId" to requestId,
                "decision" to when (decision) {
                    is PermissionDecision.Allow -> "allow"
                    is PermissionDecision.AllowAlways -> "allow_always"
                    is PermissionDecision.Deny -> "deny"
                    is PermissionDecision.Abandoned -> "deny"
                },
            ),
        )
    }

    override suspend fun interrupt(sessionId: String) {
        records[sessionId]?.write(mapOf("type" to "interrupt"))
    }

    override suspend fun closeSession(sessionId: String) {
        val record = records.remove(sessionId) ?: return
        record.connection.value = SessionConnection.Ended
        runCatching { control()?.closeAgentSession(sessionId) }
        sessionsState.value = sessionsState.value.filterNot { it.id == sessionId }
        store.save(sessionsState.value)
    }

    // ---- attaching ---------------------------------------------------------

    /** Idempotent: opens the session if it is new, re-attaches if `:computer` already has it. */
    private suspend fun attach(record: Record) {
        if (record.attached.get()) return
        if (!runtimeReady) {
            // Not a failure, and deliberately not an attempt. The broadcast for Ready brings us
            // back here with whatever the user typed still in the outbox.
            record.connection.value =
                SessionConnection.Disconnected("The computer is still starting", true)
            return
        }
        val control = control() ?: run {
            record.connection.value =
                SessionConnection.Disconnected("The computer is still starting", true)
            return
        }
        if (!record.attached.compareAndSet(false, true)) return
        val callback = Listener(record)
        runCatching {
            if (record.logPath.isCompleted) {
                control.attachAgentSession(record.id, callback)
            } else {
                control.openAgentSession(
                    record.id,
                    HARNESS_COMMAND,
                    record.workingDirectory,
                    Bundle().apply {
                        putString("BOX_SESSION_CWD", record.workingDirectory)
                        // The credential is read by the harness from this path. It is never placed
                        // in the environment itself, which would put it in an open payload.
                        putString("BOX_CREDENTIAL_FILE", CREDENTIAL_PATH)
                        putString("HOME", GUEST_HOME)
                    },
                    callback,
                )
            }
        }.onFailure {
            record.attached.set(false)
            record.connection.value = SessionConnection.Disconnected("Could not reach the computer", true)
        }
    }

    private inner class Listener(private val record: Record) : IAgentSessionCallback.Stub() {
        override fun onAttached(session: IAgentSession?, logPath: String) {
            record.handle = session
            record.connection.value =
                if (session == null) SessionConnection.Ended else SessionConnection.Live
            if (!record.logPath.isCompleted) record.logPath.complete(logPath)
            if (session != null) {
                // Whatever the user asked for while the computer was still starting.
                record.flushOutbox()
                // A session that failed to open earlier is no longer failed, and the list has to
                // stop saying so.
                scope.launch { publish(record, SessionStatus.Active) }
            }
        }

        override fun onData(offset: Long, chunk: ByteArray) {
            // tryEmit rather than emit: this is a binder thread and must never block `:computer`.
            if (!record.chunks.tryEmit(offset to chunk)) {
                Log.w(TAG, "dropped a live chunk; the log replay will still carry it")
            }
        }

        override fun onDiagnostic(text: String) {
            Log.w(TAG, "harness: $text")
        }

        override fun onClosed(exitCode: Int, error: String?) {
            record.handle = null
            record.attached.set(false)
            record.connection.value = SessionConnection.Ended
            scope.launch {
                publish(record, if (error == null) SessionStatus.Finished else SessionStatus.Failed(error))
            }
        }
    }

    /**
     * Send a command, or hold it until there is something to send it to.
     *
     * The lock covers the delivery as well as the decision, which matters more than it looks:
     * without it a message written just as the session attaches can overtake one that has been
     * queued since before the boot, and the agent would read the user's turns out of order.
     * `IAgentSession.write` is `oneway`, so nothing waits on the guest while the lock is held.
     */
    private fun Record.write(command: Map<String, String>) {
        val json = command.entries.joinToString(",", "{", "}") { (key, value) ->
            "${JsonString(key)}:${JsonString(value)}"
        }
        synchronized(outbox) {
            val live = handle
            if (live == null || outbox.isNotEmpty()) {
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

    /** Everything written while the guest process was still starting, in the order it was written. */
    private fun Record.flushOutbox() {
        synchronized(outbox) {
            val live = handle ?: return
            val undelivered = mutableListOf<String>()
            for (json in outbox) {
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

    private fun String.toTitle(): String =
        trim().lineSequence().firstOrNull()?.take(60)?.ifBlank { null } ?: "New conversation"

    private companion object {
        const val TAG = "BoxAgentBackend"
        const val WORKSPACE = "/workspace"
        const val GUEST_HOME = "/home/agent"
        const val CREDENTIAL_PATH = "/home/agent/.box/credentials.json"
        const val BIND_TIMEOUT_MILLIS = 4_000L
        const val LOG_PATH_TIMEOUT_MILLIS = 10_000L

        val HARNESS_COMMAND = arrayOf(
            "/usr/bin/node",
            "/opt/local-agent/harness/box-claude-harness.mjs",
        )

        val CLAUDE_CODE = HarnessDescriptor(
            id = "claude-code",
            name = "Claude Code",
            command = "claude",
            mark = HarnessMarkKind.Burst,
        )
    }
}

/** Minimal JSON string escaping — the harness protocol is the only consumer. */
private fun JsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}
