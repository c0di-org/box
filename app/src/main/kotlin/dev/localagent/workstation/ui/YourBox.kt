package dev.localagent.workstation.ui

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BoxProgress
import dev.localagent.workstation.BoxStage
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.TOUR_PROMPT
import dev.localagent.workstation.computer.DesktopTransport
import kotlinx.coroutines.delay

/**
 * Your box, at whatever size the moment deserves.
 *
 * One component, not five screens. The box is the only thing Box has — both of the things you can
 * do are things you do *inside* it — so it holds the window until it is open and then becomes the
 * first row of the task list. Nothing is dismissed and nobody is sent anywhere; the shrink *is*
 * the arrival, which is why the caller owns the height and animates it.
 *
 * Four states, each only allowed to say what is true at that moment:
 *
 * - **Closed** — a mark and a button, or one line and one word once there are tasks behind it.
 * - **Opening** — the ring, what it is doing, how long is left, and the first task, which can be
 *   typed now and is sent the moment the guest can take it.
 * - **Just opened, once ever** — the arrival gets the window exactly one time in the life of an
 *   install, and spends it on the two ways to use this thing, or on the sign-in one of them needs.
 * - **Open** — the header, carrying the machine's own screen as large as the column can make it.
 *
 * Settled, all of that is one header rather than a bar plus a card: see [SettledBox].
 */
@Composable
fun YourBox(
    state: BoxUiState,
    progress: BoxProgress,
    desktop: DesktopTransport?,
    full: Boolean,
    onOpen: () -> Unit,
    onOpenComputer: () -> Unit,
    onOpenChat: () -> Unit,
    onSendFirstTask: (String) -> Unit,
    /** [TOUR_PROMPT], said as an ordinary message. See `BoxViewModel.startTour`. */
    onTour: () -> Unit,
    onDismissGreeting: () -> Unit,
    onShowDetails: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which face is showing, and deliberately coarser than the state behind it: every settled
    // stage is the same header, so the box opening does not cross-fade its own mark out and back
    // in again. What changes under the strip is the strip's business — see [SettledBox].
    val face = when {
        !full -> BoxFace.Settled
        state.boxStage == BoxStage.Closed -> BoxFace.ClosedHero
        state.boxStage == BoxStage.Working -> BoxFace.OpeningHero
        else -> BoxFace.ReadyHero
    }

    Crossfade(
        targetState = face,
        animationSpec = tween(SETTLE_MILLIS / 2),
        label = "your box",
        modifier = modifier,
    ) { showing ->
        when (showing) {
            BoxFace.ClosedHero -> ClosedHero(state = state, onOpen = onOpen)

            BoxFace.OpeningHero -> OpeningHero(
                progress = progress,
                canType = state.harnesses.isNotEmpty(),
                // Everything typed into the wait, held because there is nobody signed in yet. A
                // message that is *not* held never reaches this screen: sending one opens the task
                // it belongs to and the conversation takes the window.
                waiting = state.heldForSignIn.map { it.text },
                onSend = onSendFirstTask,
                onTour = onTour,
                onWatch = onOpenComputer,
            )

            BoxFace.ReadyHero -> ReadyHero(
                signInWanted = state.signInWanted,
                waiting = state.heldForSignIn.map { it.text },
                onChat = {
                    onDismissGreeting()
                    onOpenChat()
                },
                onComputer = {
                    onDismissGreeting()
                    onOpenComputer()
                },
                // An ordinary message, which from the arrival means a new conversation simply
                // because nothing is selected yet. [ReadyHero] draws this door only once somebody
                // is signed in.
                onTour = {
                    onDismissGreeting()
                    onTour()
                },
                // The greeting deliberately stays up behind the sheet. Signing in is a step of the
                // arrival, not a way out of it — and when it lands, this screen is holding the two
                // doors that were the point of it.
                onSignIn = onSignIn,
            )

            BoxFace.Settled -> SettledBox(
                state = state,
                progress = progress,
                desktop = desktop,
                onOpen = onOpen,
                onOpenComputer = onOpenComputer,
                onShowDetails = onShowDetails,
            )
        }
    }
}

/** Which of the box's faces the window is showing. See [YourBox]. */
private enum class BoxFace { ClosedHero, OpeningHero, ReadyHero, Settled }


