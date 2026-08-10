package dev.localagent.runtime.qemu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real multiplexer against a hand-written guest, covering the behaviour that decides
 * whether the channel survives a long build: ordering, credit accounting, cancellation, teardown.
 */
class AgentdConnectionTest {
    private val guest = FakeGuest()
    private val connection = guest.connection

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `sends hello before anything else and surfaces the guest reply`() = runBlocking {
        connection.start(AgentdClient.clientHello())

        val first = guest.read()
        guest.writeText(AgentdProtocol.HELLO, 0, AgentdProtocol.CHANNEL_CONTROL, FakeGuest.GUEST_HELLO)

        assertEquals(AgentdProtocol.HELLO, first.type)
        assertEquals(0L, first.streamId)
        assertEquals(
            FakeGuest.GUEST_HELLO,
            connection.awaitPeerHello(FakeGuest.TIMEOUT_MILLIS).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `opens host streams with odd ids and carries the open payload`() = runBlocking {
        guest.handshake()

        val first = connection.openStream("""{"kind":"call"}""".toByteArray())
        val firstFrame = guest.read()
        val second = connection.openStream("""{"kind":"exec"}""".toByteArray())
        val secondFrame = guest.read()

        // Even ids stay reserved for streams the guest may one day open itself.
        assertEquals(1L, first.id)
        assertEquals(3L, second.id)
        assertEquals(AgentdProtocol.OPEN, firstFrame.type)
        assertEquals("""{"kind":"call"}""", firstFrame.text)
        assertEquals(AgentdProtocol.OPEN, secondFrame.type)
    }

    @Test
    fun `delivers guest data in order and completes on close`() = runBlocking {
        val stream = openStream()

        guest.writeText(AgentdProtocol.DATA, stream.id, AgentdProtocol.CHANNEL_STDOUT, "one")
        guest.writeText(AgentdProtocol.DATA, stream.id, AgentdProtocol.CHANNEL_STDERR, "two")
        guest.close(stream.id, """{"status":"ok","exitCode":0}""")

        val received = mutableListOf<Pair<Int, String>>()
        for (event in stream.incoming) {
            if (event is AgentdEvent.Data) received += event.channel to String(event.bytes)
        }

        assertEquals(
            listOf(AgentdProtocol.CHANNEL_STDOUT to "one", AgentdProtocol.CHANNEL_STDERR to "two"),
            received,
        )
        assertEquals(
            """{"status":"ok","exitCode":0}""",
            withTimeout(FakeGuest.TIMEOUT_MILLIS) { stream.closePayload.await() }
                .toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `returns credit only once a consumer has taken the bytes`() = runBlocking {
        val stream = openStream()
        val half = AgentdProtocol.INITIAL_WINDOW_BYTES / 2

        guest.write(AgentdProtocol.DATA, stream.id, AgentdProtocol.CHANNEL_STDOUT, ByteArray(half))
        val beforeConsuming = guest.readOrNull(FakeGuest.SILENCE_MILLIS)
        val received = stream.incoming.receive() as AgentdEvent.Data
        stream.acknowledge(received.bytes.size)
        val afterConsuming = guest.read()

        // Arrival alone must not replenish the window, or the queue could grow without limit.
        assertNull(beforeConsuming)
        assertEquals(AgentdProtocol.WINDOW, afterConsuming.type)
        assertEquals(stream.id, afterConsuming.streamId)
        assertEquals(half.toLong(), AgentdProtocol.decodeWindow(afterConsuming.payload))
    }

    @Test
    fun `does not spend a window frame on every small chunk`() = runBlocking {
        val stream = openStream()

        repeat(4) {
            guest.write(AgentdProtocol.DATA, stream.id, AgentdProtocol.CHANNEL_STDOUT, ByteArray(16))
            stream.acknowledge((stream.incoming.receive() as AgentdEvent.Data).bytes.size)
        }

        assertNull(guest.readOrNull(FakeGuest.SILENCE_MILLIS))
    }

    @Test
    fun `stops sending at the window and resumes when credit arrives`() = runBlocking {
        val stream = openStream()
        val window = AgentdProtocol.INITIAL_WINDOW_BYTES
        val payload = ByteArray(window + AgentdProtocol.MAX_FRAME_PAYLOAD)

        val sender = launch(Dispatchers.IO) { stream.send(AgentdProtocol.CHANNEL_STDIN, payload) }
        var sent = 0
        while (sent < window) sent += guest.read().payload.size
        val whileStalled = guest.readOrNull(FakeGuest.SILENCE_MILLIS)

        stream.grantCredit(AgentdProtocol.MAX_FRAME_PAYLOAD.toLong())
        val afterCredit = guest.read()
        sender.join()

        assertEquals(window, sent)
        assertNull(whileStalled)
        assertEquals(AgentdProtocol.MAX_FRAME_PAYLOAD, afterCredit.payload.size)
    }

    @Test
    fun `splits a large write into frames the guest will accept`() = runBlocking {
        val stream = openStream()

        launch(Dispatchers.IO) {
            stream.send(AgentdProtocol.CHANNEL_STDIN, ByteArray(AgentdProtocol.MAX_FRAME_PAYLOAD + 10))
        }
        val first = guest.read()
        val second = guest.read()

        assertEquals(AgentdProtocol.MAX_FRAME_PAYLOAD, first.payload.size)
        assertEquals(10, second.payload.size)
        assertEquals(AgentdProtocol.CHANNEL_STDIN, first.channel)
    }

    @Test
    fun `honours a peer that advertises smaller limits than our own`() = runBlocking {
        guest.handshake()
        connection.applyPeerLimits(windowBytes = 4096, maxStreams = 1, framePayloadBytes = 1024)
        val stream = connection.openStream(OPEN_CALL)
        guest.read()

        val sender = launch(Dispatchers.IO) { stream.send(AgentdProtocol.CHANNEL_STDIN, ByteArray(8192)) }
        var sent = 0
        val sizes = mutableListOf<Int>()
        while (sent < 4096) {
            val frame = guest.read()
            sizes += frame.payload.size
            sent += frame.payload.size
        }
        val secondStream = runCatching { connection.openStream(OPEN_CALL) }
        // It is stalled on credit that will never come, which is the point; let it go.
        sender.cancel()

        assertEquals(List(4) { 1024 }, sizes)
        assertNull(guest.readOrNull(FakeGuest.SILENCE_MILLIS))
        assertTrue(secondStream.isFailure)
    }

    @Test
    fun `a cancelled stream discards output but keeps granting credit`() = runBlocking {
        val stream = openStream()

        stream.cancel(AgentdClient.EMPTY_JSON)
        val cancel = guest.read()
        guest.write(
            AgentdProtocol.DATA, stream.id, AgentdProtocol.CHANNEL_STDOUT,
            ByteArray(AgentdProtocol.INITIAL_WINDOW_BYTES / 2),
        )
        val window = guest.read()

        assertEquals(AgentdProtocol.CANCEL, cancel.type)
        // A guest blocked on credit it will never receive could never send its own CLOSE.
        assertEquals(AgentdProtocol.WINDOW, window.type)
        assertNull(withTimeoutOrNull(FakeGuest.SILENCE_MILLIS) { stream.incoming.receive() })
    }

    @Test
    fun `answers a keepalive ping from the guest`() = runBlocking {
        guest.handshake()

        guest.write(AgentdProtocol.PING, 0, AgentdProtocol.CHANNEL_CONTROL, ByteArray(8) { 7 })
        val pong = guest.read()

        assertEquals(AgentdProtocol.PONG, pong.type)
        assertArrayEquals(ByteArray(8) { 7 }, pong.payload)
    }

    @Test
    fun `ignores frames for a stream that has already been retired`() = runBlocking {
        val stream = openStream()
        guest.close(stream.id, """{"status":"ok"}""")
        withTimeout(FakeGuest.TIMEOUT_MILLIS) { stream.closePayload.await() }

        // A straggling frame races the CLOSE that retired the id; it must not be fatal.
        guest.writeText(AgentdProtocol.DATA, stream.id, AgentdProtocol.CHANNEL_STDOUT, "late")
        guest.write(AgentdProtocol.PING, 0, AgentdProtocol.CHANNEL_CONTROL, ByteArray(8))

        assertEquals(AgentdProtocol.PONG, guest.read().type)
        assertTrue(connection.isActive)
    }

    @Test
    fun `a framing violation fails every open stream and closes the transport`() = runBlocking {
        guest.handshake()
        val first = connection.openStream(OPEN_CALL)
        guest.read()
        val second = connection.openStream(OPEN_CALL)
        guest.read()

        guest.corruptTheStream()

        listOf(first, second).forEach { stream ->
            val failure = runCatching {
                withTimeout(FakeGuest.TIMEOUT_MILLIS) { stream.closePayload.await() }
            }
            assertTrue("stream ${stream.id} should have failed", failure.isFailure)
        }
        assertNotNull(withTimeoutOrNull(FakeGuest.TIMEOUT_MILLIS) { guest.transportClosed.await() })
        assertTrue(!connection.isActive)
    }

    @Test
    fun `a closed connection refuses new streams instead of hanging`() = runBlocking {
        guest.handshake()
        connection.close()

        val failure = runCatching { connection.openStream(OPEN_CALL) }

        assertTrue(failure.exceptionOrNull() is java.io.IOException)
    }

    private suspend fun openStream(): AgentdStream {
        guest.handshake()
        return connection.openStream(OPEN_CALL).also { guest.read() }
    }

    private companion object {
        val OPEN_CALL = """{"kind":"call","method":"health"}""".toByteArray()
    }
}
