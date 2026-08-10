package dev.localagent.workstation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import dev.localagent.runtime.qemu.RuntimeService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LocalAgentTheme { WorkstationApp() } }
    }
}

private data class ChatMessage(val author: String, val body: String, val isUser: Boolean)

@Composable
private fun WorkstationApp() {
    val messages = remember {
        mutableStateListOf(
            ChatMessage("Agent", "Hi — I’m ready when you are. I can use a private computer on this phone for code and files.", false),
        )
    }
    var showComputer by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp
        if (isWide && showComputer) {
            Row(Modifier.fillMaxSize()) {
                ChatPane(
                    messages = messages,
                    onOpenComputer = { showComputer = true },
                    modifier = Modifier.weight(0.94f),
                )
                ComputerPane(
                    onClose = { showComputer = false },
                    modifier = Modifier.weight(1.06f).fillMaxHeight(),
                )
            }
        } else if (showComputer) {
            ComputerPane(onClose = { showComputer = false }, modifier = Modifier.fillMaxSize())
        } else {
            ChatPane(messages, { showComputer = true }, Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPane(
    messages: MutableList<ChatMessage>,
    onOpenComputer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    fun submit() {
        val question = draft.trim()
        if (question.isEmpty()) return
        messages += ChatMessage("You", question, true)
        messages += ChatMessage(
            "Agent",
            "The private Linux VM can start on this phone. I’ll be able to use it for commands and files once the custom Debian image with its guest agent is provisioned.",
            false,
        )
        draft = ""
    }

    Column(
        modifier
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Local Agent", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Your AI’s private computer", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            IconButton(onClick = onOpenComputer) {
                Icon(Icons.Outlined.Computer, contentDescription = "Open computer")
            }
            IconButton(onClick = { }) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
            }
        }

        if (messages.size == 1) {
            Spacer(Modifier.weight(1f))
            Text("New chat", fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Ask me anything — I’ll bring in your local computer when it’s useful.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(messages, key = { "${it.author}:${it.body}" }) { message ->
                    MessageBubble(message)
                }
                item {
                    TextButton(onClick = onOpenComputer) {
                        Icon(Icons.Outlined.Computer, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open computer")
                    }
                }
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().imePadding().padding(bottom = 16.dp),
            placeholder = { Text("Ask me anything…") },
            trailingIcon = {
                IconButton(onClick = ::submit, enabled = draft.isNotBlank()) {
                    Icon(Icons.Outlined.Send, "Send")
                }
            },
            shape = RoundedCornerShape(20.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            singleLine = false,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
    ) {
        Text(message.author, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp))
        Surface(
            color = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Text(message.body, Modifier.padding(14.dp), lineHeight = 21.sp)
        }
    }
}

@Composable
private fun ComputerPane(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Computer", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Private Linux VM", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            TextButton(onClick = onClose) { Text("Back to chat") }
        }
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp)) {
                Text("Private Linux runtime", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text("Start the private QEMU-backed Linux VM. It uses no Android shell access. The command and file workspace becomes available after the custom Debian guest agent image is provisioned.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        context.startService(Intent(context, RuntimeService::class.java).apply {
                            action = RuntimeService.ACTION_START
                        })
                    },
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text("Start private computer")
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Stock runtime • ARM64 • private workspace", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

private val LocalColors = ColorScheme(
    primary = Color(0xFF2F5D47), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EBDD), onPrimaryContainer = Color(0xFF123523),
    inversePrimary = Color(0xFFBDECC9), secondary = Color(0xFF506355), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E8D7), onSecondaryContainer = Color(0xFF0D2114),
    tertiary = Color(0xFF3B6470), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBEEAF7), onTertiaryContainer = Color(0xFF001F27),
    background = Color(0xFFF9FAF7), onBackground = Color(0xFF1A1C1A),
    surface = Color(0xFFF9FAF7), onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFDEE5DD), onSurfaceVariant = Color(0xFF424941),
    surfaceTint = Color(0xFF2F5D47), inverseSurface = Color(0xFF2F312E), inverseOnSurface = Color(0xFFF0F1ED),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF727970), outlineVariant = Color(0xFFC2C9C0), scrim = Color.Black,
)

@Composable
private fun LocalAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LocalColors, content = content)
}

@Preview(widthDp = 411, heightDp = 891)
@Composable
private fun PhonePreview() = LocalAgentTheme { WorkstationApp() }
