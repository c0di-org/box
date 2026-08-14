package dev.localagent.workstation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.localagent.workstation.computer.GuestPointer

/**
 * The keys, and the bar you drag to decide how much of the screen they get.
 *
 * A band in the layout rather than a floating overlay, which is the whole reason this exists
 * instead of a call to `showSoftInput`. An IME sits *on top of* the window and Android reports it
 * as an inset, so the guest's screen is either covered or resized to dodge it every time somebody
 * types — a full X mode set, on an emulated machine, per typing session. A sibling in a `Column`
 * costs one resize when the keyboard appears and one when it goes.
 *
 * **The bar is not a nicety.** The right split between screen and keys differs for reading a long
 * diff and typing one, and changes several times an hour. What the drag trades is key *size*
 * against screen: a keyboard given less height keeps its key shape and gives up width, and that
 * width becomes the gutter down the middle — so squeezing walks the halves apart into a split
 * keyboard and letting it out closes them up. See
 * [KeyboardLayout][dev.localagent.workstation.computer.KeyboardLayout].
 *
 * Dragging *up* past the height the keys asked for is allowed and does not enlarge them: they are
 * already as wide as the screen permits, so the extra is empty space above. That is the point of
 * allowing it — on a half-folded phone it pushes the guest's screen off the crease and onto the
 * upright half, where a laptop's screen would be.
 */
@Composable
internal fun OnScreenKeyboard(
    pointer: GuestPointer,
    onKey: (Int, Boolean) -> Unit,
    pointerScale: () -> Float,
    prefs: KeyboardPrefs,
    paneHeightPx: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val keys = remember { GuestKeyboardView(context, prefs) }

    // Whatever the last drag settled on, in pixels. Zero until one happens, which means "the height
    // the saved share asks for", which itself falls back to the height the keys want.
    //
    // Forgotten whenever the window changes shape, which on a foldable is several times a minute.
    // A pixel height is only an answer for the pane it was chosen in; the share it was saved as is
    // the answer that travels, and dropping this is what lets it be consulted again.
    var dragged by remember(paneHeightPx) { mutableIntStateOf(0) }

    // Leaving the composition mid-Control or mid-drag must not leave the guest holding a key that
    // nothing will ever release.
    DisposableEffect(keys) { onDispose { keys.releaseAll() } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        if (width <= 0) return@BoxWithConstraints

        val ceiling = if (paneHeightPx > 0) {
            (paneHeightPx * MAX_SHARE).toInt()
        } else {
            keys.preferredHeight(width)
        }
        val floor = keys.minimumHeight(width).coerceAtMost(ceiling)
        val wanted = when {
            dragged > 0 -> dragged
            prefs.share > 0f && paneHeightPx > 0 -> (paneHeightPx * prefs.share).toInt()
            else -> keys.preferredHeight(width)
        }
        val band = wanted.coerceIn(floor, ceiling)
        val bandDp = with(LocalDensity.current) { band.toDp() }
        val density = LocalDensity.current

        Column(Modifier.fillMaxWidth()) {
            // A bar with less than its own height of travel in it is a control that looks like it
            // does something and doesn't. That is the small-screen case: keys already at the
            // smallest size worth aiming at have no width left to trade away.
            if (ceiling - floor > with(density) { GRIP_HEIGHT.toPx() }) {
                Grip(
                    onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onDrag = { delta ->
                        dragged = (band - delta.toInt()).coerceIn(floor, ceiling)
                    },
                    onDragEnd = {
                        if (paneHeightPx > 0) prefs.share = dragged.toFloat() / paneHeightPx
                    },
                )
            }
            AndroidView(
                factory = { keys },
                modifier = Modifier.fillMaxWidth().height(bandDp),
                update = { view ->
                    view.pointer = pointer
                    view.onKey = onKey
                    view.desktopPointerScale = pointerScale
                    view.reloadLayout()
                },
            )
        }
    }
}

/**
 * The bar above the keys.
 *
 * Its own strip of layout rather than a handle drawn inside the keyboard, because the thing being
 * resized is the seam between two panes and the seam is where a hand goes looking for it. It is
 * also the only chrome the keyboard has, which is why it carries no label: a bar of this shape at
 * the top of a panel has meant "drag me" since long before this app.
 */
@Composable
private fun Grip(onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(GRIP_HEIGHT)
            .background(KEYBOARD_BACKDROP)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 64.dp, height = 5.dp)
                .background(Color(0xFF3A4A3E), RoundedCornerShape(3.dp)),
        )
    }
}

/**
 * The most the band may take. Past this the guest's screen has stopped being the thing on screen,
 * and a keyboard with no window above it is a keyboard typing into nowhere.
 */
private const val MAX_SHARE = 0.72f

private val GRIP_HEIGHT = 22.dp

/** Matches [GuestKeyboardView]'s own backdrop, so the bar reads as the top edge of the keyboard. */
private val KEYBOARD_BACKDROP = Color(0xFF080A09)
