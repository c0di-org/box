package dev.localagent.workstation.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.workstation.agent.HarnessAccountState
import dev.localagent.workstation.agent.HarnessControls

/** Account/model UI for whichever selected harness advertised those capabilities. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessSettingsSheet(controller: HarnessControls, onDismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    if (!state.visible) return
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) { Icon(Icons.Outlined.Lock, null, Modifier.padding(9.dp).size(20.dp)) }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.harnessName ?: "Agent", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Account and model settings stay inside this box.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(22.dp))

            Text("Account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            when (val account = state.account) {
                HarnessAccountState.Unknown -> Text("Checking the box…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                HarnessAccountState.SignedOut -> Button(onClick = controller::beginSignIn, enabled = !state.loading) {
                    Text("Sign in with ChatGPT")
                }
                is HarnessAccountState.SignedIn -> {
                    Text(account.account ?: "Signed in", fontWeight = FontWeight.Medium)
                    account.plan?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = controller::signOut, enabled = !state.loading) { Text("Sign out") }
                }
                is HarnessAccountState.DeviceCode -> DeviceCode(
                    account.verificationUrl,
                    account.userCode,
                    context,
                    controller::cancelLogin,
                )
                is HarnessAccountState.Failed -> {
                    Text(account.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = controller::beginSignIn) { Text("Try again") }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Model", style = MaterialTheme.typography.titleMedium)
            Text(
                "Available models come from the selected harness. Default follows its own configuration.",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            ModelRow(
                label = "Harness default",
                summary = "No Box override",
                selected = state.selectedModel == null,
                enabled = !state.loading,
                onClick = { controller.selectModel(null) },
            )
            state.models.forEach { model ->
                ModelRow(
                    label = model.label,
                    summary = model.summary,
                    selected = state.selectedModel == model.id,
                    enabled = !state.loading,
                    onClick = { controller.selectModel(model.id) },
                )
            }
            state.error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DeviceCode(url: String, code: String, context: Context, onCancel: () -> Unit) {
    Text("Open this verification page, then enter the one-time code:")
    Spacer(Modifier.height(8.dp))
    Text(url, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(code, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Sign-in code", code))
            }) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Copy code")
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                val parsed = runCatching { Uri.parse(url) }.getOrNull()
                if (parsed?.scheme?.lowercase() in setOf("http", "https")) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, parsed).addCategory(Intent.CATEGORY_BROWSABLE),
                        )
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
            Spacer(Modifier.size(7.dp))
            Text("Open page")
        }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
    Spacer(Modifier.height(8.dp))
    Text("Waiting for ChatGPT…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModelRow(label: String, summary: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                if (summary.isNotBlank()) Text(summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Outlined.Check, "In use", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
