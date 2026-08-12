package dev.localagent.workstation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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

/**
 * The one step of signing in that has to leave Box.
 *
 * The guest has no browser, so Claude's authorisation page is handed to the phone's. The scheme is
 * checked rather than trusted: the URL is lifted out of another program's output, and `ACTION_VIEW`
 * on an arbitrary scheme is a way to reach components that were never meant to be reachable here.
 */
private fun openInBrowser(context: Context, url: String) {
    val parsed = runCatching { Uri.parse(url) }.getOrNull()
    if (parsed?.scheme?.lowercase() !in setOf("http", "https")) {
        Toast.makeText(context, "That sign-in link could not be opened.", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, parsed)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "No browser to open the sign-in page.", Toast.LENGTH_LONG).show()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BoxRoot() }
    }
}

@Composable
private fun BoxRoot(boxViewModel: BoxViewModel = viewModel(factory = BoxContainer.factory)) {
    val state by boxViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("box_product", Context.MODE_PRIVATE)
    }
    var openWhenPermissionAnswered by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // A denied notification permission does not block the user-owned foreground runtime.
        if (openWhenPermissionAnswered) boxViewModel.openBox()
        openWhenPermissionAnswered = false
    }

    /**
     * Asked once, on the way into the only three-minute wait Box has. Notifications are how the
     * user finds out the box finished while they were in another app, which is exactly the offer
     * worth making at the moment they press the button.
     */
    val openBox: () -> Unit = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !preferences.getBoolean("notification_permission_requested", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            preferences.edit().putBoolean("notification_permission_requested", true).apply()
            openWhenPermissionAnswered = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            boxViewModel.openBox()
        }
    }

    BoxTheme {
        BoxApp(
            state = state,
            onDestinationSelected = boxViewModel::selectDestination,
            onSelectSession = boxViewModel::selectSession,
            onNewConversation = { harnessId -> boxViewModel.startSession(harnessId) },
            onSend = boxViewModel::sendMessage,
            onInterrupt = boxViewModel::interruptSession,
            onStopSubAgent = boxViewModel::interruptSubAgent,
            onPermissionDecision = boxViewModel::resolvePermission,
            onOpenArtifact = { artifact ->
                when (artifact) {
                    dev.localagent.workstation.agent.Artifact.Computer -> boxViewModel.openComputer()
                    is dev.localagent.workstation.agent.Artifact.Preview ->
                        boxViewModel.openPreview("The preview")
                }
            },
            onCloseSession = boxViewModel::closeSession,
            onSelectComputerPanel = boxViewModel::selectComputerPanel,
            onOpenBox = openBox,
            onStop = boxViewModel::stop,
            onRunCommand = boxViewModel::runCommand,
            onSelectFilesPlace = boxViewModel::selectFilesPlace,
            onOpenDirectory = boxViewModel::openDirectory,
            onNavigateUp = boxViewModel::navigateUp,
            onRefreshFiles = boxViewModel::refreshFiles,
            onOpenFile = boxViewModel::openFile,
            onCloseFile = boxViewModel::closeFile,
            onOpenInPhoneFiles = boxViewModel::openSharedInPhoneFiles,
            onNoticeShown = boxViewModel::noticeShown,
            onDismissGreeting = boxViewModel::dismissReadyGreeting,
            desktop = BoxContainer.desktop(LocalContext.current.applicationContext as android.app.Application),
            onSetDesktopControl = boxViewModel::setDesktopControl,
            onShowSignIn = boxViewModel::showSignIn,
            onDismissSignIn = boxViewModel::dismissSignIn,
            onBeginSignIn = boxViewModel::beginSignIn,
            onOpenSignInUrl = { url -> openInBrowser(context, url) },
            onSubmitSignInCode = boxViewModel::submitSignInCode,
            onCancelSignIn = boxViewModel::cancelSignIn,
            onSetPermissionMode = boxViewModel::setPermissionMode,
        )
    }
}
