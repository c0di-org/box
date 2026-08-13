package dev.localagent.workstation

import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.agent.SessionStatus
import dev.localagent.workstation.agent.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the home surface gives the window to, and who gets the keyboard.
 *
 * Both are one-line rules with a history of being got wrong in opposite directions: a full-window
 * wait that hid an app the user could have been using, then no wait at all — an empty list and a
 * 32dp ring in the corner for three minutes. These pin the middle.
 */
class HomeSurfaceTest {

    private fun task(id: String, status: SessionStatus = SessionStatus.Idle) = SessionSummary(
        id = id,
        harnessId = "claude-code",
        title = "Task $id",
        workingDirectory = "/workspace",
        status = status,
        updatedAt = 1L,
    )

    @Test
    fun `a closed box with nothing behind it is the whole window`() {
        assertTrue(BoxUiState().boxOwnsWindow)
    }

    @Test
    fun `a closed box never hides work either`() {
        // The returning user: `:computer` was reclaimed while they were away, so the box is off and
        // a week of tasks is sitting behind it. This used to be a full-window splash.
        val closed = BoxUiState(runtimeState = RuntimeState.Stopped, sessions = listOf(task("a")))
        assertEquals(BoxStage.Closed, closed.boxStage)
        assertFalse(closed.boxOwnsWindow)
    }

    @Test
    fun `a first opening with nothing under it keeps the window`() {
        val opening = BoxUiState(runtimeState = RuntimeState.Starting, openingSince = 1L)
        assertEquals(BoxStage.Working, opening.boxStage)
        assertTrue(opening.boxOwnsWindow)
    }

    @Test
    fun `reopening the box never hides work that is already there`() {
        // The case that made the old full-window wait unbearable: the machine restarts under a
        // list of tasks, and the list is what the user came back for.
        val opening = BoxUiState(
            runtimeState = RuntimeState.Starting,
            openingSince = 1L,
            sessions = listOf(task("a")),
        )
        assertFalse(opening.boxOwnsWindow)
    }

    @Test
    fun `the arrival takes the window exactly once`() {
        val greeting = BoxUiState(runtimeState = RuntimeState.Ready, readyGreeting = true)
        assertTrue(greeting.boxOwnsWindow)
        assertFalse(greeting.copy(readyGreeting = false).boxOwnsWindow)
    }

    @Test
    fun `a task swiped away leaves the list before it is closed`() {
        // The undo window: the row is gone, but nothing has been told to the agent yet.
        val closing = BoxUiState(
            runtimeState = RuntimeState.Ready,
            sessions = listOf(task("a"), task("b")),
            closingTaskId = "a",
        )
        assertEquals(listOf("b"), closing.tasks.map { it.id })
        assertEquals(listOf("a", "b"), closing.sessions.map { it.id })
        assertEquals(listOf("a", "b"), closing.copy(closingTaskId = null).tasks.map { it.id })
    }

    @Test
    fun `closing the last task gives the box the window back`() {
        val closed = BoxUiState(runtimeState = RuntimeState.Stopped, sessions = listOf(task("a")))
        assertFalse(closed.boxOwnsWindow)
        assertTrue(closed.copy(closingTaskId = "a").boxOwnsWindow)
    }

    @Test
    fun `an idle box is the user's to drive`() {
        val idle = BoxUiState(
            runtimeState = RuntimeState.Ready,
            sessions = listOf(task("a"), task("b", SessionStatus.Finished)),
        )
        assertFalse(idle.agentAtWork)
    }

    @Test
    fun `a box with an agent typing in it is not taken out from under it`() {
        val busy = BoxUiState(
            runtimeState = RuntimeState.Ready,
            sessions = listOf(task("a", SessionStatus.Finished), task("b", SessionStatus.Active)),
        )
        assertTrue(busy.agentAtWork)
    }
}
