package dev.localagent.workstation.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Box does with a session, given what is running.
 *
 * The rule that earns a test is the closed one. A transcript is a log file that outlives the VM,
 * and Box used to treat "no computer" as "nothing to show" — so a task with a week of work behind
 * it opened onto "Nothing yet with Claude Code". Reading it back needs the `:computer` process and
 * not a booted guest, and the line between those two is this table.
 */
class AttachPlanTest {

    @Test
    fun `a closed box still reads a task that has already run`() {
        assertEquals(
            AttachPlan.ReadHistory,
            attachPlan(runtimeReady = false, opened = false, hasHistory = true),
        )
    }

    @Test
    fun `a closed box has nothing to fetch for a task that never ran`() {
        assertEquals(
            AttachPlan.Wait,
            attachPlan(runtimeReady = false, opened = false, hasHistory = false),
        )
    }

    @Test
    fun `an open box runs the agent rather than reading its history`() {
        // The case that would strand a message typed while the box was closed: the log has been
        // read, so something knows the path — and that must not be mistaken for a live session.
        assertEquals(
            AttachPlan.Open,
            attachPlan(runtimeReady = true, opened = false, hasHistory = true),
        )
    }

    @Test
    fun `a session already open in this guest is picked back up, not started twice`() {
        assertEquals(
            AttachPlan.Reattach,
            attachPlan(runtimeReady = true, opened = true, hasHistory = true),
        )
    }
}
