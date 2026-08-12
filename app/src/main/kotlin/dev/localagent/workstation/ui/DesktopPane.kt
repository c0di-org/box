package dev.localagent.workstation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                DesktopView(context).apply {
                    onSurfaceReady = { surface, width, height ->
                        scope.launch { transport.attach(surface, width, height) }
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