/**
 * The mark, with the opening drawn around it.
 *
 * For the screens that give the opening the whole window — the hero here, and the computer while it
 * boots. The settled header states it as a hairline under the strip instead; a ring this size is a
 * decoration in a 52dp row. The ring is a clock corrected by checkpoints rather than either alone
 * — see [BoxProgress] — and turns amber once this phone has run past its own usual time.
 */
@Composable
fun OpeningMark(
    progress: BoxProgress,
    opening: Boolean,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    val fraction by animateFloatAsState(
        targetValue = progress.fraction,
        animationSpec = tween(900),
        label = "opening",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        if (opening) {
            val spoken = if (progress.determinate) {
                "Opening your box: ${progress.phase}, ${(fraction * 100).toInt()} percent"
            } else {
                "Opening your box: ${progress.phase}"
            }
            val ring = Modifier.size(size * 1.4f).semantics { contentDescription = spoken }
            val ink = if (progress.overdue) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.primary
            }
            if (progress.determinate) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = ring,
                    color = ink,
                    strokeWidth = 2.5.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                CircularProgressIndicator(ring, color = ink, strokeWidth = 2.5.dp)
            }
        }
        BoxMark(size)
    }
}

// ---------------------------------------------------------------------------
// Full window
// ---------------------------------------------------------------------------

/**
 * The first thing anyone sees.
 *
 * A mark and a button. There was a headline above the button saying the same words the button
 * says, and a paragraph explaining what a box is — but nobody reads an explanation of a product
 * they have already installed, and the button is the only thing on this screen that does anything.
 */
