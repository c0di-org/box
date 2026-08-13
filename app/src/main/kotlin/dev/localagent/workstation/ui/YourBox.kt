package dev.localagent.workstation.ui

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import dev.localagent.workstation.computer.DesktopTransport
import kotlinx.coroutines.delay

/**
 * Your box, at whatever size the moment deserves.
 *
 * One component and not five screens on purpose. The box is the only thing Box has — both of the
 * things you can do are things you do *inside* it — so it holds the window until it is open, and
 * then becomes the first row of the task list. The user never dismisses anything and is never sent
 * somewhere; the shrink *is* the arrival, which is why the caller owns the height and animates it.
 *
 * Four states, and each one is only allowed to say what is true at that moment:
 *
 * - **Closed** — a mark and a button, or the same offer as a row once there are tasks to see
 *   behind it.
 * - **Opening** — the ring, what it is doing, how long is left, and something worth doing with the
 *   wait: the first task can be typed now and is sent the moment the guest can take it.
 * - **Just opened, once ever** — the arrival gets the window exactly one time in the life of an
 *   install, and spends it on the two ways to use this thing, or on the sign-in that has to happen
 *   before one of them works.
 * - **Open** — a row carrying the machine's own live screen, which opens the computer.
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
    onDismissGreeting: () -> Unit,
    onShowDetails: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = Triple(state.boxStage, full, state.readyGreeting),
        animationSpec = tween(SETTLE_MILLIS / 2),
        label = "your box",
        modifier = modifier,
    ) { (stage, owns, greeting) ->
        when {
            stage == BoxStage.Closed && owns -> ClosedHero(state = state, onOpen = onOpen)

            stage == BoxStage.Closed -> ClosedRow(state = state, onOpen = onOpen)

            stage == BoxStage.Working && owns -> OpeningHero(
                progress = progress,
                canType = state.harnesses.isNotEmpty(),
                // Everything typed into the wait, held because there is nobody signed in yet. A
                // message that is *not* held never reaches this screen: sending one opens the task
                // it belongs to and the conversation takes the window.
                waiting = state.heldForSignIn.map { it.text },
                onSend = onSendFirstTask,
                onWatch = onOpenComputer,
            )

            stage == BoxStage.Working -> OpeningRow(progress)

            greeting && owns -> ReadyHero(
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
                // The greeting deliberately stays up behind the sheet. Signing in is a step of the
                // arrival, not a way out of it — and when it lands, this screen is holding the two
                // doors that were the point of it.
                onSignIn = onSignIn,
            )

            else -> ComputerRow(
                state = state,
                desktop = desktop,
                onOpenComputer = onOpenComputer,
                onShowDetails = onShowDetails,
            )
        }
    }
}

/**
 * The mark, with the opening drawn around it.
 *
 * Lives in the top bar as well as on the hero, so a wait that has been sent to the corner still has
 * one honest indicator on it. The ring is a clock corrected by checkpoints rather than either alone
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
 * This screen used to be nothing at all: pressing the button collapsed the hero to zero height and
 * handed back an app with no tasks, no transcript and no computer in it, so the honest three-minute
 * wait became three minutes of sitting in an empty list watching a 32dp ring. The ring was not the
 * problem — the emptiness was.
 *
 * So the box keeps the window while it opens, and the wait carries the one thing that is genuinely
 * useful now: the first task. What is typed here queues and goes the moment the guest can take it
 * ([BoxUiState.queued]), which also means the arrival is something the user can *see* — their own
 * message finally sending — rather than a row quietly appearing behind them.
 */
@Composable
private fun OpeningHero(
    progress: BoxProgress,
    canType: Boolean,
    waiting: List<String>,
    onSend: (String) -> Unit,
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
            onReview = null,
            footer = false,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onWatch) { Text("Watch it boot") }
    }
}

/**
 * The arrival, once ever.
 *
 * The only moment where saying what Box can do is free: the user is already looking at this screen,
 * waiting for exactly this. It is also the only place both doors are ever shown at the same size,
 * which is the point — the computer is not a feature of the chat.
 *
 * It is also where the sign-in belongs, and this is the only screen that can hold it. Claude's
 * handshake runs *inside* the guest, so nothing can be asked before the box is open; the moment it
 * opens is therefore the first moment the question can be put, and it is already the moment the
 * user is looking at. Before this, sign-in was a banner discovered *after* a first task had been
 * sent, failed, and asked to be typed again.
 *
 * [waiting] is that first task, if they typed one into the wait — shown here so their own words are
 * visibly still in hand rather than something they have to trust Box kept.
 */
