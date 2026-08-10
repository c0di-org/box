package dev.localagent.runtime.qemu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** What the guest sent on one of a stream's channels. */
internal sealed interface AgentdEvent {
    class Data(val channel: Int, val bytes: ByteArray) : AgentdEvent
    class Control(val payload: ByteArray) : AgentdEvent
    class ChannelEnded(val channel: Int) : AgentdEvent
}

/**
 * One multiplexed connection to `agentd`, owning the transport and every live stream.
 *
 * Takes plain streams rather than a `LocalSocket` so the multiplexer can be driven by a fake guest
 * in a JVM unit test. It never parses JSON: control payloads are handed up as bytes, which keeps
 * this layer testable and keeps protocol vocabulary in one place ([AgentdClient]).
 */
internal class AgentdConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeTransport: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outbox = Channel<ByteArray>(OUTBOX_FRAMES)
    private val streams = ConcurrentHashMap<Long, AgentdStream>()
    private val nextStreamId = AtomicLong(1)
    private val peerHello = CompletableDeferred<ByteArray>()
    private val failure = AtomicReference<Throwable?>(null)
    private val outstandingPings = AtomicInteger()

    @Volatile private var lastInboundNanos = System.nanoTime()
    @Volatile private var peerWindowBytes = AgentdProtocol.INITIAL_WINDOW_BYTES
    @Volatile private var peerMaxStreams = AgentdProtocol.MAX_CONCURRENT_STREAMS
    @Volatile private var peerFramePayloadBytes = AgentdProtocol.MAX_FRAME_PAYLOAD

    val isActive: Boolean get() = failure.get() == null

    suspend fun start(helloPayload: ByteArray) {
        // Queued before the loops start, so HELLO is unconditionally the first frame out.
        writeFrame(AgentdProtocol.HELLO, 0, AgentdProtocol.CHANNEL_CONTROL, helloPayload)
        scope.launch { writeLoop() }
        scope.launch { readLoop() }
        scope.launch { keepAliveLoop() }
    }

    suspend fun awaitPeerHello(timeoutMillis: Long): ByteArray =
        withTimeout(timeoutMillis) { peerHello.await() }

    /** Applied from the peer's HELLO before any stream is opened, so limits are the lower pair. */
    fun applyPeerLimits(windowBytes: Int, maxStreams: Int, framePayloadBytes: Int) {
        peerWindowBytes = windowBytes.coerceIn(1, AgentdProtocol.INITIAL_WINDOW_BYTES)
        peerMaxStreams = maxStreams.coerceIn(1, AgentdProtocol.MAX_CONCURRENT_STREAMS)
        peerFramePayloadBytes = framePayloadBytes.coerceIn(1, AgentdProtocol.MAX_FRAME_PAYLOAD)
    }

    suspend fun openStream(openPayload: ByteArray): AgentdStream {
        failure.get()?.let { throw IOException("agentd connection is closed", it) }
        check(streams.size < peerMaxStreams) { "Too many concurrent guest streams" }
        // Host streams are odd; even ids stay reserved for guest-initiated streams.
        val id = nextStreamId.getAndAdd(2)
        if (id > MAX_STREAM_ID) {
            val exhausted = IOException("agentd stream ids exhausted; reconnecting")
            fail(exhausted)
            throw exhausted
        }
        val stream = AgentdStream(
            id,
            this,
            initialSendCredit = peerWindowBytes,
            receiveWindowBytes = AgentdProtocol.INITIAL_WINDOW_BYTES,
            maxFramePayloadBytes = peerFramePayloadBytes,
        )
        streams[id] = stream
        return try {
            writeFrame(AgentdProtocol.OPEN, id, AgentdProtocol.CHANNEL_CONTROL, openPayload)
            stream
        } catch (error: Throwable) {
            streams.remove(id)
            throw error
        }
    }

    suspend fun writeFrame(type: Int, streamId: Long, channel: Int, payload: ByteArray) {
        failure.get()?.let { throw IOException("agentd connection is closed", it) }
        try {
            outbox.send(AgentdProtocol.encode(type, streamId, channel, payload))
        } catch (closed: Exception) {
            throw failure.get()?.let { IOException("agentd connection is closed", it) } ?: closed
        }
    }

    fun close() = fail(IOException("agentd connection was closed by the app"))

    private fun fail(cause: Throwable) {
        if (!failure.compareAndSet(null, cause)) return
        outbox.close()
        peerHello.completeExceptionally(cause)
        streams.values.forEach { it.failLocally(cause) }
        streams.clear()
        runCatching { closeTransport() }
        scope.cancel()
    }

    // -- transport loops

    private suspend fun writeLoop() {
        try {
            for (frame in outbox) {
                output.write(frame)
                output.flush()
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private suspend fun readLoop() {
        val header = ByteArray(AgentdProtocol.HEADER_BYTES)
        try {
            while (true) {
                readFully(header, AgentdProtocol.HEADER_BYTES)
                val frame = AgentdProtocol.decodeHeader(header)
                val payload = if (frame.payloadLength > 0) {
                    ByteArray(frame.payloadLength).also { readFully(it, frame.payloadLength) }
                } else {
                    EMPTY
                }
                lastInboundNanos = System.nanoTime()
                outstandingPings.set(0)
                dispatch(frame, payload)
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    /**
     * A read that hits the socket's poll deadline is not an error: LocalSocket reports it as a bare
     * IOException carrying EAGAIN text, which [isSocketReadTimeout] recognises. Polling is what lets
     * cancellation win over a blocking read.
     */
    private suspend fun readFully(buffer: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            currentCoroutineContext().ensureActive()
            val read = try {
                input.read(buffer, offset, count - offset)
            } catch (error: IOException) {
                if (error.isSocketReadTimeout()) continue else throw error
            }
            if (read < 0) throw EOFException("agentd closed its control channel")
            offset += read
        }
    }

    private suspend fun dispatch(frame: AgentdHeader, payload: ByteArray) {
        when (frame.type) {
            AgentdProtocol.HELLO -> peerHello.complete(payload)
            AgentdProtocol.PING ->
                writeFrame(AgentdProtocol.PONG, 0, AgentdProtocol.CHANNEL_CONTROL, payload)
            AgentdProtocol.PONG -> Unit
            AgentdProtocol.GOAWAY -> throw AgentdFramingException(
                "the guest agent ended the connection: ${payload.toString(Charsets.UTF_8)}",
            )
            AgentdProtocol.OPEN -> throw AgentdFramingException("OPEN is sent by the host only")
            else -> dispatchToStream(frame, payload)
        }
    }

    private suspend fun dispatchToStream(frame: AgentdHeader, payload: ByteArray) {
        // A frame for a retired id races legitimately with the CLOSE that retired it.
        val stream = streams[frame.streamId] ?: return
        when (frame.type) {
            AgentdProtocol.DATA -> stream.onData(frame.channel, payload)
            AgentdProtocol.END -> stream.offer(AgentdEvent.ChannelEnded(frame.channel))
            AgentdProtocol.CTRL -> stream.offer(AgentdEvent.Control(payload))
            AgentdProtocol.WINDOW -> stream.grantCredit(AgentdProtocol.decodeWindow(payload))
            AgentdProtocol.CLOSE -> {
                streams.remove(frame.streamId)
                stream.onClosed(payload)
            }
            else -> throw AgentdFramingException("unexpected agentd frame type ${frame.type}")
        }
    }

    private suspend fun keepAliveLoop() {
        while (true) {
            delay(KEEPALIVE_INTERVAL_MILLIS)
            val idleMillis = (System.nanoTime() - lastInboundNanos) / 1_000_000
            if (streams.isEmpty() || idleMillis < KEEPALIVE_INTERVAL_MILLIS) continue
            if (outstandingPings.incrementAndGet() > MAX_MISSED_PINGS) {
                fail(IOException("the guest agent stopped answering keepalives"))
                return
            }
            runCatching {
                writeFrame(AgentdProtocol.PING, 0, AgentdProtocol.CHANNEL_CONTROL, PING_PAYLOAD)
            }
        }
    }

    companion object {
        val EMPTY = ByteArray(0)
        private val PING_PAYLOAD = ByteArray(8) { it.toByte() }
        private const val OUTBOX_FRAMES = 32
        private const val MAX_STREAM_ID = 0xFFFF_FFFFL
        private const val KEEPALIVE_INTERVAL_MILLIS = 20_000L
        private const val MAX_MISSED_PINGS = 3
    }
}

/**
 * One logical stream. Terminal state lives here rather than in the event queue, so a caller can
 * await a command's exit code without having to consume its output first.
 */
internal class AgentdStream(
    val id: Long,
    private val connection: AgentdConnection,
    initialSendCredit: Int,
    private val receiveWindowBytes: Int,
    private val maxFramePayloadBytes: Int = AgentdProtocol.MAX_FRAME_PAYLOAD,
) {
    private val events = Channel<AgentdEvent>(Channel.UNLIMITED)
    private val sendCredit = AtomicInteger(initialSendCredit)
    private val creditSignal = Channel<Unit>(Channel.CONFLATED)
    private val unacknowledged = AtomicInteger()
    private val cancelled = AtomicBoolean()
    private val terminated = AtomicBoolean()

    /** The raw CLOSE payload, or the failure that ended the connection. */
    val closePayload = CompletableDeferred<ByteArray>()

    /**
     * Bounded in practice by flow control: more bytes can only arrive once [acknowledge] has
     * returned credit, and credit is only returned once a consumer has taken them.
     */
    val incoming: ReceiveChannel<AgentdEvent> get() = events

    suspend fun send(channel: Int, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val allowed = awaitCredit(minOf(maxFramePayloadBytes, bytes.size - offset))
            connection.writeFrame(
                AgentdProtocol.DATA,
                id,
                channel,
                bytes.copyOfRange(offset, offset + allowed),
            )
            offset += allowed
        }
    }

    suspend fun endChannel(channel: Int) =
        connection.writeFrame(AgentdProtocol.END, id, channel, AgentdConnection.EMPTY)

    suspend fun sendControl(payload: ByteArray) =
        connection.writeFrame(AgentdProtocol.CTRL, id, AgentdProtocol.CHANNEL_CONTROL, payload)

    /** Returns credit for bytes a consumer has taken, which is what unblocks the guest. */
    suspend fun acknowledge(bytes: Int) {
        if (bytes <= 0) return
        val pending = unacknowledged.addAndGet(bytes)
        if (pending >= receiveWindowBytes / 2 && unacknowledged.compareAndSet(pending, 0)) {
            connection.writeFrame(
                AgentdProtocol.WINDOW,
                id,
                AgentdProtocol.CHANNEL_CONTROL,
                AgentdProtocol.encodeWindow(pending),
            )
        }
    }

    /** Idempotent. The guest still answers with CLOSE, which is what retires the id. */
    suspend fun cancel(payload: ByteArray) {
        if (terminated.get() || !cancelled.compareAndSet(false, true)) return
        runCatching {
            connection.writeFrame(AgentdProtocol.CANCEL, id, AgentdProtocol.CHANNEL_CONTROL, payload)
        }
    }

    internal suspend fun onData(channel: Int, payload: ByteArray) {
        if (cancelled.get()) {
            // Discard, but keep granting: a guest blocked on a window it will never be given
            // could not run its own teardown, and would never send the CLOSE we are waiting for.
            acknowledge(payload.size)
            return
        }
        events.trySend(AgentdEvent.Data(channel, payload))
    }

    internal fun offer(event: AgentdEvent) {
        events.trySend(event)
    }

    internal fun grantCredit(credit: Long) {
        if (credit <= 0) return
        sendCredit.addAndGet(credit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        creditSignal.trySend(Unit)
    }

    internal fun onClosed(payload: ByteArray) {
        terminated.set(true)
        closePayload.complete(payload)
        events.close()
        creditSignal.close()
    }

    internal fun failLocally(cause: Throwable) {
        terminated.set(true)
        closePayload.completeExceptionally(cause)
        events.close(cause)
        creditSignal.close(cause)
    }

    private suspend fun awaitCredit(want: Int): Int {
        while (true) {
            val available = sendCredit.get()
            if (available > 0) {
                val take = minOf(want, available)
                if (sendCredit.compareAndSet(available, available - take)) return take
            } else {
                // CONFLATED, so a grant that lands before this receive is not lost.
                creditSignal.receive()
            }
        }
    }
}
