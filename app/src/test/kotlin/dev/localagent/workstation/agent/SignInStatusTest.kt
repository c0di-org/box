package dev.localagent.workstation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading `claude auth status --json`, and what happens when there is nothing to read.
 *
 * The second half is the one that was wrong on a device: an ask that never reached the guest was
 * being reported to the user as a signed-out box, and the sign-in banner that followed offered
 * them a sign-in they did not need. Every "did not answer" case below used to return
 * [GuestAuth.State.SignedOut].
 *
 * The two JSON documents are the real ones, taken from the CLI the guest runs.
 */
class SignInStatusTest {

    private val signedIn = """
        {
          "loggedIn": true,
          "authMethod": "claude.ai",
          "apiProvider": "firstParty",
          "email": "someone@example.test",
          "subscriptionType": "max"
        }
    """.trimIndent()

    private val signedOut = """
        {
          "loggedIn": false,
          "authMethod": "none",
          "apiProvider": "firstParty",
          "projectsDirectory": "/workspace/.config/claude/projects"
        }
    """.trimIndent()

    @Test
    fun `a credential and the account it belongs to`() {
        assertEquals(GuestAuth.State.SignedIn("someone@example.test"), SignInStatus.read(signedIn))
    }

    @Test
    fun `no credential is an answer`() {
        // Note the exit status this arrives with is 1, not 0. Signing out is a non-zero exit by
        // design, which is why nothing here looks at one.
        assertEquals(GuestAuth.State.SignedOut, SignInStatus.read(signedOut))
    }

    @Test
    fun `the shapes that have meant yes over the versions`() {
        assertEquals(GuestAuth.State.SignedIn(null), SignInStatus.read("""{"authenticated":true}"""))
        assertEquals(GuestAuth.State.SignedIn(null), SignInStatus.read("""{"status":"authenticated"}"""))
        // The email has been nested as well as top-level.
        assertEquals(
            GuestAuth.State.SignedIn("nested@example.test"),
            SignInStatus.read("""{"loggedIn":true,"account":{"email":"nested@example.test"}}"""),
        )
    }

    @Test
    fun `a session that never ran said nothing`() {
        // `AgentSessionHost` reports a session it could not start as onClosed(-1, …) — no stdout at
        // all. That is the shape of the bug: a guest still waking up, reported as signed out.
        assertNull(SignInStatus.read(""))
        assertNull(SignInStatus.read("   \n "))
    }

    @Test
    fun `output that is not the document asked for said nothing`() {
        assertNull(SignInStatus.read("claude: command not found"))
        assertNull(SignInStatus.read("{ half a document"))
    }

    @Test
    fun `an unanswered ask never claims a signed-out guest`() {
        // The whole point. Neither SignedOut nor Failed, because both raise the banner.
        assertEquals(GuestAuth.State.Unknown, SignInStatus.unanswered(GuestAuth.State.Unknown))
        assertEquals(GuestAuth.State.Unknown, SignInStatus.unanswered(GuestAuth.State.SignedOut))
        assertEquals(GuestAuth.State.Unknown, SignInStatus.unanswered(GuestAuth.State.Checking))
        assertEquals(GuestAuth.State.Unknown, SignInStatus.unanswered(GuestAuth.State.Failed("no")))
    }

    @Test
    fun `a box known to be signed in stays signed in`() {
        val known = GuestAuth.State.SignedIn("someone@example.test")
        assertEquals(known, SignInStatus.unanswered(known))
    }

    @Test
    fun `asking again gives up eventually`() {
        val delays = generateSequence(0) { it + 1 }
            .map { SignInStatus.retryAfterMillis(it) }
            .takeWhile { it != null }
            .filterNotNull()
            .toList()
        // Short first, because the ordinary case is a guest seconds away from answering.
        assertEquals(2_000L, delays.first())
        // Widening, and finite: a box that is not going to answer is not interrogated forever.
        assertEquals(delays.sorted(), delays)
        assertEquals(67_000L, delays.sum())
    }
}