@Composable
private fun ReadyHero(
    signInWanted: Boolean,
    waiting: List<String>,
    onChat: () -> Unit,
    onComputer: () -> Unit,
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
            DoorButton(Icons.Outlined.Forum, "Chat with an agent", onChat, primary = true)
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
 * Closed, when there is already work on this screen to look at.
 *
 * The same offer the hero makes, in the space the opening and the open box use — so coming back to
 * Box the next day is a list of what the agents did, with one row on top saying the machine is off
 * and one word to turn it back on. It was a full-window splash with all of that behind it.
 */
@Composable
private fun ClosedRow(state: BoxUiState, onOpen: () -> Unit) {
    val failure = (state.runtimeState as? RuntimeState.Failed)?.reason
    // A box that was put away is not off; it is exactly where it was left, and reopening it costs
    // about a second rather than a boot. Saying "closed" for both is the difference between an
    // errand and a commitment, so the row says which one this is.
    val putAway = state.runtimeState == RuntimeState.Suspended

    RowFrame(onClick = onOpen) {
        BoxMark(34.dp, Modifier.padding(start = 6.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    failure != null -> "Your box didn’t open"
                    putAway -> "Your box is paused"
                    else -> "Your box is closed"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                // The reason, when there is one: a row that only ever says "closed" turns a
                // failure into a button that appears to do nothing when pressed twice.
                failure?.message ?: if (putAway) "Just as you left it." else "Nothing is running.",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onOpen) { Text(if (failure != null) "Try again" else "Open") }
    }
}

/**
 * Opening, when there is already work on this screen to look at.
 *
 * Same row the open box gets, so the panel changes what it says rather than how much room it takes.
 * Nobody's tasks disappear for three minutes because the machine under them is restarting.
 */
@Composable
private fun OpeningRow(progress: BoxProgress) {
    RowFrame(onClick = null) {
        OpeningMark(progress, opening = true, size = 34.dp, modifier = Modifier.padding(start = 6.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Opening your box",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                openingLine(progress),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (progress.determinate) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = if (progress.overdue) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        Spacer(Modifier.width(14.dp))
    }
}

/**
 * The computer, on the home screen.
 *
 * It carries the live screen rather than an icon, because that picture is the product's whole claim
 * — there is a real machine in there and it is doing something — and because it is the only label
 * that never goes stale. Pressing it goes to the machine itself, not to a page about it.
 */
@Composable
private fun ComputerRow(
    state: BoxUiState,
    desktop: DesktopTransport?,
    onOpenComputer: () -> Unit,
    onShowDetails: () -> Unit,
) {
    RowFrame(onClick = onOpenComputer) {
        Box(
            Modifier
                .size(width = 96.dp, height = 60.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BoxTerminal),
            contentAlignment = Alignment.Center,
        ) {
            if (desktop != null) {
                DesktopSurface(desktop, interactive = false, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    Icons.Outlined.Computer,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = CodeColors.muted,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Computer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(9.dp))
                StatusDot(
                    MaterialTheme.colorScheme.primary,
                    7.dp,
                    Modifier.semantics { contentDescription = "Running" },
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                // Short enough to survive the 320dp rail, where this row is at its narrowest.
                if (state.agentAtWork) "Debian · in use" else "Debian",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onShowDetails) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = "Box details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RowFrame(onClick: (() -> Unit)?, content: @Composable (androidx.compose.foundation.layout.RowScope.() -> Unit)) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * How tall the box's row is once it has settled. The caller animates down to this.
 *
 * It has to be a fixed height — [RowFrame] centres its content with `fillMaxHeight`, so a row
 * given a minimum instead of an exact height expands until it has eaten the task list — which
 * means this number has to fit the *tallest* thing the row ever says, not the usual thing.
 *
 * 100dp did not, by about a pixel. The row's chrome is 40dp (14/8 outside, 12/12 inside), which
 * left 60dp for text; two lines and a subtitle need 61dp, and the three-line case — "Your box is
 * paused" wrapping in a 320dp task pane, over "Just as you left it." — was cut off through the
 * descenders of its own last line. This is that, with room to spare.
 */
val HERO_SETTLED_HEIGHT = 116.dp

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
