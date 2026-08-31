package dev.localagent.workstation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.localagent.workstation.computer.DesktopState
import dev.localagent.workstation.computer.DesktopTransport
import dev.localagent.workstation.computer.GuestPointer
import dev.localagent.workstation.computer.GuestScreen
import kotlinx.coroutines.launch

/**
 * The machine's screen, live.
 *
 * Two ways in and they are the same surface: the minimap in the box's header, and the computer
 * itself. The difference is only how much room it gets and whether input reaches it — a desktop
 * that behaved differently by where it was drawn would be two things to get right. They can be on
 * screen together, since the transport paints every attached surface from one connection.
 *
 * Control is deliberately not handed back here. This composable leaves the tree constantly — the
 * row scrolls off, a panel covers it — and none of that means the user has finished driving. The
 * handover belongs to leaving the computer; see `BoxViewModel.showTasks`.
 *
 * @param pointer the cursor this surface steers, shared with the on-screen keyboard so the two
 * cannot disagree about where it is. A view only ever looked at gets one of its own, never moved.
 * @param preview this view is a picture of the screen rather than a screen, and its size must not
 * decide the guest's. Read once when the surface first attaches. See [DesktopTransport.attach].
 */
@Composable
internal fun DesktopSurface(
    transport: DesktopTransport,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    pointer: GuestPointer? = null,
    onViewReady: (DesktopView) -> Unit = {},
) {
    val current by transport.state.collectAsState()
    val scope = rememberCoroutineScope()
    val steered = pointer ?: remember { GuestPointer {} }

    /**
     * How much of this pane the soft keyboard is currently sitting on.
     *
     * Added back to the height reported to the transport, so the guest's screen follows the
     * *window* and not the keyboard. `BoxApp` applies `safeDrawingPadding`, which includes the IME,
     * so opening the keyboard genuinely shrinks this pane — and without this the guest would do a
     * full X mode set every time somebody started typing, moving every window inside it, to match
     * a rectangle about to go away again.
     *
     * Only the *reported* height is adjusted; what is painted is the real surface. The transport
     * uses these numbers to size the machine's screen and reads actual pixel dimensions off the
     * `Surface` when it draws.
     *
     * Box's own keyboard needs none of this: it is a sibling in the layout rather than an inset,
     * so the pane it leaves is the pane the guest should fill.
     */
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    val keyboardInset = remember { mutableIntStateOf(0) }
    keyboardInset.intValue = keyboard
    val reported = remember { mutableIntStateOf(0) }

    /** Where Box's own cursor is, in this pane's pixels, and whether it is Box's job to draw one. */
    var cursor by remember { mutableStateOf(Offset.Zero) }
    var cursorShown by remember { mutableStateOf(false) }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                DesktopView(context).apply {
                    onSurfaceReady = { surface, width, height ->
                        scope.launch {
                            transport.attach(
                                surface,
                                width,
                                height + keyboardInset.intValue,
                                preview,
                            )
                        }
                    }
                    onSurfaceGone = { surface -> scope.launch { transport.detach(surface) } }
                    onInput = { input -> scope.launch { transport.send(input) } }
                    onCursor = { x, y, shown ->
                        cursor = Offset(x, y)
                        cursorShown = shown
                    }
                    onViewReady(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // Assigned before `interactive`, because taking control repaints the cursor and the
                // cursor is this object's to report.
                view.pointer = steered
                view.interactive = interactive
                view.guestSize =
                    (current as? DesktopState.Live)?.let { GuestScreen(it.widthPx, it.heightPx) }
                // The keyboard's inset and the surface's own resize come from two different
                // systems and not in a fixed order. Whichever settles second has to re-report, or
                // the guest is left sized to a pane the keyboard was covering at the moment the
                // surface happened to change. Guarded on the value so an ordinary recomposition
                // does not cost a repaint.
                if (reported.intValue != keyboard) {
                    reported.intValue = keyboard
                    view.reportSize()
                }
            },
        )

        if (cursorShown) TouchCursor(cursor)

        when (val snapshot = current) {
            DesktopState.Starting, DesktopState.Unavailable -> DesktopPlaceholder(
                title = "Connecting to the screen",
                body = "The computer is drawing its display for the first time.",
                busy = true,
            )

            is DesktopState.Failed -> DesktopPlaceholder(
                title = "No picture",
                body = snapshot.message,
                busy = false,
                // The one thing a failed desktop was missing. Without it the pane states a fact
                // and offers nothing to do about it, and the only route back to a picture is to
                // leave the computer entirely and come back — which works by accident, because it
                // happens to drop the last surface. See [DesktopTransport.reconnect].
                action = "Try again" to { scope.launch { transport.reconnect() } },
            )

            is DesktopState.Live -> Unit
        }
    }
}

/**
 * The pointer a finger is steering, drawn by Box.
 *
 * The guest draws a cursor of its own and this is deliberately a second one: this is where the hand
 * has got to, the guest's is however far behind the emulated machine currently is, and the gap
 * between them is the only honest reading of that available. With a mouse, Android's own arrow
 * plays this part and Box draws nothing.
 *
 * A white arrow with a dark outline rather than a filled shape in one colour: it has to stay
 * findable over a terminal, a white text editor and a photograph, and only an outline survives all
 * three.
 */
@Composable
private fun TouchCursor(at: Offset) {
    val size = with(LocalDensity.current) { 20.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val arrow = Path().apply {
            moveTo(at.x, at.y)
            lineTo(at.x, at.y + size)
            lineTo(at.x + size * 0.27f, at.y + size * 0.74f)
            lineTo(at.x + size * 0.46f, at.y + size * 1.11f)
            lineTo(at.x + size * 0.62f, at.y + size * 1.03f)
            lineTo(at.x + size * 0.43f, at.y + size * 0.67f)
            lineTo(at.x + size * 0.71f, at.y + size * 0.65f)
            close()
        }
        drawPath(arrow, Color.White)
        drawPath(arrow, Color.Black.copy(alpha = 0.85f), style = Stroke(width = 1.5f))
    }
}

@Composable
private fun DesktopPlaceholder(
    title: String,
    body: String,
    busy: Boolean,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(28.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = CodeColors.muted)
            Spacer(Modifier.height(14.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = CodeColors.plain,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 13.sp,
            color = CodeColors.muted,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = action.second) { Text(action.first) }
        }
    }
}
