package dev.localagent.workstation

import dev.localagent.workstation.agent.GuestAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When Box believes a sign-in is still ahead of the user, and what it does with their words in the
 * meantime.
 *
 * Both rules exist because of one hard constraint: Claude's handshake runs *inside* the guest, so
 * the only authority on whether this box has a credential cannot be asked until three minutes after
 * the moment the answer is needed. The first message anyone types is typed into that gap.
 */
class SignInGateTest {

    private fun state(
        signIn: GuestAuth.State = GuestAuth.State.Unknown,
        signedInBefore: Boolean = false,
    ) = BoxUiState(signIn = signIn, signedInBefore = signedInBefore)

    @Test
    fun `a fresh install expects a sign-in before the guest can be asked`() {
        // Nothing on this phone has ever signed in and the box has not booted. That is enough to
        // say so on the closed hero, which is the whole point of saying it before the wait.
        assertTrue(state().signInWanted)
    }

    @Test
    fun `a phone that has signed in before waits quietly for the answer`() {
        assertFalse(state(signedInBefore = true).signInWanted)
        assertFalse(state(GuestAuth.State.Checking, signedInBefore = true).signInWanted)
    }

    @Test
    fun `the guest overrules the hint in both directions`() {
        // A credential that expired, on a phone that has signed in before.
        assertTrue(state(GuestAuth.State.SignedOut, signedInBefore = true).signInWanted)
        // A workspace that already held one, on an install with no memory of it.
        assertFalse(state(GuestAuth.State.SignedIn("a@b.c")).signInWanted)
    }

    @Test
    fun `a sign-in part way through has not happened yet`() {
        assertTrue(state(GuestAuth.State.AwaitingCode("https://example.test", "")).signInWanted)
        assertTrue(state(GuestAuth.State.Starting).signInWanted)
        assertTrue(state(GuestAuth.State.Failed("no")).signInWanted)
    }

    @Test
    fun `only a signed-in box stops wanting one`() {
        // The banner's narrower question is unchanged: it fires on an answer, never on the hint.
        assertFalse(state().needsSignIn)
        assertTrue(state(GuestAuth.State.SignedOut).needsSignIn)
    }

    @Test
    fun `held messages are told apart from queued ones`() {
        // Queued means "already with the backend, waiting on the guest". Held means "deliberately
        // not handed over" — and only the held ones are the sign-in's to send.
        val waiting = BoxUiState(
            queued = listOf(
                QueuedPrompt(sessionId = "s-1", text = "booting"),
                QueuedPrompt(sessionId = null, text = "signed out", heldForSignIn = true),
            ),
        )
        assertEquals(listOf("signed out"), waiting.heldForSignIn.map { it.text })
    }

    @Test
    fun `a held message typed into the wait does not turn up in someone else's task`() {
        // It has no session because none existed yet, and it will not have one until the sign-in
        // lands. Until then the box's own screen is showing it — not whichever old task the user
        // opened while they were waiting.
        val waiting = BoxUiState(
            selectedSessionId = "s-1",
            queued = listOf(
                QueuedPrompt(sessionId = null, text = "typed into the wait", heldForSignIn = true),
                QueuedPrompt(sessionId = "s-1", text = "held right here", heldForSignIn = true),
                QueuedPrompt(sessionId = "s-2", text = "someone else's"),
            ),
        )
        assertEquals(listOf("held right here"), waiting.queuedForSelected.map { it.text })
    }

    @Test
    fun `an ordinary message still belongs to the conversation it is starting`() {
        // The unheld case is unchanged: sending opens the task, and the id arrives a moment later.
        val starting = BoxUiState(queued = listOf(QueuedPrompt(sessionId = null, text = "go")))
        assertEquals(listOf("go"), starting.queuedForSelected.map { it.text })
    }
}
