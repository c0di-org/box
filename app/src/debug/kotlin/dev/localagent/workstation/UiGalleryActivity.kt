package dev.localagent.workstation

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.agent.AgentActivity
import dev.localagent.workstation.agent.AgentPermissionMode
import dev.localagent.workstation.agent.FakeAgentBackend
import dev.localagent.workstation.agent.ConnectService
import dev.localagent.workstation.agent.GitHubAuth
import dev.localagent.workstation.agent.GuestAuth
import dev.localagent.workstation.agent.PermissionDecision
import dev.localagent.workstation.agent.TranscriptBuilder
import dev.localagent.workstation.agent.TranscriptItem
import dev.localagent.workstation.computer.ControlHolder
import dev.localagent.workstation.computer.DesktopInput
import dev.localagent.workstation.computer.DesktopState
import dev.localagent.workstation.computer.DesktopTransport
import dev.localagent.workstation.computer.GuestScreen
import dev.localagent.workstation.ui.BoxApp
import dev.localagent.workstation.ui.BoxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Debug-only: the whole shell against a canned state, with no VM under it.
 *
 * Every interesting moment in Box's UI needs a Linux machine to exist — an emulator has none, and
 * the states worth looking at (the opening, the arrival, the computer) are exactly the ones a
 * developer cannot reach on their desk. `adb shell am start -n <pkg>/dev.localagent.workstation
 * .UiGalleryActivity --es scene computer`.
 *
 * The conversation scenes are not written out here. They are *played*: [FakeAgentBackend] — the same
 * scripted backend the app itself falls back to — is run at zero pace and its events are folded
 * through the real [TranscriptBuilder], so a scene is a position in that script rather than a
 * second copy of it that goes stale the moment the script changes. Which is what makes this
 * screenshottable: `tools/screenshots.sh` walks [SCENES] on a phone and a tablet emulator, and the
 * pictures in the README are whatever the app draws today.
 *
 * Adding a scene means adding it to [SCENES] and to [GalleryModel.enter].
 */
class UiGalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scene = intent?.getStringExtra("scene") ?: DEFAULT_SCENE
        if (scene !in SCENES) {
            Log.w(TAG, "Unknown scene \"$scene\"; showing \"$DEFAULT_SCENE\". Known: $SCENES")
        }
        setContent {
            val scope = rememberCoroutineScope()
            val model = remember { GalleryModel(scope) }
            val state by model.state.collectAsState()

            LaunchedEffect(scene) {
                model.enter(scene)
                // The capture script watches for this line rather than sleeping and hoping.
                Log.i(TAG, "scene \"$scene\" ready")
            }

            BoxTheme {
                BoxApp(
                    state = state,
                    onDestinationSelected = model::destination,
                    onSelectSession = model::select,
                    onNewConversation = {},
                    onSend = model::send,
                    onInterrupt = model::interrupt,
                    onStopSubAgent = model::stopSubAgent,
                    onPermissionDecision = model::decide,
                    onOpenArtifact = {},
                    onCloseSession = {},
                    onUndoCloseSession = {},
                    onCommitCloseSession = {},
                    onSelectComputerPanel = model::panel,
                    onOpenBox = {},
                    onPutAway = {},
                    onStop = {},
                    onRunCommand = {},
                    onSelectFilesPlace = {},
                    onOpenDirectory = {},
                    onNavigateUp = {},
                    onRefreshFiles = {},
                    onOpenFile = {},
                    onCloseFile = {},
                    onOpenInPhoneFiles = {},
                    onNoticeShown = {},
                    onDismissGreeting = model::dismissGreeting,
                    onSetPermissionMode = model::setPermissionMode,
                    // The gallery is usable by hand as well as photographed, so the sheet it can
                    // reach has to be closable. Nothing behind it connects to anything: there is
                    // no box here to hold a credential.
                    onShowGitHub = model::showGitHub,
                    onResumeConnection = model::showGitHub,
                    onDismissGitHub = model::hideGitHub,
                    onDeclineConnection = model::declineConnection,
                    desktop = if (state.destination == BoxDestination.Computer) StubDesktop else null,
                    onSetDesktopControl = model::control,
                )
            }
        }
    }

    private companion object {
        const val TAG = "BoxUiGallery"
    }
}

