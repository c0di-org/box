package dev.localagent.runtime.qemu

import org.json.JSONObject

/**
 * The two moments in a session that are worth interrupting someone for.
 *
 * This is deliberately *not* a second parser of the harness wire format. `HarnessWire` on the app
 * side owns that job and stays the only place that learns the vocabulary. This reads two type tags
 * and one line of prose each, because `:computer` needs exactly two facts — the agent finished, or
 * the agent is stuck waiting on you — and nothing else.
 *
 * It lives here rather than in the UI process for the reason the whole session design exists:
 * Android kills the Compose process whenever it likes, and "start work, pocket the phone, get told
 * when it's done" has to survive that. A notification posted from the process that owns the VM is
 * posted whether or not anyone is watching.
 *
 * Unrecognised lines are silence, never an error — a guest image is upgraded independently of the
 * APK, so a newer harness must degrade rather than crash.
 */
internal object SessionSignals {

    sealed interface Signal {
        /** The agent stopped on its own. [summary] is its closing line, when it wrote one. */
        data class Finished(val summary: String?, val failed: Boolean) : Signal

        /** The agent is blocked on a decision only the user can make. */
        data class NeedsYou(val label: String) : Signal
    }

    fun read(line: String): Signal? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        return when (json.optString("type")) {
            "session_ended" -> {
                val outcome = json.optJSONObject("outcome")
                Signal.Finished(
                    summary = outcome?.text("summary") ?: outcome?.text("message"),
                    failed = outcome?.optString("status") == "failed",
                )
            }

            "permission_requested" -> Signal.NeedsYou(describe(json.optJSONObject("ask")))

            // Also a thing only the user can do, and the one most worth a notification: the agent
            // is holding its turn open indefinitely waiting for it, so a phone in a pocket is the
            // difference between a task that finishes and one that is still waiting at bedtime.
            "connect_requested" -> Signal.NeedsYou(
                when (json.optString("service")) {
                    "github" -> "It needs you to connect GitHub"
                    else -> "It needs you to connect an account"
                },
            )
            else -> null
        }
    }

    /**
     * A phrase for the notification, from the ask itself. Never the payload: a diff or a command
     * line on the lock screen is both unreadable and a leak of what the user is working on.
     */
    private fun describe(ask: JSONObject?): String = when (ask?.optString("kind")) {
        "edit_file" -> "It wants to change a file"
        "run_command" -> "It wants to run a command"
        "network_access" -> "It wants to reach the network"
        else -> ask?.text("title") ?: "It needs your decision"
    }

    /** org.json turns a missing string into "", which is not the same thing as absent. */
    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
