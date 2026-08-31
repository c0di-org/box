package dev.localagent.workstation

import dev.localagent.workstation.agent.Attachment
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
    fun `a box that could not answer does not put a sign-in in front of a signed-in user`() {
        // Force-quit Box and come back: a fresh process asks the guest once, and on a box that is
        // Ready-but-not-yet-answering the ask fails. That is Unknown, not SignedOut — and Unknown
        // must not raise the banner over a phone that has signed in before.
        //
        // See `SignInStatus.unanswered`, which is what turns a failed ask into this state.
        assertFalse(state(GuestAuth.State.Unknown, signedInBefore = true).needsSignIn)
        assertFalse(state(GuestAuth.State.Unknown, signedInBefore = true).signInWanted)
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

    @Test
    fun `a message held for a sign-in keeps the files it was sent with`() {
        // The composer is cleared the moment a message is queued, because a second tap must not
        // send the same files twice — so the queued copy is the only one left. A picture attached
        // before signing in has to still be attached three minutes later, when the sign-in lands
        // and the wait is finally sent; the alternative is a turn about a file nobody mentioned.
        val waiting = BoxUiState(
            queued = listOf(
                QueuedPrompt(
                    sessionId = null,
                    text = "what is this?",
                    heldForSignIn = true,
                    attachments = listOf(shot),
                ),
            ),
        )
        assertEquals(listOf(listOf(shot)), waiting.heldForSignIn.map { it.attachments })
    }

    @Test
    fun `letting a held message go does not strip its files`() {
        // Exactly what the flush does to each one before sending it. It is a `copy` rather than a
        // rebuild for this reason: a rebuild that forgot the new field would lose the picture, and
        // would lose it silently, in the one path nobody watches because it only runs once.
        val held = QueuedPrompt(null, "look", heldForSignIn = true, attachments = listOf(shot))

        val released = held.copy(heldForSignIn = false)

        assertFalse(released.heldForSignIn)
        assertEquals(listOf(shot), released.attachments)
        assertEquals("look", released.text)
    }

    @Test
    fun `a picture with no words is a whole message, held or not`() {
        // Box does not require a word alongside a picture, so an empty text with an attachment is
        // a real message and not an empty one to be dropped on the way through the wait.
        val waiting = BoxUiState(
            queued = listOf(QueuedPrompt(null, "", heldForSignIn = true, attachments = listOf(shot))),
        )
        assertEquals(1, waiting.heldForSignIn.size)
        assertEquals(listOf(shot), waiting.heldForSignIn.single().attachments)
    }

    private companion object {
        val shot = Attachment(
            guestPath = "/workspace/shared/inbox/20260812-214755-shot.png",
            name = "shot.png",
            mimeType = "image/png",
            bytes = 4,
        )
    }
}
