package dev.localagent.runtime.qemu

import dev.localagent.runtime.api.ExecEvent
import dev.localagent.runtime.api.ExecRequest
import dev.localagent.runtime.api.PtyRequest
import dev.localagent.runtime.api.SessionRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the protocol vocabulary on top of the multiplexer: how a call, a command and a PTY are
 * expressed as streams, and how cancellation and failure reach the caller.
 */
class AgentdClientTest {
    private val guest = FakeGuest()
    private val client = AgentdClient.over(guest.connection)

    @After
    fun tearDown() {
        guest.connection.close()
    }

    @Test
    fun `a call opens a stream and returns the streamed result body`() = runBlocking {
        guest.handshake()

        val result = async(Dispatchers.IO) { client.callJson("health") }
        val open = guest.read()
        guest.writeText(
            AgentdProtocol.DATA, open.streamId, AgentdProtocol.CHANNEL_STDOUT,
            """{"ready":true,"protocol":2}""",
        )
        guest.close(open.streamId, """{"status":"ok"}""")

        assertEquals(AgentdProtocol.OPEN, open.type)
        val request = JSONObject(open.text)
        assertEquals("call", request.getString("kind"))
        assertEquals("health", request.getString("method"))
        assertTrue(JSONObject(result.await()).getBoolean("ready"))
    }

    @Test
    fun `a result larger than one frame is reassembled in order`() = runBlocking {
        guest.handshake()
        val body = (0 until 3).joinToString("") { "chunk$it " }

        val result = async(Dispatchers.IO) { client.callJson("list_files") }
        val open = guest.read()
        body.chunked(4).forEach {
            guest.writeText(AgentdProtocol.DATA, open.streamId, AgentdProtocol.CHANNEL_STDOUT, it)
        }
        guest.close(open.streamId, """{"status":"ok"}""")

        assertEquals(body, result.await())
    }

