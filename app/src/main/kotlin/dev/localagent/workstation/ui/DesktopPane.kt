package dev.localagent.workstation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.localagent.workstation.computer.ControlHolder
import dev.localagent.workstation.computer.DesktopState
import dev.localagent.workstation.computer.DesktopTransport
import kotlinx.coroutines.launch

/**
 * The agent's screen, live.
 *
 * Two ways in and they are the same surface: inline in the Computer pane, and full window. The
 * difference is only how much room it gets and whether the chrome is there, because a desktop that
 * behaves differently depending on where it is drawn is two things to get right instead of one.
 */
@Composable
fun DesktopSurface(
    transport: DesktopTransport,
    interactive: Boolean,
    modifier: Modifier = Modifier,
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
                    onSurfaceGone = { scope.launch { transport.detach() } }
                    onInput = { input -> scope.launch { transport.send(input) } }
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

    // Handing control back on the way out is not politeness, it is correctness: a key held down
    // when the screen closes would otherwise stay held in the guest forever.
    DisposableEffect(Unit) {
        onDispose { scope.launch { transport.setControl(ControlHolder.Agent) } }
    }
}

/**
 * Full window. What the product calls "Open computer".
 *
 * Chrome is one thin bar, and it stays: a desktop with no way back is a trap, and Back is
 * deliberately not forwarded to the guest for the same reason.
 */
@Composable
fun DesktopFullWindow(
    transport: DesktopTransport,
    control: ControlHolder,
    onSetControl: (ControlHolder) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true) { onClose() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back to the conversation",
                    tint = Color.White,
                )
            }
            Text(
                "Agent's Computer",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            ControlToggle(control, onSetControl)
        }
        DesktopSurface(
            transport = transport,
            interactive = control == ControlHolder.User,
            modifier = Modifier.fillMaxSize().weight(1f),
        )
    }
}

/**
 * Who is driving.
 *
 * Explicit rather than implicit-on-touch. The agent is working in there, and a stray tap that
 * silently stole the keyboard mid-task would be the kind of thing you only notice afterwards.
 */
@Composable
private fun ControlToggle(control: ControlHolder, onSetControl: (ControlHolder) -> Unit) {
    when (control) {
        ControlHolder.Agent -> Button(
            onClick = { onSetControl(ControlHolder.User) },
            shape = RoundedCornerShape(14.dp),
        ) { Text("Take over") }

        ControlHolder.User -> OutlinedButton(
            onClick = { onSetControl(ControlHolder.Agent) },
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
        ) { Text("Give back", color = Color.White) }
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