/** Every scene the gallery knows. `tools/screenshots.sh` names these. */
val SCENES = listOf(
    // The box itself, before there is anything else to look at.
    "closed",
    "opening",
    "greeting",
    "signin",
    // The work.
    "tasks",
    "chat",
    "permission",
    "question",
    "subagent",
    "github-ask",
    "github-code",
    "github-repos",
    "github-add-repo",
    // The machine.
    "computer",
    "computer-chat",
    "terminal",
    "files",
)

private const val DEFAULT_SCENE = "tasks"

/**
 * The gallery's state, driven by the app's own scripted backend.
 *
 * Everything the runtime would supply — whether the box is open, what is in `/workspace`, what the
 * shell printed — is canned here, because there is no runtime. Everything an *agent* would supply
 * comes from [FakeAgentBackend] and is folded by [TranscriptBuilder], exactly as `BoxViewModel`
 * does it with the real one.
 */
private class GalleryModel(private val scope: CoroutineScope) {

    private val backend = FakeAgentBackend(scope, pace = 0f)
    private val selection = MutableStateFlow<String?>(null)
    private val mutable = MutableStateFlow(BoxUiState(runtimeState = RuntimeState.Ready))
    val state: StateFlow<BoxUiState> = mutable.asStateFlow()

    /** The scripted "clone my project" conversation — the one the README is written about. */
    private val headline: String get() = backend.sessions.value.first().id

    /** The other scripted conversation: one agent delegating to a sub-agent it can stop. */
    private val subAgentSession: String
        get() = backend.sessions.value.first { it.title == "Audit the public API" }.id

