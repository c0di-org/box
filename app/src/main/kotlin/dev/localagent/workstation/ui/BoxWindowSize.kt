package dev.localagent.workstation.ui

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

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

/** Session list width in the wide layout. Wide enough for a session title on two lines. */
val SESSION_PANE_WIDTH = 320.dp
