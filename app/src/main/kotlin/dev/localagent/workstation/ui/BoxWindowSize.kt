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
 * How many panes Box shows. Derived from the *window*, never from the device: a Fold changes
 * class mid-process when it opens, and DeX windows are resized by dragging a corner.
 */
enum class BoxLayout {
    /** Phone, folded. Bottom nav, one pane at a time. The daily driver. */
    Single,

    /** Unfolded or tablet. Session list beside the conversation. */
    Dual,

    /** DeX or a wide tablet. Sessions, conversation, and the agent's computer at once. */
    Triple,
    ;

    val showsSessionRail: Boolean get() = this != Single
    val showsComputerPane: Boolean get() = this == Triple
}

/**
 * Material 3 width classes decide Single vs the rest; the extra [TRIPLE_PANE_WIDTH] step is Box's
 * own, because a third pane only earns its place once the conversation can still hold ~55 columns
 * of monospace next to it.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberBoxLayout(width: Dp, height: Dp): BoxLayout = remember(width, height) {
    val sizeClass = WindowSizeClass.calculateFromSize(DpSize(width, height))
    when (sizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> BoxLayout.Single
        WindowWidthSizeClass.Medium -> BoxLayout.Dual
        else -> if (width >= TRIPLE_PANE_WIDTH) BoxLayout.Triple else BoxLayout.Dual
    }
}

val TRIPLE_PANE_WIDTH = 1180.dp

/** Session list width in multi-pane layouts. Wide enough for a session title on two lines. */
val SESSION_PANE_WIDTH = 320.dp

/** Computer pane width in the three-pane layout. */
val COMPUTER_PANE_MIN_WIDTH = 420.dp
