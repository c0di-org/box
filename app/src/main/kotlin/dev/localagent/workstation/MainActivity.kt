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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * Everything a share sheet handed over, waiting for the composition to take it.
 *
 * A flow rather than a call into the ViewModel, because the two arrivals are not the same shape:
 * a share that launches Box comes in through `onCreate`, before anything is composed, and one that
 * reaches a running Box comes through `onNewIntent`. Both end up here, and the composition drains
 * it whenever it is ready.
 */
private val sharedIn = MutableStateFlow<List<Uri>>(emptyList())

/**
 * What another app handed to Box, or nothing if this intent was not a share.
 *
 * `EXTRA_STREAM` is read for both the single and multiple forms. Shared *text* is deliberately not
 * taken: it belongs in the composer as words, not in the box as a file, and Android already offers
 * it to the keyboard as a paste.
 */
private fun sharedUris(intent: Intent?): List<Uri> = when (intent?.action) {
    Intent.ACTION_SEND -> listOfNotNull(
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
    )
    Intent.ACTION_SEND_MULTIPLE ->
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            .orEmpty()
            .filterNotNull()
    else -> emptyList()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        take(intent)
        setContent { BoxRoot() }
    }

    /**
     * Box is `singleTask`, so a share into a running Box arrives here rather than as a second
     * instance. That launch mode is what makes the share sheet land in the conversation the user
     * already has open, instead of a fresh copy of the app on top of their own.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        val uris = sharedUris(intent)
        if (uris.isNotEmpty()) sharedIn.value = sharedIn.value + uris
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

    // Drained rather than observed: each file is taken in once, and the list is emptied as it is
    // read so a configuration change does not attach the same photograph twice.
    val shared by sharedIn.collectAsState()
    LaunchedEffect(shared) {
        if (shared.isEmpty()) return@LaunchedEffect
        sharedIn.value = emptyList()
        boxViewModel.receiveShared(shared)
    }

    /**
     * The photo picker, which needs no storage permission and shows the camera roll — the right
     * door for the case this whole feature exists for, which is a screenshot.
     */
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(boxViewModel::attach) }

    /** Everything else. The types match the share filter, so both doors accept the same things. */
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(boxViewModel::attach) }

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
                        boxViewModel.openPreview(artifact)
                    is dev.localagent.workstation.agent.Artifact.Document ->
                        boxViewModel.openDocument(artifact)
                }
            },
            // Both routes to closing — the swipe and the header menu — go the same way: the row
            // leaves, and the close itself happens when the undo snackbar does.
            onCloseSession = boxViewModel::beginClosingTask,
            onUndoCloseSession = boxViewModel::undoClosingTask,
            onCommitCloseSession = boxViewModel::commitClosingTask,
            onSelectComputerPanel = boxViewModel::selectComputerPanel,
            onOpenBox = openBox,
            onPutAway = boxViewModel::putAway,
            onStop = boxViewModel::stop,
            onSetOpenFaster = boxViewModel::setOpenFaster,
            onSetGuestSizing = boxViewModel::setGuestSizing,
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
            onShowGitHub = boxViewModel::showGitHub,
            onResumeConnection = boxViewModel::resumeConnection,
            onDismissGitHub = boxViewModel::dismissGitHub,
            onConnectGitHub = { boxViewModel.connectGitHub() },
            onGitHubRepositoriesChosen = boxViewModel::githubRepositoriesChosen,
            onSubmitGitHubToken = boxViewModel::submitGitHubToken,
            onDeclineConnection = boxViewModel::declineConnection,
            onDisconnectGitHub = boxViewModel::disconnectGitHub,
            onSetPermissionMode = boxViewModel::setPermissionMode,
            onViewportChanged = boxViewModel::setViewport,
            onAttachPhoto = {
                pickPhoto.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onAttachFile = { pickFile.launch(arrayOf("*/*")) },
            onRemoveAttachment = boxViewModel::removeAttachment,
        )
    }
}
