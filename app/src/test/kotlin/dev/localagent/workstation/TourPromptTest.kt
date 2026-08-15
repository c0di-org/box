package dev.localagent.workstation

import dev.localagent.workstation.agent.GuestAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue contract behind the tour chip.
 *
 * `BoxViewModel.startTour` is [TOUR_PROMPT] through `sendMessage` and nothing else, so tapping it
 * inside a task adds to that task. Tapped from the opening screen or the arrival there is no
 * session yet, and the prompt is queued with a **null** id — the shape `flushHeldPrompts` answers
 * by *starting* a conversation. That is the case pinned here, because it is the one where the chip
 * is most likely to be used and the two properties deciding where such a prompt is drawn are
 * exactly the sort a later tidy-up would "fix" into filtering by session.
 */
class TourPromptTest {

    private fun held() = BoxUiState(
        signIn = GuestAuth.State.Unknown,
        queued = listOf(QueuedPrompt(sessionId = null, text = TOUR_PROMPT, heldForSignIn = true)),
    )

    @Test
    fun `a tour held for sign-in is shown on the screen holding it`() {
        // The hero draws `heldForSignIn` — if this filtered by session, a prompt belonging to no
        // session yet would vanish the moment it was queued and the chip would read as dead.
        assertEquals(listOf(TOUR_PROMPT), held().heldForSignIn.map { it.text })
    }

    @Test
    fun `it is not also drawn in the conversation underneath`() {
        // Held prompts are the hero's to show. Appearing in both places would be one message drawn
        // twice, and the conversation's copy would be the one nobody can act on.
        assertTrue(held().queuedForSelected.isEmpty())
    }

    @Test
    fun `once the sign-in lands it becomes an ordinary queued message`() {
        // What `flushHeldPrompts` does first: clear the flag, so the same prompt now reads as
        // in-flight rather than waiting, and the harness's echo is what finally clears it.
        val flushed = held().let { state ->
            state.copy(queued = state.queued.map { it.copy(heldForSignIn = false) })
        }
        assertTrue("no longer waiting on a person", flushed.heldForSignIn.isEmpty())
        assertEquals(
            "and now visible in the conversation it is about to open",
            listOf(TOUR_PROMPT),
            flushed.queuedForSelected.map { it.text },
        )
    }

    @Test
    fun `the prompt is the words the chip shows`() {
        // The chip draws TOUR_PROMPT rather than a label describing it, and the guest's itinerary
        // is keyed on the same sentence. Two spellings would break the join silently.
        assertEquals("Show me what’s inside the box", TOUR_PROMPT)
    }
}
