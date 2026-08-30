package dev.localagent.workstation.ui

import dev.localagent.workstation.agent.TaskItem
import dev.localagent.workstation.agent.TaskState
import dev.localagent.workstation.agent.ToolCall
import dev.localagent.workstation.agent.ToolOutcome
import dev.localagent.workstation.agent.TranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What decides whether the transcript keeps up with the agent.
 *
 * The follow effect re-runs when its keys change, and every other key it has — the last card's
 * identity, the number of cards, the activity — stands still while a card that is already on
 * screen fills in. That is precisely the stretch with the most to watch: a tool writing output, a
 * sub-agent working through its list. So this token is the only thing keeping the view attached to
 * the end during it, and a change here that quietly stops it moving would be invisible until
 * someone noticed they were scrolling by hand again.
 */
class TranscriptFollowTest {

    @Test
    fun `a tool writing more output moves the token`() {
        val before = tool(output = "compiling")
        val after = tool(output = "compiling\nlinking")

        assertNotEquals(before.growth(), after.growth())
    }

    @Test
    fun `a tool finishing moves the token`() {
        val running = tool(output = "done")
        val finished = tool(output = "done", outcome = ToolOutcome.Success())

        assertNotEquals(running.growth(), finished.growth())
    }

    @Test
    fun `streamed prose moves the token`() {
        assertNotEquals(agent("Here's wh").growth(), agent("Here's what I found").growth())
    }

    @Test
    fun `a sub-agent taking another step moves the token`() {
        val one = subAgent(agent("looking"))
        val two = subAgent(agent("looking"), tool(output = "grep"))

        assertNotEquals(one.growth(), two.growth())
    }

    /** The nested case is the one the report named: the card grows without gaining a child. */
    @Test
    fun `a sub-agent's newest card filling in moves the token`() {
        val before = subAgent(agent("done"), tool(output = "one line"))
        val after = subAgent(agent("done"), tool(output = "one line\ntwo lines"))

        assertNotEquals(before.growth(), after.growth())
    }

    @Test
    fun `a checklist ticking moves the token`() {
        val before = checklist(TaskState.Running, TaskState.Pending)
        val after = checklist(TaskState.Done, TaskState.Running)

        assertNotEquals(before.growth(), after.growth())
    }

    /** Recomputed on every recomposition, so an unchanged card must not look like a changed one. */
    @Test
    fun `an unchanged card holds its token`() {
        assertEquals(tool(output = "steady").growth(), tool(output = "steady").growth())
        assertEquals(subAgent(agent("steady")).growth(), subAgent(agent("steady")).growth())
    }

    private fun tool(output: String, outcome: ToolOutcome? = null) = TranscriptItem.Tool(
        key = "t", at = AT, callId = "c1", call = ToolCall.Shell("make"),
        output = output, outcome = outcome,
    )

    private fun agent(text: String) =
        TranscriptItem.Agent(key = "m", at = AT, text = text, streaming = true)

    private fun subAgent(vararg items: TranscriptItem) = TranscriptItem.SubAgent(
        key = "s", at = AT, subAgentId = "a1", task = ToolCall.Task("Explore"),
        items = items.toList(), outcome = null,
    )

    private fun checklist(vararg states: TaskState) = TranscriptItem.Checklist(
        key = "p", at = AT, items = states.map { TaskItem("step", it) },
    )

    private companion object {
        const val AT = 1_700_000_000_000L
    }
}
