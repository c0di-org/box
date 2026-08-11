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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * This is one component and not two screens on purpose. Until the box is open there is very little
 * else Box can do — the agents live inside it — so opening it is not a setting hidden behind a tab,
 * it is the whole window: a mark, a sentence, and one button. As the box comes up the same panel
 * keeps the window and fills with progress. When it is finally open the panel shrinks into a row at
 * the top of the task list and the tasks arrive underneath it.
 *
 * The user never dismisses anything and is never sent somewhere. The shrink *is* the arrival, which
 * is why the caller owns the height ([HERO_SETTLED_HEIGHT]) and animates it: this composable only
 * says what belongs at each size.
 */
@Composable
fun YourBox(
    state: BoxUiState,
    progress: BoxProgress,
    desktop: DesktopTransport?,
    onOpen: () -> Unit,
    onOpenDesktop: () -> Unit,
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = state.boxStage,
        animationSpec = tween(SETTLE_MILLIS / 2),
        label = "your box",
        modifier = modifier,
    ) { stage ->
        when (stage) {
            BoxStage.Open -> SettledRow(
                state = state,
                desktop = desktop,
                onOpenDesktop = onOpenDesktop,
                onShowDetails = onShowDetails,
            )

            // Nothing. The opening moved onto the mark in the corner — see [OpeningMark] — so that
            // the three minutes are spent with the app in front of the user rather than behind a
            // screen they cannot use and cannot dismiss.
            BoxStage.Working -> Unit

            BoxStage.Closed -> ClosedHero(state = state, onOpen = onOpen)
        }
    }
}

/**
 * The mark, with the opening drawn around it.
 *
 * This is where a three-minute wait belongs: in the corner, on the thing the wait is about, next to
 * an app the user can carry on using. It replaced a full-window hero with a ring, a title and a
 * countdown, which was accurate and completely in the way.
 *
 * The ring is still the honest part — see [BoxProgress] for why it is a clock corrected by
 * checkpoints rather than either one alone — and the runtime phase it used to print lives on as the
 * spoken description, where it costs no space.
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
            val ring = Modifier.size(size + 13.dp).semantics { contentDescription = spoken }
            // Amber once it has run past what this phone usually takes. The ring is the only thing
            // on screen saying anything about the opening, so it carries "this is slower than
            // normal" as a colour — the alternative was a line of text, and text is what this
            // surface keeps losing on purpose.
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
                "Your box didn\u2019t open",
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
        // The one fact worth spending a line on, and only before the first wait of the user\u2019s life
        // with this app \u2014 three minutes is long enough that discovering it halfway through is worse
        // than being told.
        if (failure == null && state.runtimeState == RuntimeState.NotProvisioned) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Takes about three minutes the first time.",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * The box once it is open: the top row of the task list, above everything the agents are doing.
 *
 * It carries the live screen rather than an icon. That picture is the product's whole claim — there
 * is a real computer in there, and it is doing something — and a still icon would say the opposite.
 */
@Composable
private fun SettledRow(
    state: BoxUiState,
    desktop: DesktopTransport?,
    onOpenDesktop: () -> Unit,
    onShowDetails: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier
                .clickable(onClick = onOpenDesktop)
                .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            // One line, and the dot is the whole status. The row only exists while the box is
            // open, so a word saying "Running" next to a live picture of it running was the third
            // thing on this screen making the same claim.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Your box",
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
            IconButton(onClick = onShowDetails) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "Box details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How tall the box's row is once it has settled. The caller animates down to this. */
val HERO_SETTLED_HEIGHT = 100.dp

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
