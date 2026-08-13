package dev.localagent.workstation.ui

import android.content.res.Configuration
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.localagent.workstation.agent.AgentViewport
import dev.localagent.workstation.agent.ViewportLayout

/**
 * How the tasks destination is laid out. Derived from the *window*, never from the device: a Fold
 * changes class mid-process when it opens, and DeX windows are resized by dragging a corner.
 *
 * There is no third arrangement any more. A wide window used to put the computer in the narrowest
 * of three columns, non-interactive, with tab chips over it — the machine drawn as a photograph of
 * itself. It gets the whole window instead; see [ComputerPane].
 */
enum class BoxLayout {
    /** Phone, folded. One pane at a time. The daily driver. */
    Single,

    /** Unfolded, tablet, or DeX. Task list beside the conversation. */
    Wide,
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberBoxLayout(width: Dp, height: Dp): BoxLayout = remember(width, height) {
    val sizeClass = WindowSizeClass.calculateFromSize(DpSize(width, height))
    when (sizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> BoxLayout.Single
        else -> BoxLayout.Wide
    }
}

/**
 * The same measurement, in the form an agent is told about — see [AgentViewport].
 *
 * It reads the window for [BoxLayout] and [AgentViewport.widthDp], and the configuration for the
 * keyboard, which is the one fact here that no window can answer. That is a device fact, and the
 * doc comment above is a warning against sending those; the distinction that lets this one through
 * is that it is re-sent. What goes stale and hurts is a fact told once and believed all session —
 * "this is a DeX device" — not a keyboard that is reported again the moment it is undocked.
 *
 * `keyboard` rather than `keyboardHidden`: the question is whether there is a real keyboard to
 * type on, not whether a soft one happens to be showing this second.
 */
@Composable
fun rememberViewport(width: Dp, height: Dp): AgentViewport {
    val layout = rememberBoxLayout(width, height)
    val configuration = LocalConfiguration.current
    val hardwareKeyboard = configuration.keyboard != Configuration.KEYBOARD_NOKEYS
    return remember(layout, width, hardwareKeyboard) {
        AgentViewport(
            layout = when (layout) {
                BoxLayout.Single -> ViewportLayout.Compact
                BoxLayout.Wide -> ViewportLayout.Wide
            },
            widthDp = width.value.toInt(),
            hardwareKeyboard = hardwareKeyboard,
        )
    }
}

/** Session list width in the wide layout. Wide enough for a session title on two lines. */
val SESSION_PANE_WIDTH = 320.dp