@Composable
private fun ClosedHero(state: BoxUiState, onOpen: () -> Unit) {
    val failure = (state.runtimeState as? RuntimeState.Failed)?.reason

    HeroFrame {
        BoxMark(96.dp)
        if (failure != null) {
            Spacer(Modifier.height(26.dp))
            Text(
                "Your box didn’t open",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                failure.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )
        }
        Spacer(Modifier.height(if (failure != null) 28.dp else 40.dp))
        Button(
            onClick = onOpen,
            modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(20.dp),
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Text(
                if (failure != null) "Try again" else "Open your box",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // What this button costs, in one line, before anyone commits to it. Three minutes is long
        // enough that discovering it halfway through is worse than being told — and so is
        // discovering, at the end of those three minutes, that there is still a sign-in to do.
        // Box cannot *ask* for the sign-in yet (the handshake runs inside the guest), but it can
        // say it is coming: see [BoxUiState.signInWanted].
        val note = when {
            failure != null -> null
            state.runtimeState == RuntimeState.NotProvisioned && state.signInWanted ->
                "~3 min the first time, then a quick sign-in"
            state.runtimeState == RuntimeState.NotProvisioned -> "~3 min the first time"
            state.signInWanted -> "You’ll sign in to Claude once it’s open"
            else -> null
        }
        note?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The wait, given something to do.
 *
 * Pressing the button used to collapse the hero to zero height and hand back an app with no tasks,
 * no transcript and no computer in it, so an honest three-minute wait became three minutes in an
 * empty list watching a 32dp ring. The ring was not the problem; the emptiness was.
 *
 * So the box keeps the window while it opens, and the wait carries the one genuinely useful thing:
 * the first task. What is typed here queues and goes the moment the guest can take it
 * ([BoxUiState.queued]), which also makes the arrival something the user can *see* — their own
 * message finally sending — rather than a row appearing quietly behind them.
 */
@Composable
private fun OpeningHero(
    progress: BoxProgress,
    canType: Boolean,
    waiting: List<String>,
    onSend: (String) -> Unit,
    onTour: () -> Unit,
    onWatch: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OpeningMark(progress, opening = true, size = 64.dp)
        Spacer(Modifier.height(28.dp))
        Text(
            "Opening your box",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            openingLine(progress),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // While the box is opening, the sign-in is not the wait anyone can act on — it cannot even
        // be asked for yet — so this says the thing that is true now and the arrival says the rest.
        HeldMessages(waiting, "Waiting for your box.")
        Spacer(Modifier.height(34.dp))
        Composer(
            enabled = canType,
            blockedReason = null,
            placeholder = "Ask Box anything…",
            onSend = onSend,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        // Only while the wait is still empty. Once they have queued something of their own, the
        // suggestion is competing with it for the same slot — and theirs is the one that matters.
        if (canType && waiting.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            TourSuggestion(onTour)
        }
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onWatch) { Text("Watch it boot") }
    }
}

/**
 * [TOUR_PROMPT], as something to tap.
 *
 * Drawn as the message it will become rather than as a button describing one, because that is what
 * pressing it does: the words appear in the conversation over the user's name, and a control
 * labelled "Take a tour" would have been Box putting words there they never chose. Quiet enough to
 * be declined — it sits under the composer, not in front of it.
 */
@Composable
internal fun TourSuggestion(onTap: () -> Unit) {
    Surface(
        onClick = onTap,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.widthIn(max = 520.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                TOUR_PROMPT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The arrival, once ever.
 *
 * The only moment where saying what Box can do is free: the user is already looking at this screen
 * waiting for exactly this. It is also the only place both doors are shown at the same size, which
 * is the point — the computer is not a feature of the chat.
 *
 * The sign-in belongs here because this is the only screen that can hold it. Claude's handshake
 * runs *inside* the guest, so nothing can be asked before the box is open; the moment it opens is
 * the first moment the question can be put, and already the moment the user is looking. Before
 * this, sign-in was a banner discovered *after* a first task had been sent, failed, and retyped.
 *
 * [waiting] is that first task, if they typed one into the wait — shown so their own words are
 * visibly still in hand rather than something they have to trust Box kept.
 */
@Composable
private fun ReadyHero(
    signInWanted: Boolean,
    waiting: List<String>,
    onChat: () -> Unit,
    onComputer: () -> Unit,
    onTour: () -> Unit,
    onSignIn: () -> Unit,
) {
    HeroFrame {
        Box(contentAlignment = Alignment.BottomEnd) {
            BoxMark(96.dp)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp).padding(0.dp),
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(7.dp),
                )
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(
            "Your box is ready",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (signInWanted) {
            Spacer(Modifier.height(8.dp))
            Text(
                "One thing left: sign in to Claude.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            HeldMessages(waiting, "Sent as soon as you’re signed in.")
            Spacer(Modifier.height(34.dp))
            DoorButton(Icons.Outlined.Lock, "Sign in to Claude", onSignIn, primary = true)
            Spacer(Modifier.height(12.dp))
            // Still offered, and still true: a box is a Linux computer whether or not an agent can
            // talk to it, and somebody who came for that should not be held up by a login.
            DoorButton(Icons.Outlined.Computer, "Use the computer", onComputer, primary = false)
        } else {
            Spacer(Modifier.height(34.dp))
            // First, because it is the only one of the three that answers the question somebody
            // standing on this screen actually has. The other two assume they already know.
            DoorButton(Icons.Outlined.AutoAwesome, TOUR_PROMPT, onTour, primary = true)
            Spacer(Modifier.height(12.dp))
            DoorButton(Icons.Outlined.Forum, "Chat with an agent", onChat, primary = false)
            Spacer(Modifier.height(12.dp))
            DoorButton(Icons.Outlined.Computer, "Use the computer", onComputer, primary = false)
        }
    }
}

/**
 * What they asked for, still in hand, waiting on the one step in front of it.
 *
 * The composer clears itself the moment Send is pressed, so a held message with nowhere to appear
 * is a message the user watched vanish. This is that somewhere, on both screens where a prompt can
 * be held: the opening, and the arrival it is handed to.
 *
 * The first one, because that is the line the task will be named after. The rest are counted rather
 * than stacked — this sits in the middle of a hero on a phone, and a column that grows with typing
 * is a hero that pushes its own button off the screen.
 */
@Composable
private fun HeldMessages(waiting: List<String>, note: String) {
    val first = waiting.firstOrNull() ?: return
    Spacer(Modifier.height(18.dp))
    Surface(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text(
                first,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                if (waiting.size > 1) "$note · ${waiting.size - 1} more waiting" else note,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DoorButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    primary: Boolean,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (primary) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        },
        border = BorderStroke(
            1.dp,
            if (primary) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            },
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (primary) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(14.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeroFrame(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = { content() },
        )
    }
}

// ---------------------------------------------------------------------------
// Settled
// ---------------------------------------------------------------------------

/**
 * The box once it has settled: one header, not two.
 *
 * There was a top bar — mark, the word "Box", an overflow menu — sitting directly on a card
 * reading "Computer / Debian · in use" with a 96dp thumbnail and a *second* overflow going to the
 * same sheet. Two headers, two menus, one box, and the only part that changed by the second was
 * the smallest thing on either.
 *
 * So identity is one strip — mark, name, one LED for the machine, one menu — and everything under
 * it belongs to the box's state. Open, that is the machine's screen at whatever size the column
 * can spare: a minimap of what the agent is doing while it talks about doing it, which is the
 * whole claim of the product and the only label here that cannot go stale.
 */
@Composable
private fun SettledBox(
    state: BoxUiState,
    progress: BoxProgress,
    desktop: DesktopTransport?,
    onOpen: () -> Unit,
    onOpenComputer: () -> Unit,
    onShowDetails: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        BoxStrip(state, progress, onShowDetails)
        when (state.boxStage) {
            BoxStage.Open -> ComputerScreen(state, desktop, onOpenComputer)
            BoxStage.Working -> OpeningNote(progress)
            BoxStage.Closed -> ClosedNote(state, onOpen)
        }
    }
}

/**
 * The mark, the name, how the box is, and the one way into its details.
 *
 * The LED carries the machine's state as a colour, read without being read. The words survive for
 * anyone listening rather than looking — see [boxLedState].
 *
 * The opening gets a hairline under the strip rather than a ring around the mark. At this size a
 * ring is a decoration; a bar that crosses the whole column is legible from across the desk, and
 * it is the only thing on this header with a number behind it.
 */
@Composable
private fun BoxStrip(state: BoxUiState, progress: BoxProgress, onShowDetails: () -> Unit) {
    val led = boxLedState(state, progress)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(STRIP_HEIGHT).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxMark(26.dp)
            Spacer(Modifier.width(11.dp))
            Text(
                "Box",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(11.dp))
            BoxLed(led)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShowDetails) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "Your box",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.boxStage == BoxStage.Working) {
            val ink = if (progress.overdue) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.primary
            }
            val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            val bar = Modifier.fillMaxWidth().height(OPENING_BAR_HEIGHT)
            if (progress.determinate) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = bar,
                    color = ink,
                    trackColor = track,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(modifier = bar, color = ink, trackColor = track)
            }
        }
    }
}

/**
 * How the machine is, as one dot.
 *
 * It breathes only while something is happening — the box opening, or an agent at work in it —
 * because that is what is worth catching out of the corner of an eye. A light that pulses when
 * nothing is happening teaches people to stop looking at it.
 */
@Composable
private fun BoxLed(led: BoxLed) {
    val alpha = if (led.pulsing) {
        val pulse = rememberInfiniteTransition(label = "led")
        pulse.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_100), RepeatMode.Reverse),
            label = "led alpha",
        ).value
    } else {
        1f
    }
    StatusDot(
        led.color.copy(alpha = led.color.alpha * alpha),
        7.dp,
        Modifier.semantics { contentDescription = led.spoken },
    )
}

