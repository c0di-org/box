package dev.localagent.workstation

import dev.localagent.workstation.agent.AgentActivity
import dev.localagent.workstation.agent.SessionConnection
import dev.localagent.workstation.agent.Transcript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Not claiming work that nothing is doing.
 *
 * Observed on hardware: Android SIGKILLed `:computer` two minutes into a turn, and the
 * conversation went on showing "Getting Claude Code ready" for another twenty-four — over a box
 * the same screen was reporting as shut. The transcript is folded from the session log, and a
 * harness killed with its process never gets to write a last line, so the log's final word stands
 * forever. Only the pipe's health knows better.
 */
class AgentStoppedTest {

    private fun state(activity: AgentActivity, connection: SessionConnection) = BoxUiState(
        transcript = Transcript(sessionId = "s-1", activity = activity),
        connection = connection,
    )

    @Test
    fun `a dead pipe stops the cold-start card claiming to be starting`() {
        val stopped = state(
            AgentActivity.Starting("Getting Claude Code ready"),
            SessionConnection.Disconnected("The computer stopped", retrying = true),
        )
        assertTrue(stopped.agentStopped)
    }

    @Test
    fun `retrying is not a reason to keep the spinner`() {
        // The sessions died with the process; only their logs survived. A runtime that comes back
        // does not bring this harness with it, so a pending retry says nothing about this turn.
        val retrying = state(
            AgentActivity.Working("Installing dependencies"),
            SessionConnection.Disconnected("The computer stopped", retrying = true),
        )
        assertTrue(retrying.agentStopped)
    }

    @Test
    fun `a live pipe is left alone`() {
        assertFalse(state(AgentActivity.Thinking(), SessionConnection.Live).agentStopped)
        assertFalse(
            state(AgentActivity.Working("Reading the source"), SessionConnection.Live).agentStopped,
        )
    }

    @Test
    fun `an idle or finished session is not a failure`() {
        // The ordinary end of every session: the pipe closes *because* the work finished. Calling
        // that stopped would put a red line under every completed task in the app.
        assertFalse(state(AgentActivity.Ended, SessionConnection.Ended).agentStopped)
        assertFalse(state(AgentActivity.Idle, SessionConnection.Ended).agentStopped)
    }

    @Test
    fun `waiting on the user is not stopped work`() {
        // The agent parked on a question has not stopped; the user has. That row is answered by
        // the question card, and colouring it as a failure would blame the machine for a wait the
        // person is holding.
        val asking = state(
            AgentActivity.AwaitingPermission("req-1"),
            SessionConnection.Disconnected("The computer stopped", retrying = true),
        )
        assertFalse(asking.agentStopped)
    }

    @Test
    fun `a session with no transcript claims nothing`() {
        assertFalse(BoxUiState(connection = SessionConnection.Live).agentStopped)
    }
}
