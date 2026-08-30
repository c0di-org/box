package dev.localagent.workstation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gauge, and the cases where it must refuse to be one.
 *
 * A context ring is read at a glance and believed, so the failure that matters here is not a wrong
 * pixel — it is drawing something confident out of numbers that do not support it.
 */
class ContextUsageTest {

    @Test
    fun `a context event reaches the transcript`() {
        val transcript = listOf(
            AgentEvent.ContextChanged("e1", SESSION, AT, usedTokens = 40_000, contextWindow = 200_000),
        ).toTranscript(SESSION)

        assertEquals(20, transcript.context?.percent)
    }

    @Test
    fun `the newest reading replaces the last`() {
        val transcript = listOf(
            AgentEvent.ContextChanged("e1", SESSION, AT, 40_000, 200_000),
            AgentEvent.ContextChanged("e2", SESSION, AT, 120_000, 200_000),
        ).toTranscript(SESSION)

        assertEquals(60, transcript.context?.percent)
    }

    /** Most harnesses say nothing about context, and the ring simply does not appear. */
    @Test
    fun `a transcript without the event has no gauge`() {
        assertNull(
            listOf(AgentEvent.UserMessage("e1", SESSION, AT, "hello")).toTranscript(SESSION).context,
        )
    }

    /** Changing model mid-session can leave a conversation larger than its new window. */
    @Test
    fun `an overfull context clamps rather than overdrawing`() {
        val usage = ContextUsage(usedTokens = 250_000, contextWindow = 200_000)

        assertEquals(1f, usage.fraction, 0f)
        assertEquals(100, usage.percent)
    }

    // ---- what the wire refuses ------------------------------------------

    /** A window of zero is a harness that does not know one, not a context that is full. */
    @Test
    fun `a zero window is dropped rather than drawn as full`() {
        assertNull(parse("""{"type":"context","usedTokens":1000,"contextWindow":0}"""))
    }

    @Test
    fun `a missing window is dropped`() {
        assertNull(parse("""{"type":"context","usedTokens":1000}"""))
    }

    @Test
    fun `a well formed reading is kept`() {
        val event = parse("""{"type":"context","usedTokens":1000,"contextWindow":200000}""")

        assertEquals(1000L, (event as AgentEvent.ContextChanged).usedTokens)
        assertEquals(200_000L, event.contextWindow)
    }

    private fun parse(line: String) = HarnessWire.parse(
        line,
        HarnessWire.Context(
            sessionId = SESSION,
            harnessId = "claude-code",
            title = "A task",
            workingDirectory = "/workspace",
        ),
        ordinal = 1,
    )

    private companion object {
        const val SESSION = "s-test"
        const val AT = 1_700_000_000_000L
    }
}