private data class BoxLed(val color: Color, val spoken: String, val pulsing: Boolean)

@Composable
private fun boxLedState(state: BoxUiState, progress: BoxProgress): BoxLed {
    val failure = (state.runtimeState as? RuntimeState.Failed)?.reason
    val paused = state.runtimeState == RuntimeState.Suspended
    return when {
        failure != null -> BoxLed(MaterialTheme.colorScheme.error, "Your box didn’t open", false)
        state.boxStage == BoxStage.Working -> BoxLed(
            if (progress.overdue) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            "Opening your box",
            pulsing = true,
        )
        state.boxStage == BoxStage.Closed -> BoxLed(
            MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            if (paused) "Your box is paused" else "Your box is closed",
            pulsing = false,
        )
        state.agentAtWork -> BoxLed(MaterialTheme.colorScheme.primary, "Running · in use", true)
        else -> BoxLed(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f), "Running", false)
    }
}

/**
 * The machine's own screen, on the home column.
 *
 * As big as the column can give it, and carrying nothing else. This was a 96×60 thumbnail beside
 * a title and a subtitle, which is the wrong way round: the title and the subtitle were the parts
 * that never changed. At this size the picture is genuinely readable as a picture — a window
 * opening, a build scrolling — so watching the agent work is something that can be done from the
 * conversation, without leaving it.
 *
 * Pressing it goes to the machine itself, not to a page about it.
 */