    init {
        scope.launch { backend.harnesses.collect { list -> mutable.update { it.copy(harnesses = list) } } }
        scope.launch { backend.sessions.collect { list -> mutable.update { it.copy(sessions = list) } } }
        scope.launch { transcripts().collect { transcript -> mutable.update { it.copy(transcript = transcript) } } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun transcripts() = selection.flatMapLatest { sessionId ->
        if (sessionId == null) {
            MutableStateFlow(null)
        } else {
            val builder = TranscriptBuilder(sessionId)
            backend.events(sessionId).map { event ->
                builder.accept(event)
                builder.build()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Scenes
    // -----------------------------------------------------------------------

    suspend fun enter(scene: String) {
        when (scene) {
            "closed" -> mutable.update {
                BoxUiState(runtimeState = RuntimeState.NotProvisioned, harnesses = it.harnesses)
            }

            // Two thirds of the way through a first boot, with the work already queued behind it.
            "opening" -> mutable.update {
                it.copy(
                    runtimeState = RuntimeState.Connecting,
                    openingSince = SystemClock.elapsedRealtime() - 82_000L,
                )
            }

            "greeting" -> mutable.update { it.copy(readyGreeting = true) }

            // The same arrival, for the first-run user who has not signed in — with the task they
            // typed into the wait still in hand. Nothing about this scene is reachable in the
            // gallery's fake backend, which is signed into nothing and asks for nothing.
            "signin" -> mutable.update {
                it.copy(
                    readyGreeting = true,
                    signIn = GuestAuth.State.SignedOut,
                    queued = listOf(
                        QueuedPrompt(
                            sessionId = null,
                            text = "Clone my project and get it running.",
                            heldForSignIn = true,
                        ),
                    ),
                )
            }

            "tasks" -> Unit

            "chat" -> {
                select(headline)
                // The script parks on a permission request. Answer it, then wait for the run to
                // finish, so the shot is the whole arc: plan, tool cards, diff, what came of it.
                allowPending()
                settle()
            }

            "permission" -> {
                select(headline)
                awaitPending()
            }

            // An agent asking rather than asking permission, answered where it was asked. The
            // generic script is the only one that puts a question up, and it only runs for a turn
            // the gallery starts itself — so this scene sends one.
            "question" -> {
                select(headline)
                allowPending()
                settle()
                backend.send(headline, "Now do the same for the API client.")
                awaitPending()
            }

            // A sub-agent mid-run, which is the only state where it can be stopped. The scripted
            // delegate parks rather than finishing, so this shot is reachable at zero pace.
            "subagent" -> {
                select(subAgentSession)
                state.first { transcript ->
                    transcript.transcript?.items?.any {
                        it is TranscriptItem.SubAgent && it.items.isNotEmpty()
                    } == true
                }
            }

            "computer" -> computer(ComputerPanel.None)

            "computer-chat" -> {
                select(headline)
                allowPending()
                settle()
                computer(ComputerPanel.Chat)
            }

            // An agent mid-clone, holding its turn open on an account only the user can grant.
            // Reachable nowhere else: the fake backend never asks, because it has no VM to ask for.
            "github-ask" -> {
                select(headline)
                allowPending()
                settle()
                mutable.update { it.copy(connectRequest = CLONE_REQUEST.copy(sessionId = headline)) }
            }

            // The sheet the ask opens, with the code already on the clipboard.
            "github-code" -> {
                select(headline)
                allowPending()
                settle()
                mutable.update {
                    it.copy(
                        connectRequest = CLONE_REQUEST.copy(sessionId = headline),
                        githubVisible = true,
                        github = GitHubAuth.State.AwaitingApproval(
                            userCode = "WDJB-MJHT",
                            url = "https://github.com/login/device?user_code=WDJB-MJHT",
                            expiresAtElapsedRealtime = SystemClock.elapsedRealtime() + 13 * 60_000L,
                            reason = CLONE_REQUEST.reason,
                        ),
                    )
                }
            }

            // The second step, which is the one that is actually about trust.
            "github-repos" -> {
                select(headline)
                allowPending()
                settle()
                mutable.update {
                    it.copy(
                        connectRequest = CLONE_REQUEST.copy(sessionId = headline),
                        githubVisible = true,
                        github = GitHubAuth.State.ChoosingRepositories(
                            url = "https://github.com/apps/box/installations/new",
                            login = "codi",
                        ),
                    )
                }
            }

            // The same step on a box that is already connected, which is the commoner arrival: an
            // agent's 403 on a private repository usually means "not that one", not "no account".
            "github-add-repo" -> {
                select(headline)
                allowPending()
                settle()
                mutable.update {
                    it.copy(
                        connectRequest = CLONE_REQUEST.copy(sessionId = headline),
                        githubVisible = true,
                        github = GitHubAuth.State.ChoosingRepositories(
                            url = "https://github.com/apps/box/installations/new",
                            login = "codi",
                            adding = true,
                        ),
                    )
                }
            }

            "terminal" -> computer(ComputerPanel.Terminal)

            "files" -> computer(ComputerPanel.Files)

            else -> enter(DEFAULT_SCENE)
        }
    }

    private fun computer(panel: ComputerPanel) = mutable.update {
        it.copy(
            destination = BoxDestination.Computer,
            desktopControl = ControlHolder.User,
            computerPanel = panel,
            commandHistory = SHELL_HISTORY,
            currentPath = "/workspace/awesome-app",
            files = WORKSPACE,
        )
    }

    private suspend fun awaitPending() =
        state.first { it.transcript?.pendingPermission != null }.transcript!!.pendingPermission!!

    private suspend fun allowPending() {
        val pending = awaitPending()
        backend.resolvePermission(headline, pending.requestId, PermissionDecision.Allow)
    }

    /** Waits for the agent to stop working. At zero pace this is a handful of frames. */
    private suspend fun settle() {
        state.first { it.transcript?.activity == AgentActivity.Idle }
    }

    // -----------------------------------------------------------------------
    // Interaction — the gallery is meant to be usable by hand, not only photographed
    // -----------------------------------------------------------------------

    fun showGitHub() = mutable.update { it.copy(githubVisible = true) }

    fun hideGitHub() = mutable.update { it.copy(githubVisible = false) }

    fun declineConnection() = mutable.update { it.copy(githubVisible = false, connectRequest = null) }

    fun select(sessionId: String?) {
        selection.value = sessionId
        mutable.update { it.copy(selectedSessionId = sessionId, transcript = null) }
    }

    fun send(text: String) {
        val sessionId = selection.value ?: return
        scope.launch { backend.send(sessionId, text) }
    }

    fun interrupt() {
        val sessionId = selection.value ?: return
        scope.launch { backend.interrupt(sessionId) }
    }

    fun stopSubAgent(subAgentId: String) {
        val sessionId = selection.value ?: return
        scope.launch { backend.interruptSubAgent(sessionId, subAgentId) }
    }

    fun decide(requestId: String, decision: PermissionDecision) {
        val sessionId = selection.value ?: return
        scope.launch { backend.resolvePermission(sessionId, requestId, decision) }
    }

    fun destination(destination: BoxDestination) = mutable.update { it.copy(destination = destination) }

    fun panel(panel: ComputerPanel) = mutable.update {
        it.copy(computerPanel = if (it.computerPanel == panel) ComputerPanel.None else panel)
    }

    fun control(holder: ControlHolder) = mutable.update { it.copy(desktopControl = holder) }

    fun dismissGreeting() = mutable.update { it.copy(readyGreeting = false) }

    // Nothing here obeys the setting — there is no agent to skip asking. It is held so the
    // composer's mode control shows the choice, which is the only place that state is now drawn.
    fun setPermissionMode(mode: AgentPermissionMode) = mutable.update { it.copy(permissionMode = mode) }
}

/** What the shell tools would be showing if a box were open. */
/** The ask the scripted "clone my project" conversation would make on a box with no credential. */
private val CLONE_REQUEST = ConnectRequest(
    sessionId = "",
    requestId = "connect-1",
    service = ConnectService.GitHub,
    reason = "to clone garfbargle/awesome-app",
)

private val SHELL_HISTORY = listOf(
    CommandRecord(
        id = 1,
        command = "uname -srm",
        stdout = "Linux 6.1.0-arm64 aarch64\n",
        stderr = "",
        exitCode = 0,
    ),
    CommandRecord(
        id = 2,
        command = "git -C awesome-app log --oneline -3",
        stdout = "8f2c1ab Add retry to the payments client\n" +
            "3d90e77 Bump vite to 5.2.8\n" +
            "b41e5c0 Initial commit\n",
        stderr = "",
        exitCode = 0,
    ),
    CommandRecord(
        id = 3,
        command = "curl -s localhost:5173 | head -3",
        stdout = "<!doctype html>\n<html lang=\"en\">\n  <head>\n",
        stderr = "",
        exitCode = 0,
    ),
)

private val WORKSPACE = listOf(
    FileEntry("/workspace/awesome-app/src", "src", isDirectory = true, size = 4096),
    FileEntry("/workspace/awesome-app/public", "public", isDirectory = true, size = 4096),
    FileEntry("/workspace/awesome-app/node_modules", "node_modules", isDirectory = true, size = 4096),
    FileEntry("/workspace/awesome-app/index.html", "index.html", isDirectory = false, size = 361),
    FileEntry("/workspace/awesome-app/package.json", "package.json", isDirectory = false, size = 1_204),
    FileEntry("/workspace/awesome-app/package-lock.json", "package-lock.json", isDirectory = false, size = 486_912),
    FileEntry("/workspace/awesome-app/vite.config.js", "vite.config.js", isDirectory = false, size = 288),
    FileEntry("/workspace/awesome-app/README.md", "README.md", isDirectory = false, size = 2_047),
)

/**
 * Reports a live screen and paints nothing.
 *
 * Layout is the only thing this is for, and deliberately so: the guest's pixels are the one part of
 * Box that has never been confirmed on a phone, and a gallery that painted a convincing desktop
 * here would put a picture of a working feature into the README.
 */
private object StubDesktop : DesktopTransport {
    override val state: StateFlow<DesktopState> =
        MutableStateFlow(DesktopState.Live(1280, 800, ControlHolder.User))

    // Never asks for a resize, for the same reason nothing is painted: the gallery is about
    // layout, and a stub that drove the guest's screen would be reaching past what it is for.
    override val wantedGuestScreen: StateFlow<GuestScreen?> = MutableStateFlow(null)

    override suspend fun attach(surface: Surface, widthPx: Int, heightPx: Int, preview: Boolean) = Unit
    override suspend fun detach(surface: Surface) = Unit
    override suspend fun send(input: DesktopInput) = Unit
    override suspend fun setControl(holder: ControlHolder) = Unit
}
