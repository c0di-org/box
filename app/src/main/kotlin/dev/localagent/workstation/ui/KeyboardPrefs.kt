package dev.localagent.workstation.ui

import android.content.Context

/**
 * When the on-screen keyboard appears.
 *
 * [AUTO] is the whole point of the feature — pick up a phone with nothing plugged into it and the
 * keys are there. The other two exist because auto-detection you can't correct is worse than no
 * auto-detection at all: a Bluetooth keyboard that is paired but across the room looks, to
 * [HardwareInput][dev.localagent.workstation.computer.HardwareInput], exactly like one you are
 * typing on.
 */
internal enum class OnScreenKeyboardMode(val label: String) {
    AUTO("Automatic"),
    ALWAYS("Always"),
    NEVER("Never"),
}

/**
 * How the user likes the keys, remembered across sessions.
 *
 * Its own preferences file rather than a corner of somebody else's: nothing here is about the box,
 * the agent or a session, and all of it is about this phone.
 */
internal class KeyboardPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var mode: OnScreenKeyboardMode
        get() = OnScreenKeyboardMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) }
            ?: OnScreenKeyboardMode.AUTO
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** F1–F12 as a row of their own. On, because a terminal multiplexer is most of what runs here. */
    var functionRow: Boolean
        get() = prefs.getBoolean(KEY_FUNCTION_ROW, true)
        set(value) = prefs.edit().putBoolean(KEY_FUNCTION_ROW, value).apply()

    /** Pull the two halves apart, so the inner keys land under the thumbs. */
    var split: Boolean
        get() = prefs.getBoolean(KEY_SPLIT, false)
        set(value) = prefs.edit().putBoolean(KEY_SPLIT, value).apply()

    /**
     * How much of the computer pane the keys take, set by dragging the bar above them. Zero means
     * the height the keyboard asks for, which is the most its keys can use.
     *
     * A fraction rather than pixels because the window this is remembered for may be a foldable's:
     * it changes size several times a minute, and a height that was two thirds of the screen folded
     * shouldn't come back as a strip when it opens.
     */
    var share: Float
        get() = prefs.getFloat(KEY_SHARE, 0f)
        set(value) = prefs.edit().putFloat(KEY_SHARE, value).apply()

    private companion object {
        const val PREFERENCES = "box_keyboard"
        const val KEY_MODE = "mode"
        const val KEY_FUNCTION_ROW = "functionRow"
        const val KEY_SPLIT = "split"
        const val KEY_SHARE = "share"
    }
}
