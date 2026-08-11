package dev.localagent.workstation

import android.content.Context

/**
 * How long opening the box takes on *this* phone.
 *
 * One number, kept across restarts, because the alternative is a constant compiled into the app
 * that cannot know which device or which thermal state it is describing. The same Fold 7 measured
 * 168 seconds cold and 252 seconds with the SoC already hot; a shipped figure is wrong for one of
 * those and usually both.
 *
 * Nothing here is guessed at read time — an unknown returns null, and [BoxProgress] falls back to
 * its documented assumption rather than this class inventing one.
 */
class OpeningHistory(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** The learned duration, or null until the box has been opened once on this device. */
    fun expectedMillis(): Long? {
        val stored = preferences.getLong(KEY, 0L)
        return stored.takeIf { it in BoxProgress.MIN_LEARNED_MILLIS..BoxProgress.MAX_LEARNED_MILLIS }
    }

    fun record(millis: Long) {
        preferences.edit().putLong(KEY, millis).apply()
    }

    private companion object {
        /** Shared with the notification-permission flag in `MainActivity`. */
        const val PREFERENCES = "box_product"
        const val KEY = "expected_open_millis"
    }
}