@Composable
private fun ComputerScreen(
    state: BoxUiState,
    desktop: DesktopTransport?,
    onOpenComputer: () -> Unit,
) {
    val label = if (state.agentAtWork) {
        "The computer, in use. Opens the machine."
    } else {
        "The computer. Opens the machine."
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(SCREEN_INSET)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(screenHeight(maxWidth))
                .clip(RoundedCornerShape(14.dp))
                .background(BoxTerminal)
                // A dark desktop on a dark column has no edge of its own. The hairline is what
                // makes the picture an object rather than a hole in the panel.
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onOpenComputer)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (desktop != null) {
                // Never counted when the guest's screen is being sized: a minimap this big is
                // easily larger than a real pane on a small window, and left to count it would
                // resize the guest's display every time the user walked back from the computer.
                DesktopSurface(
                    desktop,
                    interactive = false,
                    preview = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.Computer,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = CodeColors.muted,
                )
            }
        }
    }
}

/** What the opening is doing, under the bar that says how far along it is. */
@Composable
private fun OpeningNote(progress: BoxProgress) {
    Text(
        "${progress.phase} · ${openingLine(progress)}",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

/**
 * Closed, with work on the screen behind it.
 *
 * One line and one word. The strip above already says whose box this is and the LED already says
 * it is off, so all this owes anybody is the reason — a row that only ever says "closed" turns a
 * failure into a button that appears to do nothing when pressed twice — and the way back on.
 */
@Composable
private fun ClosedNote(state: BoxUiState, onOpen: () -> Unit) {
    val failure = (state.runtimeState as? RuntimeState.Failed)?.reason
    // A box that was put away is not off; it is exactly where it was left, and reopening it costs
    // about a second rather than a boot. Saying "closed" for both is the difference between an
    // errand and a commitment, so the row says which one this is.
    val putAway = state.runtimeState == RuntimeState.Suspended

    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                failure != null -> failure.message
                putAway -> "Paused, just as you left it."
                else -> "Closed. Nothing is running."
            },
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onOpen) { Text(if (failure != null) "Try again" else "Open") }
    }
}

/** The identity strip: tall enough for a 40dp icon button with air around it. */
private val STRIP_HEIGHT = 52.dp

/** The hairline under the strip while the box opens. */
private val OPENING_BAR_HEIGHT = 2.dp

/** How far the screen is held off the edges of the column. */
private val SCREEN_INSET = 12.dp

/**
 * How tall the screen is, given the width it has.
 *
 * The guest is 1280×800, so 16:10 is the shape that letterboxes least — [DesktopSurface] fits the
 * picture into whatever it is given, and bars on a minimap are the part of it that isn't the
 * machine. Capped because "as large as it can be" is the *column's* answer, not the phone's: a
 * 411dp-wide home screen would otherwise spend a third of itself on the picture before the task
 * list got a row.
 */
private fun screenHeight(width: Dp): Dp = (width / SCREEN_ASPECT).coerceIn(90.dp, 220.dp)

private const val SCREEN_ASPECT = 1.6f

/**
 * How tall the header is once it has settled. The caller animates down to this, and has to be told
 * rather than left to measure: [SessionsPane] animates a `height`, and a height that is whatever
 * the content turns out to be cannot be animated to.
 *
 * Every number that goes into it is a constant in this file, so the header and the space made for
 * it cannot drift apart — the old version was a single 116dp constant with a comment explaining
 * which of the row's four states it had been sized for, and that state was the one that fitted.
 */
@Composable
fun settledBoxHeight(state: BoxUiState, width: Dp): Dp = when (state.boxStage) {
    BoxStage.Open -> STRIP_HEIGHT + screenHeight(width - SCREEN_INSET * 2) + SCREEN_INSET * 2
    BoxStage.Working -> STRIP_HEIGHT + OPENING_BAR_HEIGHT + 32.dp
    // Two lines of reason, because a failure's own message is the widest thing this ever says.
    BoxStage.Closed -> STRIP_HEIGHT + 52.dp
}

/** Long enough to read as the panel moving rather than the screen changing. */
const val SETTLE_MILLIS = 520

/**
 * The opening, recomputed once a second while anyone is waiting on it.
 *
 * The clock is [SystemClock.elapsedRealtime], not wall time: a phone that syncs its clock mid-boot
 * would otherwise jump the bar, and opening a box takes long enough for that to happen.
 */
@Composable
fun rememberBoxProgress(state: BoxUiState): BoxProgress {
    val working = state.boxStage == BoxStage.Working
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(working) {
        while (working) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }

    return remember(state.runtimeState, state.openingSince, state.expectedOpenMillis, now) {
        BoxProgress.of(
            state = state.runtimeState,
            elapsedMillis = state.openingSince?.let { (now - it).coerceAtLeast(0L) },
            expectedMillis = state.expectedOpenMillis,
        )
    }
}