    @Test
    fun `a guest error becomes an exception carrying its code and message`() = runBlocking {
        guest.handshake()

        val result = async(Dispatchers.IO) { runCatching { client.callJson("read_file") } }
        val open = guest.read()
        guest.close(
            open.streamId,
            """{"status":"error","error":{"code":"too_large","message":"file exceeds the limit"}}""",
        )

        val message = result.await().exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("too_large"))
        assertTrue(message, message.contains("file exceeds the limit"))
    }

    @Test
    fun `write_file streams its content on stdin and ends the channel`() = runBlocking {
        guest.handshake()
        val content = ByteArray(1024) { it.toByte() }

        val result = async(Dispatchers.IO) {
            client.call("write_file", JSONObject().put("path", "/workspace/x"), body = content)
        }
        val open = guest.read()
        val data = guest.read()
        val end = guest.read()
        guest.writeText(
            AgentdProtocol.DATA, open.streamId, AgentdProtocol.CHANNEL_STDOUT,
            """{"bytesWritten":1024}""",
        )
        guest.close(open.streamId, """{"status":"ok"}""")

        // No base64: the bytes go out as they are, which is why the 12 MiB v1 cap is gone.
        assertEquals(AgentdProtocol.CHANNEL_STDIN, data.channel)
        assertArrayEquals(content, data.payload)
        assertEquals(AgentdProtocol.END, end.type)
        assertEquals(AgentdProtocol.CHANNEL_STDIN, end.channel)
        assertEquals(1024, JSONObject(result.await().toString(Charsets.UTF_8)).getInt("bytesWritten"))
    }

    @Test
    fun `exec separates the streams and ends with the exit code`() = runBlocking {
        guest.handshake()

        val events = async(Dispatchers.IO) {
            client.exec(ExecRequest(listOf("cargo", "build"))).toList()
        }
        val open = guest.read()
        guest.writeText(AgentdProtocol.DATA, open.streamId, AgentdProtocol.CHANNEL_STDOUT, "compiling")
        guest.writeText(AgentdProtocol.DATA, open.streamId, AgentdProtocol.CHANNEL_STDERR, "warning")
        guest.close(open.streamId, """{"status":"ok","exitCode":3}""")

        val received = events.await()
        assertEquals("exec", JSONObject(open.text).getString("kind"))
        assertEquals("compiling", String((received[0] as ExecEvent.Stdout).bytes))
        assertEquals("warning", String((received[1] as ExecEvent.Stderr).bytes))
        assertEquals(ExecEvent.Exited(3), received[2])
    }

    @Test
    fun `exec output reaches the collector before the command finishes`() = runBlocking {
        guest.handshake()
        val firstEvent = CompletableDeferred<String>()

        val collector = launch(Dispatchers.IO) {
            client.exec(ExecRequest(listOf("sleep", "60"))).collect { event ->
                if (event is ExecEvent.Stdout) firstEvent.complete(String(event.bytes))
            }
        }
        val open = guest.read()
        guest.writeText(AgentdProtocol.DATA, open.streamId, AgentdProtocol.CHANNEL_STDOUT, "scrolling")

        // v1 could not have done this: it buffered the whole run before answering.
        assertEquals("scrolling", withTimeout(FakeGuest.TIMEOUT_MILLIS) { firstEvent.await() })
        collector.cancel()
    }

    @Test
    fun `cancelling the collector cancels the command in the guest`() = runBlocking {
        guest.handshake()

        val collector = launch(Dispatchers.IO) {
            client.exec(ExecRequest(listOf("sleep", "300"))).collect { }
        }
        val open = guest.read()
        collector.cancel()
        val cancel = guest.readUntil(AgentdProtocol.CANCEL)

        assertEquals(open.streamId, cancel.streamId)
        assertEquals(AgentdProtocol.CHANNEL_CONTROL, cancel.channel)
    }

    @Test
    fun `a pty carries keystrokes resizes and an exit code`() = runBlocking {
        guest.handshake()

        val session = client.openPty(PtyRequest(listOf("/bin/bash"), columns = 80, rows = 24))
        val open = guest.read()
        session.write("ls\n".toByteArray())
        val keystrokes = guest.read()
        session.resize(columns = 120, rows = 40)
        val resize = guest.read()
        val output = async(Dispatchers.IO) { session.output.toList() }
        guest.writeText(AgentdProtocol.DATA, open.streamId, AgentdProtocol.CHANNEL_STDOUT, "ls\r\n")
        guest.close(open.streamId, """{"status":"ok","exitCode":0}""")

        val request = JSONObject(open.text)
        assertEquals("pty", request.getString("kind"))
        assertEquals(80, request.getInt("columns"))
        assertEquals(AgentdProtocol.CHANNEL_STDIN, keystrokes.channel)
        assertEquals("ls\n", keystrokes.text)
        assertEquals(AgentdProtocol.CTRL, resize.type)
        assertEquals("""{"op":"resize","columns":120,"rows":40}""", resize.text)
        assertEquals(listOf("ls\r\n"), output.await().map { String(it) })
        assertEquals(0, session.awaitExit())
    }

    @Test
    fun `a pty exit code is available without collecting its output`() = runBlocking {
        guest.handshake()

        val session = client.openPty(PtyRequest(listOf("/bin/bash")))
        val open = guest.read()
        guest.close(open.streamId, """{"status":"ok","exitCode":137}""")

        // Terminal state lives on the stream, not in the output queue.
        assertEquals(137, withTimeout(FakeGuest.TIMEOUT_MILLIS) { session.awaitExit() })
    }

    @Test
    fun `closing a pty cancels it and survives a guest that answers`() = runBlocking {
        guest.handshake()
        val session = client.openPty(PtyRequest(listOf("/bin/bash")))
        val open = guest.read()

        val closing = launch(Dispatchers.IO) { session.close() }
        val cancel = guest.readUntil(AgentdProtocol.CANCEL)
        guest.close(open.streamId, """{"status":"cancelled","exitCode":143}""")
        closing.join()

        assertEquals("""{"signal":"TERM"}""", cancel.text)
    }

    @Test
    fun `concurrent operations share one connection and complete independently`() = runBlocking {
        guest.handshake()

        val slow = async(Dispatchers.IO) { client.exec(ExecRequest(listOf("build"))).toList() }
        val slowOpen = guest.read()
        val quick = async(Dispatchers.IO) { client.callJson("health") }
        val quickOpen = guest.read()

        guest.writeText(
            AgentdProtocol.DATA, quickOpen.streamId, AgentdProtocol.CHANNEL_STDOUT, """{"ready":true}""",
        )
        guest.close(quickOpen.streamId, """{"status":"ok"}""")
        val quickResult = quick.await()

        guest.close(slowOpen.streamId, """{"status":"ok","exitCode":0}""")

        // The file browser answering while a build runs is the reason v2 exists.
        assertEquals("""{"ready":true}""", quickResult)
        assertEquals(1L, slowOpen.streamId)
        assertEquals(3L, quickOpen.streamId)
        assertEquals(ExecEvent.Exited(0), slow.await().last())
    }

    @Test
    fun `a session opens an exec stream that keeps stdin open and asks for no deadline`() =
        runBlocking {
            guest.handshake()

            async(Dispatchers.IO) { client.openSession(SessionRequest(listOf("harness"))) }
            val open = guest.read()

            val request = JSONObject(open.text)
            assertEquals("exec", request.getString("kind"))
            assertTrue(request.getBoolean("stdin"))
            // Unbounded: a harness working through a real task has no honest wall-clock limit.
            assertEquals(0, request.getInt("timeoutSeconds"))
        }

    @Test
    fun `a session is answered while it is still running`() = runBlocking {
        guest.handshake()

        val session = async(Dispatchers.IO) {
            client.openSession(SessionRequest(listOf("harness")))
        }.await()
        val open = guest.read()
        val collected = mutableListOf<ExecEvent>()
        val reader = launch(Dispatchers.IO) { session.output.collect { collected += it } }

        // The harness asks…
        guest.writeText(
            AgentdProtocol.DATA, open.streamId, AgentdProtocol.CHANNEL_STDOUT,
            """{"type":"permission","requestId":"p1"}""" + "\n",
        )
        // …and the answer travels back down the same stream that is still open.
        session.write("""{"requestId":"p1","decision":"allow"}""".plus("\n").toByteArray())
        val answer = guest.readUntil(AgentdProtocol.DATA)

        guest.close(open.streamId, """{"status":"ok","exitCode":0}""")
        reader.join()

        assertEquals(AgentdProtocol.CHANNEL_STDIN, answer.channel)
        assertEquals("""{"requestId":"p1","decision":"allow"}""" + "\n", answer.text)
        assertEquals(ExecEvent.Exited(0), collected.last())
    }

    @Test
    fun `a session separates stderr from the events on stdout`() = runBlocking {
        guest.handshake()

        val session = async(Dispatchers.IO) {
            client.openSession(SessionRequest(listOf("harness")))
        }.await()
        val open = guest.read()
        val collected = async(Dispatchers.IO) { session.output.toList() }

        guest.writeText(
            AgentdProtocol.DATA, open.streamId, AgentdProtocol.CHANNEL_STDERR, "npm warn\n",
        )
        guest.writeText(
            AgentdProtocol.DATA, open.streamId, AgentdProtocol.CHANNEL_STDOUT, """{"type":"ok"}""",
        )
        guest.close(open.streamId, """{"status":"ok","exitCode":0}""")

        // A harness narrating structured events on stdout must never have them interleaved with
        // whatever npm decided to say — which is exactly what a PTY would have done.
        val events = collected.await()
        assertEquals("npm warn\n", (events[0] as ExecEvent.Stderr).bytes.decodeToString())
        assertEquals("""{"type":"ok"}""", (events[1] as ExecEvent.Stdout).bytes.decodeToString())
        assertEquals(ExecEvent.Exited(0), events[2])
    }

    @Test
    fun `a session reports its exit code to a caller that never collected output`() = runBlocking {
        guest.handshake()

        val session = async(Dispatchers.IO) {
            client.openSession(SessionRequest(listOf("harness")))
        }.await()
        val open = guest.read()
        val exit = async(Dispatchers.IO) { session.awaitExit() }

        guest.writeText(
            AgentdProtocol.DATA, open.streamId, AgentdProtocol.CHANNEL_STDOUT, "ignored",
        )
        guest.close(open.streamId, """{"status":"ok","exitCode":3}""")

        // Terminal state lives on the stream: the UI process may have died mid-session, and
        // whoever rebinds still needs to learn how it ended.
        assertEquals(3, exit.await())
    }
}
