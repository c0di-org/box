package dev.localagent.workstation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.workstation.agent.GuestAuth

/**
 * Signing in to Claude on a phone, for a Linux computer that has no browser.
 *
 * The whole exchange is a hand-off: Claude Code's own `auth login` runs in the guest, Box lifts the
 * URL out of its output and opens it in the phone's browser, and the code the user comes back with
 * is typed into that still-running process. Box never sees the credential — Claude Code writes it
 * inside the guest — which is why this screen asks for a short code and never for a password.
 *
 * The raw output is shown throughout, and that is a deliberate design choice rather than a debug
 * affordance. Box matches a URL, not a sentence, because the CLI's wording moves between versions;
 * when it says something this screen has no template for, the user can still read what it said and
 * act on it instead of facing a dead end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInSheet(
    state: GuestAuth.State,
    computerReady: Boolean,
    onBegin: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 26.dp)) {
            SignInHeader(state)
            Spacer(Modifier.height(18.dp))

            when (state) {
                is GuestAuth.State.AwaitingCode -> AwaitingCodeBody(
                    url = state.url,
                    transcript = state.transcript,
                    onOpenUrl = onOpenUrl,
                    onSubmitCode = onSubmitCode,
                    onCancel = onCancel,
                )

                GuestAuth.State.Starting -> WaitingBody(
                    "Asking Claude Code for a sign-in link. On a freshly booted computer this " +
                        "takes a few seconds.",
                    onCancel = onCancel,
                )

                GuestAuth.State.Checking -> WaitingBody("Checking whether you're already signed in.", null)

                is GuestAuth.State.SignedIn -> SignedInBody(state.account, onDismiss)

                is GuestAuth.State.Failed -> FailedBody(
                    message = state.message,
                    computerReady = computerReady,
                    onRetry = onBegin,
                )

                GuestAuth.State.SignedOut, GuestAuth.State.Unknown -> StartBody(
                    computerReady = computerReady,
                    onBegin = onBegin,
                )
            }
        }
    }
}

@Composable
private fun SignInHeader(state: GuestAuth.State) {
    val (icon, tint) = when (state) {
        is GuestAuth.State.SignedIn -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
        is GuestAuth.State.Failed -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        else -> Icons.Outlined.Lock to MaterialTheme.colorScheme.tertiary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = tint.copy(alpha = 0.14f)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(9.dp).size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                when (state) {
                    is GuestAuth.State.SignedIn -> "Signed in"
                    is GuestAuth.State.Failed -> "Sign-in didn't finish"
                    else -> "Sign in to Claude"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Your credential stays on this phone, inside the computer.",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StartBody(computerReady: Boolean, onBegin: () -> Unit) {
    Column {
        Text(
            "Box will open Claude's sign-in page in your browser. Approve it there, then bring the " +
                "code back here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onBegin, enabled = computerReady, modifier = Modifier.fillMaxWidth()) {
            Text(if (computerReady) "Start sign-in" else "Waiting for the computer…")
        }
        if (!computerReady) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Claude Code runs inside the computer, so it has to be up before it can log in. " +
                    "Box is starting it now.",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AwaitingCodeBody(
    url: String,
    transcript: String,
    onOpenUrl: (String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    Column {
        Text(
            "1. Approve Box in your browser",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Open the sign-in page")
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "2. Paste the code you get back",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        ) {
            BasicTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                maxLines = 3,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (code.isNotBlank()) {
                        submitted = true
                        onSubmitCode(code)
                    }
                }),
                decorationBox = { inner ->
                    Box {
                        if (code.isEmpty()) {
                            Text(
                                "Paste the code here",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    submitted = true
                    onSubmitCode(code)
                },
                enabled = code.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Finish signing in")
            }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
        if (submitted) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Sent. Claude Code is finishing up inside the computer.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Transcript(transcript)
    }
}

/**
 * What the CLI actually said.
 *
 * Box parses this output loosely on purpose, so the honest thing is to show it. If a version says
 * something Box has no answer for, the user is looking at the real text rather than at Box's guess
 * about it.
 */
@Composable
private fun Transcript(transcript: String) {
    if (transcript.isBlank()) return
    Text(
        "What the computer said",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Box(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
            Text(
                transcript.trim(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaitingBody(message: String, onCancel: (() -> Unit)?) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onCancel != null) {
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun SignedInBody(account: String?, onDismiss: () -> Unit) {
    Column {
        Text(
            account?.let { "Signed in as $it. Agents can start work now." }
                ?: "Claude Code is signed in. Agents can start work now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun FailedBody(message: String, computerReady: Boolean, onRetry: () -> Unit) {
    Column {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
        ) {
            Text(
                message,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, enabled = computerReady, modifier = Modifier.fillMaxWidth()) {
            Text("Try again")
        }
    }
}
