package dev.localagent.workstation.ui

import java.util.concurrent.TimeUnit

/**
 * When something last happened, in the shortest true form.
 *
 * The task list's second line used to fall back to the working directory, which is `/workspace` for
 * every task there has ever been — a column of identical text under a column of identical titles.
 * Time is the opposite: different for every row, what a reader sorts by, and true whether or not
 * the agent has said anything yet.
 *
 * Coarse on purpose, and coarser further back. "4 minutes ago" and "5 minutes ago" are the same
 * fact to the reader, and a list that reflows every sixty seconds draws the eye to the wrong thing.
 */
object Ago {

    fun of(thenMillis: Long, nowMillis: Long): String {
        val elapsed = nowMillis - thenMillis
        // Clocks move backwards — a device timezone change, an NTP correction, a summary written
        // by a process that has since been restarted. "just now" is the least wrong thing to say
        // about something that appears not to have happened yet.
        if (elapsed <= 0) return "just now"

        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
        if (minutes < 1) return "just now"
        if (minutes < 60) return "${minutes}m ago"

        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
        if (hours < 24) return "${hours}h ago"

        val days = TimeUnit.MILLISECONDS.toDays(elapsed)
        if (days < 7) return "${days}d ago"
        return "${days / 7}w ago"
    }
}
