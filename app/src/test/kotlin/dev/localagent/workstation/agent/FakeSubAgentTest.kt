package dev.localagent.workstation.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo path, which is also the development path: the whole sub-agent feature reachable with no
 * VM. Worth a test of its own because the fake is what the gallery photographs and what anyone
 * building the UI works against, so a script that quietly stops producing a nested card takes the
 * feature's only hands-on surface with it.
 *
 * No sleeps: each step suspends until the log says what it is waiting for, and fails on a timeout
 * rather than on a guess about how fast a scripted run is.
 */
class FakeSubAgentTest {

    @Test
    fun `the scripted delegate nests inside a card, and stopping it stops only that`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val backend = FakeAgentBackend(scope, pace = 0f)
            val session = backend.sessions.value.first { it.title == "Audit the public API" }.id

            val working = withTimeout(TIMEOUT) {
                backend.transcripts(session).first { transcript ->
                    transcript.subAgent()?.items?.any { it is TranscriptItem.Tool } == true
                }
            }
            val delegate = working.subAgent()!!
            assertTrue(delegate.running)
            assertEquals("Explore", delegate.task.agentType)
            // Its work is in its card, not spread through the transcript it was spawned from.
            assertTrue(delegate.items.any { it is TranscriptItem.Tool })
            assertTrue(working.items.none { it is TranscriptItem.Tool && it.callId == delegate.subAgentId })

            backend.interruptSubAgent(session, delegate.subAgentId)

            // The delegate stops; the session it belonged to does not, and says what it will do now.
            val after = withTimeout(TIMEOUT) {
                backend.transcripts(session).first { transcript ->
                    transcript.subAgent()?.stopped == true &&
                        transcript.items.filterIsInstance<TranscriptItem.Agent>()
                            .any { it.text.contains("Stopped the audit") }
                }
            }
            assertNull(after.outcome)
        } finally {
            scope.cancel()
        }
    }

    /**
     * The same script asks for two commands at once, which is the shape that broke on a phone: one
     * was approved and the other could no longer be answered by anything. Answering them out of
     * order here is the demo proving it can be done at all.
     */
    @Test
    fun `two requests wait together and are answered in any order`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val backend = FakeAgentBackend(scope, pace = 0f)
            val session = backend.sessions.value.first { it.title == "Audit the public API" }.id

            val both = withTimeout(TIMEOUT) {
                backend.transcripts(session).first { it.pendingPermissions.size == 2 }
            }.pendingPermissions
            assertTrue(both.all { it.ask is PermissionAsk.RunCommand })

            backend.resolvePermission(session, both[1].requestId, PermissionDecision.Deny)
            val one = withTimeout(TIMEOUT) {
                backend.transcripts(session).first { it.pendingPermissions.size == 1 }
            }
            // The survivor is the one nobody answered, and the run still says it is waiting on it.
            assertEquals(both[0].requestId, one.pendingPermissions.single().requestId)
            assertEquals(AgentActivity.AwaitingPermission(both[0].requestId), one.activity)

            backend.resolvePermission(session, both[0].requestId, PermissionDecision.Allow)
            val cleared = withTimeout(TIMEOUT) {
                backend.transcripts(session).first { it.pendingPermissions.isEmpty() }
            }
            assertTrue(cleared.pendingPermissions.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    /** Folded the same way `BoxViewModel` folds it, so this is watching what the UI would draw. */
    private fun FakeAgentBackend.transcripts(sessionId: String) =
        TranscriptBuilder(sessionId).let { builder ->
            events(sessionId).map { event ->
                builder.accept(event)
                builder.build()
            }
        }

    private fun Transcript.subAgent() = items.filterIsInstance<TranscriptItem.SubAgent>().firstOrNull()

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
