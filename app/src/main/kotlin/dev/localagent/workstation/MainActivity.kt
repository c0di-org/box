package dev.localagent.workstation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.localagent.workstation.ui.BoxApp
import dev.localagent.workstation.ui.BoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BoxRoot() }
    }
}

@Composable
private fun BoxRoot(boxViewModel: BoxViewModel = viewModel()) {
    val state by boxViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("box_product", Context.MODE_PRIVATE)
    }
    var pendingPermissionAction by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // A denied notification permission does not block the user-owned foreground runtime.
        when (pendingPermissionAction) {
            "setup" -> boxViewModel.setupAndStart()
            "start" -> boxViewModel.start()
        }
        pendingPermissionAction = null
    }

    val withNotificationPermission: (String) -> Unit = { action ->
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !preferences.getBoolean("notification_permission_requested", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            preferences.edit().putBoolean("notification_permission_requested", true).apply()
            pendingPermissionAction = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            when (action) {
                "setup" -> boxViewModel.setupAndStart()
                "start" -> boxViewModel.start()
            }
        }
    }

    BoxTheme {
        BoxApp(
            state = state,
            onDestinationSelected = boxViewModel::selectDestination,
            onSelectSession = boxViewModel::selectSession,
            onToggleHarness = boxViewModel::toggleHarness,
            onNewConversation = { harnessId -> boxViewModel.startSession(harnessId) },
            onSend = boxViewModel::sendMessage,
            onInterrupt = boxViewModel::interruptSession,
            onPermissionDecision = boxViewModel::resolvePermission,
            onOpenArtifact = { artifact ->
                boxViewModel.openArtifact(
                    when (artifact) {
                        dev.localagent.workstation.agent.Artifact.Computer -> "The live desktop"
                        is dev.localagent.workstation.agent.Artifact.Preview -> "The preview"
                    },
                )
            },
            onCloseSession = boxViewModel::closeSession,
            onSelectComputerTool = boxViewModel::selectComputerTool,
            onSetupAndStart = { withNotificationPermission("setup") },
            onStart = { withNotificationPermission("start") },
            onStop = boxViewModel::stop,
            onRetry = boxViewModel::retry,
            onRunCommand = boxViewModel::runCommand,
            onOpenDirectory = boxViewModel::openDirectory,
            onNavigateUp = boxViewModel::navigateUp,
            onRefreshFiles = boxViewModel::refreshFiles,
            onOpenFile = boxViewModel::openFile,
            onCloseFile = boxViewModel::closeFile,
            onNoticeShown = boxViewModel::noticeShown,
        )
    }
}
