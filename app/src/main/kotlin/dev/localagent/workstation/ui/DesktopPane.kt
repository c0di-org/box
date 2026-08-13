package dev.localagent.workstation.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.localagent.workstation.computer.DesktopState
import dev.localagent.workstation.computer.DesktopTransport
import kotlinx.coroutines.launch

/**
 * The machine's screen, live.
 *
 * Two ways in and they are the same surface: the thumbnail on the home row, and the computer
 * itself. The difference is only how much room it gets and whether input reaches it, because a
 * desktop that behaves differently depending on where it is drawn is two things to get right
 * instead of one. They can be on screen together — the transport paints every attached surface
 * from one connection.
 *
 * Control is deliberately not handed back here. This composable leaves the tree constantly — the
 * row scrolls off, a panel covers it — and none of that means the user has finished driving. The
 * handover belongs to leaving the computer; see `BoxViewModel.showTasks`.
 */
@Composable
fun DesktopSurface(
    transport: DesktopTransport,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    onViewReady: (DesktopView) -> Unit = {},
) {
    val current by transport.state.collectAsState()
    val scope = rememberCoroutineScope()

    /**
     * How much of this pane the soft keyboard is currently sitting on.
     *
     * Added back to the height reported to the transport, so the guest's screen follows the
     * *window* and not the keyboard. `BoxApp` applies `safeDrawingPadding`, which includes the IME,
     * so opening the keyboard genuinely shrinks this pane — and without this the guest would do a
     * full X mode set every time somebody started typing, moving every window inside it, to match
     * a rectangle that is about to go away again.
     *
     * Only the *reported* height is adjusted. What is painted is still the real surface, which is
     * why this is safe: the transport uses these numbers to decide how big the machine's screen
     * should be, and reads the actual pixel dimensions off the `Surface` when it draws.
     */
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    val keyboardInset = remember { mutableIntStateOf(0) }
    keyboardInset.intValue = keyboard
    val reported = remember { mutableIntStateOf(0) }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                DesktopView(context).apply {
                    onSurfaceReady = { surface, width, height ->
                        scope.launch { transport.attach(surface, width, height + keyboardInset.intValue) }
                    }
                    onSurfaceGone = { surface -> scope.launch { transport.detach(surface) } }
                    onInput = { input -> scope.launch { transport.send(input) } }
                    onViewReady(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.interactive = interactive
                view.guestSize = (current as? DesktopState.Live)?.let { it.widthPx to it.heightPx }
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
            )

            is DesktopState.Live -> Unit
        }
    }
}

@Composable
private fun DesktopPlaceholder(title: String, body: String, busy: Boolean) {
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
    }
}

