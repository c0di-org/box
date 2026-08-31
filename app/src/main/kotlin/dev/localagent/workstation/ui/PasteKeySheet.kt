package dev.localagent.workstation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.workstation.CredentialRequest

/**
 * Somewhere to paste a key.
 *
 * The gap this fills: a harness that needs a secret used to say *"put a DeepSeek API key in
 * `/workspace/.config/box/deepseek-api-key` from the Box terminal, then send the task again"* — an
 * accurate sentence, and an impossible instruction for somebody holding a phone. The phone's
 * clipboard has no route into the guest's X session at all, and the on-screen keyboard turns taps
 * into X11 keysyms one at a time, so following it meant typing a thirty-character secret by hand
 * onto a hand-drawn keyboard. The one thing they already had was the key, one tap from being
 * pasted, and there was nowhere to paste it.
 *
 * Box had already solved this twice — `SignInSheet` for Claude, `ConnectGitHubSheet` for GitHub —
 * and this is the third of the same shape rather than a fourth idea.
 *
 * **Why a sheet and not the composer.** Text sent through the composer becomes a `prompt`, which
 * the harness echoes back as `user_message`: appended to the session log on the workspace disk,
 * replayed in full every time the task is opened, and drawn as a chat bubble from then on. A key
 * pasted there is a key stored in the clear and shown on screen forever. The value here travels on
 * the non-echoed stdin channel instead, which is the same distinction the Claude harness draws
 * around `auth_code`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteKeySheet(
    request: CredentialRequest,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var value by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    request.ask.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                request.ask.help
                    ?: "Paste it here and this task will carry on where it left off.",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                    singleLine = true,
                    enabled = !request.saving,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                    ),
                    // Obscured, because this is drawn on a phone that is often held in a room with
                    // other people in it. Paste still works; nothing here reads the clipboard.
                    visualTransformation = PasswordVisualTransformation(),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        // `Password` so the keyboard offers no autocorrect, no capitalisation and
                        // no suggestion strip — a key is not a word, and a keyboard that learns it
                        // would go on offering it in other apps.
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (value.isNotBlank()) onSubmit(value) },
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !request.saving) { Text("Not now") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { if (value.isNotBlank()) onSubmit(value) },
                    enabled = value.isNotBlank() && !request.saving,
                ) {
                    if (request.saving) {
                        // Still open, and still saying "Saving". The sheet closes when the guest
                        // says it wrote the file, not when Box finished handing the bytes over —
                        // those are different facts, and only one of them is the user's.
                        CircularProgressIndicator(
                            Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(9.dp))
                        Text("Saving…")
                    } else {
                        Text("Save")
                    }
                }
            }
        }
    }
}
