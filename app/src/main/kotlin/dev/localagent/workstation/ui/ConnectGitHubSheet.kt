package dev.localagent.workstation.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.workstation.agent.GitHubAuth
import kotlinx.coroutines.delay

/**
 * Connecting the box to GitHub, in as few decisions as this can honestly be reduced to.
 *
 * The shape of the screen follows from where the code travels. Claude's sign-in has to ask for
 * something back, so it is a form; this one hands something *out*, so it is a card with a code on
 * it and one button. Nothing here is typed unless the person chooses the escape hatch — the code
 * is on the clipboard before they have finished reading it, and the link carries it as well, so
 * the likely path from opening this sheet to being connected involves no keyboard at all.
 *
 * Two steps are shown as two steps, deliberately. Authorising and choosing repositories are
 * genuinely different questions — who you are, and what this box may touch — and collapsing them
 * into one progress bar would hide the second, which is the one that is actually about trust.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectGitHubSheet(
    state: GitHubAuth.State,
    computerReady: Boolean,
    /** The agent's words for why, when an agent is the one waiting. */
    reason: String?,
    agentWaiting: Boolean,
    onConnect: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRepositoriesChosen: () -> Unit,
    onSubmitToken: (String) -> Unit,
    onDecline: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Whether this sheet has watched a connection happen, as opposed to having been opened on a box
    // that was already connected. Only the first of those should close itself.
    var sawFlow by remember { mutableStateOf(false) }
    if (state is GitHubAuth.State.Starting ||
        state is GitHubAuth.State.AwaitingApproval ||
        state is GitHubAuth.State.ChoosingRepositories
    ) {
        sawFlow = true
    }
    val finished = state is GitHubAuth.State.Connected && !state.needsRepositories
    LaunchedEffect(finished, sawFlow) {
        // Long enough to see that it worked, short enough that nobody has to dismiss a screen whose
        // only remaining content is a tick. The agent is mid-turn behind this sheet, and the best
        // version of the moment is the conversation coming back on its own with the work resumed.
        if (finished && sawFlow) {
            delay(1_100)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 26.dp)) {
            Header(state, reason)
            Spacer(Modifier.height(20.dp))

            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "connect-github",
            ) { current ->
                when (current) {
                    is GitHubAuth.State.AwaitingApproval -> ApproveBody(current, onOpenUrl, onSubmitToken)

                    is GitHubAuth.State.ChoosingRepositories -> ChooseBody(
                        url = current.url,
                        onOpenUrl = onOpenUrl,
                        onDone = onRepositoriesChosen,
                    )

                    is GitHubAuth.State.Connected ->
                        if (current.needsRepositories) {
                            // Connected to an account and able to reach nothing. Not a success, and
                            // not a failure either — one step of two, offered again.
                            ChooseBody(url = null, onOpenUrl = onOpenUrl, onDone = onConnect)
                        } else {
                            ConnectedBody(current, onDisconnect)
                        }

                    GitHubAuth.State.Starting -> WaitingBody("Getting a code…")
                    GitHubAuth.State.Checking -> WaitingBody("Checking…")

                    GitHubAuth.State.Unconfigured -> TokenBody(
                        note = "This build of Box has no GitHub App, so a token you make yourself is the way in.",
                        onSubmitToken = onSubmitToken,
                        alwaysOpen = true,
                    )

                    is GitHubAuth.State.Failed -> FailedBody(current, computerReady, onConnect)

                    GitHubAuth.State.Disconnected, GitHubAuth.State.Unknown ->
                        StartBody(computerReady, onConnect)
                }
            }

            if (agentWaiting && !finished) {
                Spacer(Modifier.height(6.dp))
                // The only way to say no. Closing the sheet deliberately does not answer for them —
                // an agent that is waiting should hear a decision, not a dismissal.
                TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
                    Text("Not now", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Header(state: GitHubAuth.State, reason: String?) {
    val (icon, tint) = when {
        state is GitHubAuth.State.Connected && !state.needsRepositories ->
            Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
        state is GitHubAuth.State.Failed ->
            Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        else -> Icons.AutoMirrored.Outlined.CallMerge to MaterialTheme.colorScheme.tertiary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = tint.copy(alpha = 0.14f)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(9.dp).size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                when {
                    state is GitHubAuth.State.Connected && !state.needsRepositories -> "GitHub connected"
                    state is GitHubAuth.State.Failed -> "That didn’t finish"
                    else -> "Connect GitHub"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                when {
                    // Past the first step, the useful sentence is not why they are here — it is
                    // that the half they have already done worked. Without it, a second browser
                    // trip reads as the first one having failed.
                    state is GitHubAuth.State.ChoosingRepositories -> "Signed in as @${state.login}"
                    // The agent's own reason when there is one: it is the only explanation the
                    // person gets before deciding, and more use than anything written in advance.
                    else -> reason ?: "Stays in your box. Only the repositories you pick."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StartBody(computerReady: Boolean, onConnect: () -> Unit) {
    Column {
        Button(onClick = onConnect, enabled = computerReady, modifier = Modifier.fillMaxWidth()) {
            Text(if (computerReady) "Connect" else "Waiting for your box…")
        }
        Spacer(Modifier.height(10.dp))
        Quiet("Your box gets a code to enter at GitHub. Nothing is typed here.")
    }
}

/**
 * The code, and the one thing to do with it.
 *
 * Copied to the clipboard the moment it appears, and again on a tap. That is not a convenience so
 * much as an admission: eight characters transcribed by hand between two apps is the step people
 * get wrong, and the link below carries the code as well — so the intended path is a tap, a
 * confirmation at GitHub, and back.
 */
@Composable
private fun ApproveBody(
    state: GitHubAuth.State.AwaitingApproval,
    onOpenUrl: (String) -> Unit,
    onSubmitToken: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var justCopied by remember(state.userCode) { mutableStateOf(true) }

    LaunchedEffect(state.userCode) {
        clipboard.setText(AnnotatedString(state.userCode))
        delay(2_400)
        justCopied = false
    }

    Column {
        CodeCells(
            code = state.userCode,
            onCopy = {
                clipboard.setText(AnnotatedString(state.userCode))
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                justCopied = true
            },
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth()) {
            Quiet(
                if (justCopied) "Copied — paste it at GitHub" else "Tap the code to copy it again",
                Modifier.align(Alignment.Center),
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(onClick = { onOpenUrl(state.url) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open GitHub")
        }

        Spacer(Modifier.height(16.dp))
        Waiting("Waiting for you at GitHub")

        Expiry(state.expiresAtElapsedRealtime)

        Spacer(Modifier.height(4.dp))
        TokenBody(note = null, onSubmitToken = onSubmitToken, alwaysOpen = false)
    }
}

/**
 * One box per character.
 *
 * A code is not a word, and setting it as one invites the eye to read it rather than copy it.
 * Cells make each character its own thing to check, which is what somebody comparing this screen
 * against a browser field is actually doing.
 */
@Composable
private fun CodeCells(code: String, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onCopy),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        code.forEach { character ->
            if (character == '-') {
                // GitHub's own grouping, kept: it is how the code is written everywhere else.
                Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(1.dp),
                    ) { Box(Modifier.width(8.dp).height(2.dp)) }
                }
            } else {
                Surface(
                    modifier = Modifier.padding(horizontal = 3.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                ) {
                    Text(
                        character.toString(),
                        Modifier.padding(horizontal = 9.dp, vertical = 13.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Choosing what the box can reach, which is the step that is actually about trust. */
@Composable
private fun ChooseBody(url: String?, onOpenUrl: (String) -> Unit, onDone: () -> Unit) {
    Column {
        Text(
            "Now pick what this box can see.",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Quiet("Choose repositories at GitHub. Box can only reach the ones you pick, and you can change it later.")
        Spacer(Modifier.height(16.dp))
        if (url != null) {
            Button(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Choose repositories")
            }
            Spacer(Modifier.height(12.dp))
            Waiting("Waiting for you to choose")
        } else {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Choose repositories") }
        }
    }
}

@Composable
private fun ConnectedBody(state: GitHubAuth.State.Connected, onDisconnect: () -> Unit) {
    Column {
        Text(
            "@${state.login}",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Quiet(
            when {
                state.stale -> "Connected. Box couldn’t reach GitHub just now to check."
                state.repositories == null -> "git and gh are signed in here."
                else -> "${state.repositories} ${if (state.repositories == 1) "repository" else "repositories"} · git and gh are signed in here."
            },
        )
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
        Spacer(Modifier.height(8.dp))
        // Said plainly because it is easy to believe otherwise: removing the copy in this box is
        // not the same as withdrawing the access, and only one of those can be done from here.
        Quiet("Disconnecting removes the credential from this box. To withdraw access entirely, revoke it in your GitHub settings.")
    }
}

@Composable
private fun FailedBody(state: GitHubAuth.State.Failed, computerReady: Boolean, onConnect: () -> Unit) {
    Column {
        Text(state.message, style = MaterialTheme.typography.bodyLarge)
        state.detail?.let {
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Text(
                    it,
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onConnect, enabled = computerReady, modifier = Modifier.fillMaxWidth()) {
            Text("Try again")
        }
    }
}

/**
 * A token the person made themselves.
 *
 * Folded away, because it is the wrong path for almost everybody: a token pasted by hand is one
 * nobody scoped to a few repositories, and typing a secret on a phone is unpleasant enough that
 * offering it first would make the flow look harder than it is. It exists for GitHub Enterprise
 * and for people who would rather mint their own — and it takes the token straight to the guest,
 * never through the conversation, where it would live in a log for ever.
 */
@Composable
private fun TokenBody(note: String?, onSubmitToken: (String) -> Unit, alwaysOpen: Boolean) {
    var open by remember { mutableStateOf(alwaysOpen) }
    var token by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        if (note != null) {
            Quiet(note)
            Spacer(Modifier.height(12.dp))
        }
        if (!open) {
            TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Use a token instead", style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        ) {
            BasicTextField(
                value = token,
                onValueChange = { token = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (token.isNotBlank()) onSubmitToken(token) }),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onSubmitToken(token) },
            enabled = token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Use this token") }
    }
}

@Composable
private fun WaitingBody(label: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // The same eight cells the code will land in, empty. Nothing moves when it arrives, which
        // is the difference between a screen that fills in and a screen that jumps.
        CodeCells(code = "        ", onCopy = {})
        Spacer(Modifier.height(14.dp))
        Waiting(label)
    }
}

/** A breathing dot: something is happening, and nothing is being asked of you yet. */
@Composable
private fun Waiting(label: String) {
    val transition = rememberInfiniteTransition(label = "waiting")
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(7.dp).alpha(pulse),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {}
        Spacer(Modifier.width(8.dp))
        Quiet(label)
    }
}

/**
 * How long the code has left, and only once that has become a real question.
 *
 * A countdown running from the first second turns a fifteen minute window into a timed test.
 * Below five minutes it stops being decoration and starts being the reason the code will not work,
 * which is worth saying before it happens rather than after.
 */
@Composable
private fun Expiry(expiresAtElapsedRealtime: Long) {
    val remaining by produceState(initialValue = Long.MAX_VALUE, expiresAtElapsedRealtime) {
        while (true) {
            value = expiresAtElapsedRealtime - SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    if (remaining > 5 * 60_000L || remaining <= 0L) return
    val minutes = remaining / 60_000L
    val seconds = (remaining % 60_000L) / 1_000L
    val urgency by animateColorAsState(
        if (remaining < 60_000L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "expiry",
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "This code expires in %d:%02d".format(minutes, seconds),
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = urgency,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Quiet(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
