package dev.localagent.workstation.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Box will hold for a conversation that has no process behind it.
 *
 * This is a performance rule with a correctness test, which is unusual enough to say why. The
 * outbox does two jobs: it queues what could not be delivered, and — via the runtime-ready
 * receiver — it is the list of sessions that were *waiting on the box* and must be given a harness
 * the moment the guest is up. Anything that lands in it therefore costs a `claude` process.
 *
 * A turn is worth one: someone typed it at a shut box. A standing setting is not, and does not
 * need one, because `onAttached` states the current mode, model and viewport to every harness
 * before anything else it reads.
 *
 * Getting this wrong does not fail anywhere. It just quietly starts a harness per conversation on
 * the next open — which is exactly what happened once the UI began broadcasting the viewport on
 * every launch: three `claude` processes in a two-core emulated guest, and 455 s to answer "hi".
 */
class StandingSettingTest {

    @Test
    fun `the settings broadcast to every session are not held for the ones that are idle`() {
        assertTrue(isStandingSetting(mapOf("type" to "permission_mode", "mode" to "ask")))
        assertTrue(isStandingSetting(mapOf("type" to "model", "model" to "opus")))
        assertTrue(
            isStandingSetting(
                mapOf(
                    "type" to "viewport",
                    "layout" to "tall",
                    "widthDp" to 400,
                    "hardwareKeyboard" to false,
                ),
            ),
        )
    }

    @Test
    fun `a turn is held, because someone typed it at a box that was shut`() {
        assertFalse(isStandingSetting(mapOf("type" to "prompt", "text" to "clone my project")))
    }

    @Test
    fun `so is anything else Box has to say to a session`() {
        // The default has to be "hold it". A command nobody classified is far likelier to be work
        // than a setting, and dropping work is worse than starting a process.
        assertFalse(isStandingSetting(mapOf("type" to "interrupt")))
        assertFalse(isStandingSetting(mapOf("type" to "permission_decision", "requestId" to "r1")))
        assertFalse(isStandingSetting(mapOf("type" to "stop_subagent", "subAgentId" to "a1")))
    }
}
