package dev.localagent.workstation

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.database.ContentObserver
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.runtime.qemu.GuestSizing
import dev.localagent.runtime.qemu.IExecCallback
import dev.localagent.runtime.qemu.IFileListCallback
import dev.localagent.runtime.qemu.IFileReadCallback
import dev.localagent.runtime.qemu.IPortForwardCallback
import dev.localagent.runtime.qemu.IRuntimeControl
import dev.localagent.runtime.qemu.RuntimeService
import dev.localagent.runtime.qemu.RuntimeStateCodec
import dev.localagent.runtime.qemu.RuntimeStorage
import dev.localagent.runtime.qemu.shared.SharedFolder
import dev.localagent.workstation.agent.AgentBackend
import dev.localagent.workstation.agent.AgentEvent
import dev.localagent.workstation.agent.AgentModel
import dev.localagent.workstation.agent.AgentPermissionMode
import dev.localagent.workstation.agent.Attachment
import dev.localagent.workstation.agent.AgentViewport
import dev.localagent.workstation.agent.Artifact
import dev.localagent.workstation.files.Inbox
import dev.localagent.workstation.agent.FakeAgentBackend
import dev.localagent.workstation.agent.ConnectOutcome
import dev.localagent.workstation.agent.GitHubAuth
import dev.localagent.workstation.agent.GuestAgentBackend
import dev.localagent.workstation.agent.GuestAuth
import dev.localagent.workstation.agent.PermissionDecision
import dev.localagent.workstation.agent.SessionConnection
import dev.localagent.workstation.agent.TranscriptBuilder
import dev.localagent.workstation.computer.ControlHolder
import dev.localagent.workstation.computer.GuestScreen
import dev.localagent.workstation.computer.GuestScreenFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Product state for Box.
 *
 * Two independent sources feed it. Agent sessions come from an [AgentBackend] — today a scripted
 * fake, tomorrow the harness transport — and the VM's own state comes from RuntimeService in the
 * `:computer` process. They are deliberately not coupled: the conversation stays usable while the
 * VM boots, dies, or was never provisioned, because chat is the product and the VM is substrate.
 */
class BoxViewModel @JvmOverloads constructor(
    application: Application,
    // The default ViewModel factory reflects on a single-argument constructor, hence @JvmOverloads.
    backend: AgentBackend? = null,
) : AndroidViewModel(application) {
    private val mutableUiState = MutableStateFlow(
        BoxUiState(
            openFaster = application
                .getSharedPreferences(GuestAgentBackend.PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(OPEN_FASTER_KEY, true),
            guestSizing = application
                .getSharedPreferences(GuestAgentBackend.PREFERENCES, Context.MODE_PRIVATE)
                .let {
                    GuestSizing(
                        memoryMb = it.getInt(GUEST_MEMORY_KEY, GuestSizing.DEFAULT.memoryMb),
                        processors = it.getInt(GUEST_PROCESSORS_KEY, GuestSizing.DEFAULT.processors),
                    )
                },
            // What this phone can be asked for, which is not what QEMU could be asked for. Read
            // here rather than each time the sheet opens: it is a property of the device.
            guestSizingChoices = GuestSizing.choicesFor(application),
        ),
    )
    val uiState: StateFlow<BoxUiState> = mutableUiState.asStateFlow()
    private val ids = AtomicLong()

    private val agents: AgentBackend = backend ?: FakeAgentBackend(viewModelScope)
    private val auth = BoxContainer.auth
    private val openings = OpeningHistory(application)

    // The same file the backend keeps the permission mode in; Box has a handful of settings and no
    // screen for them, so a store of its own would be more machinery than the thing being stored.
    private val preferences =
        application.getSharedPreferences(GuestAgentBackend.PREFERENCES, Context.MODE_PRIVATE)
    private val signIns = SignInHistory(application)
    private val github = GitHubAuth()
    private var transcriptJob: Job? = null
    private var connectionJob: Job? = null

    /**
     * The session whose log is still being read back, if one is.
     *
     * Null means everything arriving is live. See [AgentEvent.CaughtUp] — the distinction only
     * matters for events that are a *question*, since a question read out of a log has usually
     * already been answered further down it.
     */
    private var replayingSession: String? = null

    /** RuntimeService owns the VM in another process; this is the only source of runtime truth. */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val payload = intent?.getBundleExtra(RuntimeService.EXTRA_STATE) ?: return
            val state = RuntimeStateCodec.decode(payload) ?: return
            if (state == RuntimeState.Ready) rememberHowLongThatTook()
            val arrived = state == RuntimeState.Ready &&
                mutableUiState.value.runtimeState != RuntimeState.Ready
            mutableUiState.update {
                it.copy(
                    runtimeState = state,
                    openingSince = openingAfter(it.openingSince, it.runtimeState, state),
                )
            }
            if (arrived) announceOpenBox()
            // Held across the whole time the computer is meant to be alive, not just when it is
            // usable: the connection is how Box finds out that `:computer` died, and the startup
            // path is exactly where it dies. See [ComputerLoss].
            if (ComputerLoss.shouldWatch(state)) bindRuntime() else releaseRuntime()
            if (state != RuntimeState.Ready) readyAnnounced = false
            greetReadyComputer()
        }
    }

    /**
     * The questions worth asking a guest that has just become usable, asked exactly once.
     *
     * Now that the connection is held from startup onwards, `onServiceConnected` usually arrives
     * *before* the computer is ready — so readiness has two possible triggers, whichever lands
     * second, and this has to be safe to call from both.
     */
    private var readyAnnounced = false

    private fun greetReadyComputer() {
        if (readyAnnounced || mutableUiState.value.runtimeState != RuntimeState.Ready) return
        val bound = control ?: return
        readyAnnounced = true
        // Fill whichever place the Files panel is on as soon as the guest can answer.
        refreshFiles()
        // Whether the guest already holds a credential is only answerable once there is a guest to
        // ask. Until then the sign-in state is honestly Unknown.
        auth.check(bound)
        // A sheet raised while the box was still starting has been sitting on "Waiting for your
        // box…". It can answer now, so the code it was meant to be showing arrives without the
        // person having to press anything.
        //
        // Asked before the status check, and *instead* of it when it fires: the connect program
        // reads the stored credential itself and decides between a device flow and the repository
        // picker on what it finds. A status check racing alongside would only be a second opinion
        // about the same file, arriving in whichever order the guest happened to answer.
        if (!resumeWaitingConnection()) {
            // Whether this box already reaches GitHub, which used to be asked only if somebody
            // went looking for it in diagnostics. A box that is connected should be able to say so
            // without being interrogated.
            github.check(bound)
        }
    }

    /**
     * Starts a flow the box was not up for when it was asked for, if there is one.
     *
     * Narrow on purpose: only where a sheet is already open against an agent that is still
     * waiting, and only where nothing else has since taken the flow somewhere. Anything wider
     * would restart a device flow underneath somebody halfway through one.
     */
    private fun resumeWaitingConnection(): Boolean {
        val state = mutableUiState.value
        if (!state.githubVisible || state.connectRequest == null || !state.computerReady) return false
        return when (state.github) {
            GitHubAuth.State.Unknown, GitHubAuth.State.Disconnected -> {
                connectGitHub(state.connectRequest.reason)
                true
            }
            else -> false
        }
    }

    @Volatile private var control: IRuntimeControl? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            control = IRuntimeControl.Stub.asInterface(binder)
            greetReadyComputer()
        }

        /**
         * `:computer` has gone. Android calls this when the hosting process dies, which for a
         * native VM abort is the only notice Box ever gets — no final state is broadcast.
         */
        override fun onServiceDisconnected(name: ComponentName?) {
            control = null
            val lost = ComputerLoss.after(mutableUiState.value.runtimeState) ?: return
            mutableUiState.update { it.copy(runtimeState = lost) }
            if (lost is RuntimeState.Failed) showNotice(lost.reason.message)
        }
    }

    /**
     * Only called for states the computer itself just reported, so `:computer` is already up and
     * `BIND_AUTO_CREATE` never starts it on Box's behalf.
     */
    private fun bindRuntime() {
        if (bound) return
        bound = getApplication<Application>().bindService(
            Intent(getApplication(), RuntimeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun releaseRuntime() {
        if (!bound) return
        bound = false
        control = null
        runCatching { getApplication<Application>().unbindService(connection) }
    }

    init {
        openings.expectedMillis()?.let { learned ->
            mutableUiState.update { it.copy(expectedOpenMillis = learned) }
        }
        ContextCompat.registerReceiver(
            getApplication(),
            stateReceiver,
            IntentFilter(RuntimeService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        resyncRuntimeState()
        seedSavedBoxIfImageIsPending()
        observeAgents()
        watchSharedFolder()
        followWindowWithGuestScreen()
        mutableUiState.update { it.copy(signedInBefore = signIns.hasSignedIn()) }
        viewModelScope.launch {
            github.state.collect { state ->
                mutableUiState.update { it.copy(github = state) }
                // An agent asked, a person went and did it: the session that has been holding a
                // tool call open is told, and carries on with the clone it was in the middle of.
                if (state is GitHubAuth.State.Connected && !state.needsRepositories) {
                    answerConnectRequest(ConnectOutcome(true, state.login, state.repositories))
                }
            }
        }
        viewModelScope.launch {
            auth.state.collect { signIn ->
                mutableUiState.update { it.copy(signIn = signIn) }
                if (signIn !is GuestAuth.State.SignedIn) return@collect
                // Written from the guest's own answer, so it is remembered whether the credential
                // arrived through Box's sign-in sheet or was already sitting in the workspace.
                signIns.remember(true)
                mutableUiState.update { it.copy(signedInBefore = true) }
                // The point of holding anything: the words someone typed into a three-minute wait
                // go now, without being retyped and without ever having failed.
                flushHeldPrompts()
            }
        }
    }

    /**
     * The Shared place, kept live without inventing a channel for it.
     *
     * The sync runs in `:computer` and tells Android's document machinery what it changed, because
     * the phone's Files app has to be told. Listening to the same notification is free, and it is
     * what turns "a file arrived from the box" from something the user has to go and check into
     * something they watch happen. `true` for descendants: a file landing in a subfolder is still
     * this folder changing.
     */
    private fun watchSharedFolder() {
        runCatching {
            getApplication<Application>().contentResolver.registerContentObserver(
                DocumentsContract.buildChildDocumentsUri(
                    SharedFolder.authority(getApplication()),
                    SharedFolder.ROOT_DOCUMENT_ID,
                ),
                true,
                sharedObserver,
            )
        }
    }

    private val sharedObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (mutableUiState.value.filesPlace == FilesPlace.Shared) refreshSharedFiles()
        }
    }

    private fun observeAgents() {
        viewModelScope.launch {
            agents.harnesses.collect { list ->
                mutableUiState.update { it.copy(harnesses = list) }
            }
        }
        viewModelScope.launch {
            agents.sessions.collect { list ->
                mutableUiState.update { it.copy(sessions = list) }
            }
        }
        viewModelScope.launch {
            agents.permissionMode.collect { mode ->
                mutableUiState.update { it.copy(permissionMode = mode) }
            }
        }
        viewModelScope.launch {
            agents.agentModel.collect { model ->
                mutableUiState.update { it.copy(agentModel = model) }
            }
        }
    }

    /**
     * Turning the asking off, or back on.
     *
     * Deliberately not stored in this state and mirrored down: the backend owns it, persists it and
     * tells the harnesses, and the UI reads back whatever it ended up as. One writer, so a mode the
     * guest never heard about can never be the one on screen.
     */
    fun setPermissionMode(mode: AgentPermissionMode) {
        viewModelScope.launch { agents.setPermissionMode(mode) }
    }

    /**
     * Changing which model answers. One writer, for the same reason as the mode above.
     *
     * Unlike the machine size on the same sheet, this needs nothing reopened: the backend tells
     * every running harness, and each one asks its session to switch for the next turn.
     */
    fun setAgentModel(model: AgentModel) {
        viewModelScope.launch { agents.setAgentModel(model) }
    }

    /**
     * Reported by the composition every time the window it measured changes shape.
     *
     * Called far more often than it does anything — a drag across a DeX corner is a stream of
     * widths — so the backend drops the ones that repeat rather than the UI trying to guess which
     * are worth sending. Nothing in [BoxUiState] holds it: no pixel on screen depends on what the
     * agent was told, and the window itself is the only honest source for what the window is.
     */
    fun setViewport(viewport: AgentViewport) {
        viewModelScope.launch { agents.setViewport(viewport) }
    }

    /**
     * Android can reclaim the UI process while the VM keeps running in `:computer`, which would
     * otherwise leave a fresh BoxUiState claiming Box was never set up. Installed images decide
     * the starting point, then a live runtime process overrides it with the real state.
     */
    private fun resyncRuntimeState() {
        val storage = runCatching { RuntimeStorage(getApplication()) }.getOrNull()
        val provisioned = runCatching { storage?.hasHeadlessBootSet() }.getOrNull() == true
        if (provisioned) {
            // Closed and put away are different starting points, and only the disk knows which
            // this is: a saved box has no `:computer` left to answer the broadcast below, so
            // without this the box the user paused comes back described as switched off.
            val saved = runCatching { storage?.hasSuspendedVm() }.getOrNull() == true
            mutableUiState.update {
                it.copy(runtimeState = if (saved) RuntimeState.Suspended else RuntimeState.Stopped)
            }
        }
        getApplication<Application>().sendBroadcast(
            Intent(RuntimeService.ACTION_QUERY_STATE)
                .setPackage(getApplication<Application>().packageName),
        )
    }

    /**
     * Boots and saves the new guest once, before the user asks for it.
     *
     * The open this is for is the one "Open faster" cannot reach. Provisioning happens inside the
     * Open gesture — `RuntimeService` installs the image and only then boots — so by the time a new
     * guest is on the phone the user is already waiting on it, and the snapshot that would have
     * made the open cheap was discarded by the install that replaced its disk. That makes the slow
     * path follow every Box update carrying a new guest, which is precisely when somebody has just
     * updated something and is looking at it.
     *
     * Launch is the trigger rather than a charging window, and that is a deliberate reading of the
     * battery question rather than a dodge of it. "Charging and idle" sounds careful and is not:
     * on a phone that is rarely plugged in it means the seeding never runs and the cold path stays
     * normal, so the feature would be paid for in code and never delivered. Here the work starts
     * while the person is in Box, which is both the moment they are most likely to open their box
     * — in which case the boot becomes theirs, see [RuntimeService.seeding] — and the moment a
     * notice about it is least of a surprise.
     */
    private fun seedSavedBoxIfImageIsPending() {
        val application = getApplication<Application>()
        val storage = runCatching { RuntimeStorage(application) }.getOrNull() ?: return
        val bundled = runCatching { storage.bundledIdentity() }.getOrNull() ?: return
        val installed = runCatching { storage.installedIdentity() }.getOrNull()
        val attempted = preferences.getString(SEEDED_IMAGE_KEY, null)
        val saver = application.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        val charge = application.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val seed = SeedDecision.shouldSeed(
            openFaster = mutableUiState.value.openFaster,
            installed = installed,
            bundled = bundled,
            lastAttempted = attempted,
            batterySaver = saver,
            batteryPercent = charge,
            minimumBatteryPercent = MINIMUM_SEED_BATTERY_PERCENT,
        )
        // One line per launch, and the only account anywhere of why a phone did or did not spend
        // two minutes of emulation on its own. A background boot that cannot be explained after the
        // fact is the kind of thing that gets a feature blamed for battery it never used.
        Log.i(
            TAG,
            "Seed check: openFaster=${mutableUiState.value.openFaster} installed=$installed " +
                "bundled=$bundled attempted=$attempted saver=$saver battery=$charge -> $seed",
        )
        if (!seed) return

        // Guarded, and the mark is only written if the start was actually accepted. This runs from
        // the ViewModel's construction, so the app is on screen and the foreground-service start is
        // allowed — but "allowed" here is a rule about process state that Box does not own, and the
        // penalty for being wrong about it is a crash on launch. Nothing about a seed is worth
        // that, and a refused seed costs only the cold open it was trying to avoid.
        runCatching {
            application.startForegroundService(
                Intent(application, RuntimeService::class.java)
                    .setAction(RuntimeService.ACTION_SEED)
                    // Carried for the same reason [start] carries it: `:computer` is a fresh
                    // process and a broadcast would arrive before anything was listening.
                    .putExtra(RuntimeService.EXTRA_KEEP_SAVED, mutableUiState.value.openFaster),
            )
        }.onSuccess {
            preferences.edit().putString(SEEDED_IMAGE_KEY, bundled.toString()).apply()
        }
    }

    override fun onCleared() {
        releaseRuntime()
        getApplication<Application>().unregisterReceiver(stateReceiver)
        runCatching { getApplication<Application>().contentResolver.unregisterContentObserver(sharedObserver) }
        super.onCleared()
    }

    // -----------------------------------------------------------------------
    // Conversations
    // -----------------------------------------------------------------------

    fun selectDestination(destination: BoxDestination) {
        if (destination == BoxDestination.Computer) openComputer() else showTasks()
    }

    /**
     * Go to the machine, and start it if it is off.
     *
     * The answer to "show me the computer" is never "no", it is "in a moment" — the same rule
     * sending a message follows. Somebody who only wants Linux should be able to install Box, press
     * Computer, and end up at a desktop without ever meeting an agent.
     *
     * Walking in also hands over the keyboard, unless an agent is mid-task. Deliberate arrival is
     * not the stray tap the explicit hand-over was written to protect against, and a desktop that
     * ignores the first thing you type into it is a desktop that looks broken.
     */
    fun openComputer() {
        wakeComputerIfNeeded()
        val state = mutableUiState.value
        val holder = if (state.agentAtWork) ControlHolder.Agent else ControlHolder.User
        mutableUiState.update { it.copy(destination = BoxDestination.Computer) }
        setDesktopControl(holder)
    }

    /** Back to the tasks. Control goes with it; see [BoxUiState.desktopControl]. */
    fun showTasks() {
        mutableUiState.update { it.copy(destination = BoxDestination.Tasks) }
        setDesktopControl(ControlHolder.Agent)
    }

    fun selectComputerPanel(panel: ComputerPanel) {
        mutableUiState.update {
            it.copy(
                computerPanel = if (it.computerPanel == panel) ComputerPanel.None else panel,
                openedFile = null,
            )
        }
        if (mutableUiState.value.computerPanel == ComputerPanel.Files) refreshFiles()
    }

    fun selectSession(sessionId: String?) {
        if (mutableUiState.value.selectedSessionId == sessionId) return
        transcriptJob?.cancel()
        connectionJob?.cancel()
        mutableUiState.update {
            it.copy(
                selectedSessionId = sessionId,
                transcript = null,
                transcriptLoading = sessionId != null,
                connection = SessionConnection.Connecting,
                // Only the observed session's events reach this class, so an outstanding request
                // belonging to the task being left has nobody left to keep it honest. Dropped
                // rather than carried: coming back replays the log, which is what will say
                // whether it is still outstanding.
                connectRequest = null,
            )
        }
        val id = sessionId ?: return
        // Everything until [AgentEvent.CaughtUp] is history being read back, not news.
        replayingSession = id

        connectionJob = viewModelScope.launch {
            agents.connection(id).collect { connection ->
                mutableUiState.update {
                    if (it.selectedSessionId == id) it.copy(connection = connection) else it
                }
            }
        }
        transcriptJob = viewModelScope.launch {
            val builder = TranscriptBuilder(id)
            agents.events(id).collect { event ->
                builder.accept(event)
                if (event is AgentEvent.PermissionRequested) autoApprove(id, event)
                if (event is AgentEvent.ConnectRequested) offerConnection(id, event)
                if (event is AgentEvent.ConnectResolved) settleConnection(event.requestId)
                if (event is AgentEvent.CaughtUp && replayingSession == id) {
                    replayingSession = null
                    raiseOutstandingConnection(id)
                }
                // The harness echoing a prompt is the proof it arrived, so the queued copy the UI
                // was showing in its place can go. One echo clears one copy, so the same message
                // sent twice stays visible twice.
                if (event is AgentEvent.UserMessage) {
                    mutableUiState.update { state ->
                        val index = state.queued.indexOfFirst {
                            it.text == event.text && (it.sessionId == null || it.sessionId == id)
                        }
                        if (index < 0) {
                            state
                        } else {
                            state.copy(queued = state.queued.filterIndexed { at, _ -> at != index })
                        }
                    }
                }
                val transcript = builder.build()
                mutableUiState.update {
                    if (it.selectedSessionId == id) {
                        it.copy(transcript = transcript, transcriptLoading = false)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** "Always allow" is remembered per scope string; matching later asks never raise the sheet. */
    private fun autoApprove(sessionId: String, event: AgentEvent.PermissionRequested) {
        val scope = event.ask.alwaysAllowScope ?: return
        if (scope !in mutableUiState.value.alwaysAllowed) return
        viewModelScope.launch {
            agents.resolvePermission(sessionId, event.requestId, PermissionDecision.AllowAlways(scope))
        }
    }

    /**
     * Sending is never refused because the computer is off, nor spent because it is not signed in.
     *
     * A message to a cold runtime starts it and is held until the guest can take it: the backend
     * queues the write, and the boot is ~3 minutes of visible waiting rather than an error. The
     * text shows as queued meanwhile, and the harness's echo of the prompt into the session log is
     * what clears that copy.
     *
     * A box with no credential is the other kind of not-yet, and used to be treated as a yes. The
     * first message anyone types is typed into the opening — three minutes before Box can discover
     * nobody is signed in — so handing it over then bought a failed task and a retype. It is
     * *held* instead: same queue, same card, and [flushHeldPrompts] sends it when sign-in lands.
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        val attachments = mutableUiState.value.pendingAttachments
        // A picture on its own is a message. "Look at this" is often the whole thought, and
        // requiring a word alongside it would be Box asking for something it does not need.
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        wakeComputerIfNeeded()
        val state = mutableUiState.value
        val sessionId = state.selectedSessionId
        // Held on what Box knows *and* on what it expects — see [BoxUiState.signInWanted]. Waiting
        // for certainty here would mean waiting for the guest, and the guest answers at exactly the
        // moment the harness starts taking work, which is the race this exists to lose safely.
        val held = state.signInWanted
        mutableUiState.update {
            it.copy(
                queued = it.queued + QueuedPrompt(sessionId, trimmed, heldForSignIn = held, attachments = attachments),
                // Cleared here rather than after the send lands: they are on their way with this
                // turn, and a second tap must not send them twice. They ride on the queued copy
                // rather than staying on the composer, which is what lets a turn held for a sign-in
                // still have its files when the credential finally arrives — the composer the user
                // would otherwise have to re-attach from is gone by then.
                pendingAttachments = emptyList(),
            )
        }
        if (held) {
            // Only once the box is open. Interrupting a boot with a sheet that says "waiting for
            // your box…" is the app asking for something it cannot accept yet; the arrival asks.
            if (state.computerReady) showSignIn()
            return
        }

        viewModelScope.launch {
            if (sessionId == null) {
                val harness = mutableUiState.value.harnesses.firstOrNull() ?: return@launch
                startSession(harness.id, trimmed, attachments)
            } else {
                agents.send(sessionId, trimmed, attachments)
            }
        }
    }

    /**
     * Takes something the user picked, or shared in from another app, into the box.
     *
     * The copy happens now rather than at send, and that is the point: a `content://` uri is a
     * loan from the app that produced it, revocable and often dead by the time the user has
     * finished typing. Once it is in the shared folder it is a file, and the rest of Box only ever
     * deals in paths.
     */
    fun attach(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            Inbox.receive(getApplication(), uri)
                .onSuccess { attachment ->
                    mutableUiState.update {
                        it.copy(pendingAttachments = it.pendingAttachments + attachment)
                    }
                }
                .onFailure { failure -> showNotice(failure.message ?: "That file could not be attached.") }
        }
    }

    /**
     * Files another app handed to Box through the share sheet.
     *
     * Beyond taking them in, this has to put them somewhere they can be *seen*. A share can reach
     * Box with nothing open — the home surface has no composer once the box has settled — and an
     * attachment nobody can see is the same as one that never arrived. So the newest conversation
     * is selected, or one is started if this box has never had a conversation at all, which is
     * exactly what someone who just shared a picture to a chat app expects to be looking at.
     */
    fun receiveShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach(::attach)
        val state = mutableUiState.value
        if (state.selectedSessionId != null) return
        val newest = state.sessions.firstOrNull()
        if (newest != null) {
            selectSession(newest.id)
        } else {
            state.harnesses.firstOrNull()?.let { startSession(it.id) }
        }
    }

    /**
     * Taken back off the composer before it was sent, and deleted.
     *
     * Deleting is right only here. Before sending, the copy exists because Box made one and the
     * user has changed their mind; after sending it is a file in their own folder, on their phone,
     * and Box removing it would be tidying up something that is no longer its business.
     */
    fun removeAttachment(attachment: Attachment) {
        mutableUiState.update {
            it.copy(pendingAttachments = it.pendingAttachments.filterNot { held -> held == attachment })
        }
        viewModelScope.launch(Dispatchers.IO) {
            Inbox.phoneFile(getApplication(), attachment.guestPath)?.delete()
        }
    }

    /**
     * Sends everything that was waiting on a credential, in the order it was typed.
     *
     * Deliberately a replay of [sendMessage]'s own two branches rather than a bulk write: a prompt
     * held with no session still has to *start* one, and starting it is what gives the task its
     * title — the user's first line. Marking each as no longer held before it goes keeps it on
     * screen as an ordinary queued message, which the harness's echo then clears.
     */
    private fun flushHeldPrompts() {
        val held = mutableUiState.value.heldForSignIn
        if (held.isEmpty()) return
        mutableUiState.update { state ->
            state.copy(
                queued = state.queued.map { it.copy(heldForSignIn = false) },
                // The arrival has done its job and is about to be left behind: this sends the user
                // into the conversation it was holding for them. Leaving the flag set would put the
                // welcome screen back on the first press of the back button.
                readyGreeting = false,
            )
        }
        viewModelScope.launch {
            // Sequential, and carrying the id the first one created: everything typed into a
            // booting box was typed before any session existed, so all of it is bound for the one
            // conversation the first line opens — not a task each.
            var started: String? = null
            held.forEach { prompt ->
                val sessionId = prompt.sessionId ?: started
                // The files go with the turn they were attached to, which is the whole reason
                // [QueuedPrompt] carries them: the composer they came from was cleared when the
                // message was typed, possibly minutes and a sign-in ago.
                if (sessionId == null) {
                    val harness = mutableUiState.value.harnesses.firstOrNull() ?: return@forEach
                    started = beginSession(harness.id, prompt.text, prompt.attachments)
                } else {
                    agents.send(sessionId, prompt.text, prompt.attachments)
                }
            }
        }
    }

    /** Start the VM if a message needs it. Never restarts one that is already on its way up. */
    private fun wakeComputerIfNeeded() {
        val state = mutableUiState.value
        val needsWaking = state.runtimeState == RuntimeState.Stopped ||
            state.runtimeState == RuntimeState.NotProvisioned ||
            state.runtimeState == RuntimeState.Suspended ||
            state.runtimeState is RuntimeState.Failed
        if (needsWaking) start()
    }

    /**
     * The tour, always in a conversation of its own.
     *
     * Deliberately not [sendMessage] with the same words. That path appends to whatever is
     * selected, so tapping the tour from inside a task would bury a first look at the machine in
     * the middle of unrelated work — and the tour opens by reporting what this box *is*, which
     * only reads as an answer when it is the first thing in the thread. A fresh session is also
     * what makes the transcript worth keeping: it is the one conversation somebody might scroll
     * back to a week later to remember what Box did on the day they installed it.
     *
     * Held prompts still behave: [beginSession] carries the prompt through the same queue, so a
     * box with nobody signed in yet holds this exactly as it holds a typed one.
     */
    fun startTour() {
        val harness = mutableUiState.value.harnesses.firstOrNull() ?: return
        wakeComputerIfNeeded()
        val state = mutableUiState.value
        // The held branch, spelled out rather than delegated to [sendMessage], because the one
        // thing that must not be inherited from it is the selected session: this is queued with a
        // null id on purpose, which is the shape [flushHeldPrompts] answers by *starting* a
        // conversation. Without this the first tap on a box nobody has signed into yet would open
        // a task and fail it, which is the exact trade [sendMessage]'s own hold exists to avoid.
        if (state.signInWanted) {
            mutableUiState.update {
                it.copy(
                    queued = it.queued + QueuedPrompt(
                        sessionId = null,
                        text = TOUR_PROMPT,
                        heldForSignIn = true,
                    ),
                )
            }
            if (state.computerReady) showSignIn()
            return
        }
        startSession(harness.id, TOUR_PROMPT)
    }

    fun startSession(
        harnessId: String,
        prompt: String? = null,
        attachments: List<Attachment> = emptyList(),
    ) {
        if (mutableUiState.value.startingSession) return
        viewModelScope.launch { beginSession(harnessId, prompt, attachments) }
    }

    /** The body of [startSession], for callers that need the id before they do anything else. */
    private suspend fun beginSession(
        harnessId: String,
        prompt: String? = null,
        attachments: List<Attachment> = emptyList(),
    ): String? {
        mutableUiState.update { it.copy(startingSession = true) }
        val id = runCatching { agents.startSession(harnessId, prompt, attachments) }
            .onFailure { error -> showNotice(error.message ?: "Box could not start that task.") }
            .getOrNull()
        mutableUiState.update { state ->
            state.copy(
                startingSession = false,
                // The first message was typed before this session had an id. Give it one now,
                // so selecting the conversation does not take it for another session's.
                queued = if (id == null) {
                    state.queued
                } else {
                    state.queued.map { if (it.sessionId == null) it.copy(sessionId = id) else it }
                },
            )
        }
        if (id != null) selectSession(id)
        return id
    }

    fun resolvePermission(requestId: String, decision: PermissionDecision) {
        val sessionId = mutableUiState.value.selectedSessionId ?: return
        viewModelScope.launch { agents.resolvePermission(sessionId, requestId, decision) }
        if (decision !is PermissionDecision.AllowAlways) return

        mutableUiState.update { it.copy(alwaysAllowed = it.alwaysAllowed + decision.scope) }
        // "Always" has to mean the ones already on screen too. A turn that asks twice about the same
        // scope raises both before either is answered, so widening the rule and then only checking
        // asks that arrive *later* leaves its own sibling sitting there blocked — which reads exactly
        // like the button having done nothing.
        val alsoCovered = mutableUiState.value.transcript?.pendingPermissions.orEmpty()
            .filter { it.requestId != requestId && it.ask.alwaysAllowScope == decision.scope }
        viewModelScope.launch {
            alsoCovered.forEach { agents.resolvePermission(sessionId, it.requestId, decision) }
        }
    }

    fun interruptSession() {
        val sessionId = mutableUiState.value.selectedSessionId ?: return
        viewModelScope.launch { agents.interrupt(sessionId) }
    }

    /**
     * Stops one sub-agent. The session keeps running — this is not the Stop button in the header.
     *
     * No confirmation and no notice: the card the button is on becomes the answer, and a snackbar
     * saying "stopped" about a thing that visibly stopped is the app reading itself aloud.
     */
    fun interruptSubAgent(subAgentId: String) {
        val sessionId = mutableUiState.value.selectedSessionId ?: return
        viewModelScope.launch { agents.interruptSubAgent(sessionId, subAgentId) }
    }

    /**
     * Take a task off the list, with a way back for as long as the snackbar is up.
     *
     * The list is where closing belongs — it used to mean opening the task and finding the header
     * menu, which is a strange route to "I am done with this" — and a list you can close things
     * from needs an undo, because a swipe is easy to do by accident and this one is final. So the
     * row goes now and the close itself waits: see [BoxUiState.closingTaskId]. Nothing is told to
     * the agent in the meantime, which is what makes taking it back free.
     */
    fun beginClosingTask(sessionId: String) {
        val pending = mutableUiState.value.closingTaskId
        // Its snackbar is about to be replaced by this one, so its undo is gone. Honour what the
        // user did rather than quietly keeping a task they closed.
        if (pending != null && pending != sessionId) closeSession(pending)
        mutableUiState.update { it.copy(closingTaskId = sessionId) }
        if (mutableUiState.value.selectedSessionId == sessionId) selectSession(null)
    }

    /** The undo window closed without being used. */
    fun commitClosingTask() {
        val pending = mutableUiState.value.closingTaskId ?: return
        mutableUiState.update { it.copy(closingTaskId = null) }
        closeSession(pending)
    }

    /** The row comes back exactly as it was, because nothing has happened to it yet. */
    fun undoClosingTask() {
        mutableUiState.update { it.copy(closingTaskId = null) }
    }

    fun closeSession(sessionId: String) {
        viewModelScope.launch {
            agents.closeSession(sessionId)
            if (mutableUiState.value.selectedSessionId == sessionId) selectSession(null)
        }
    }

    // -----------------------------------------------------------------------
    // GitHub
    // -----------------------------------------------------------------------

    /**
     * The agent needs an account, so Box asks for it — with the code already on screen.
     *
     * Started here rather than on a tap, which is the whole difference between this and a banner.
     * The person asked for a private repository; the agent is holding its turn open; a sheet
     * saying "press to begin" would spend the one moment where everything is already in context on
     * a button. So it arrives with eight characters in it and only the part they alone can do left.
     *
     * Closing it answers nothing — the agent goes on waiting and the card stays in the
     * conversation, because "not now" is said out loud rather than inferred from a dismissal.
     */
    private fun offerConnection(sessionId: String, event: AgentEvent.ConnectRequested) {
        mutableUiState.update {
            it.copy(connectRequest = ConnectRequest(sessionId, event.requestId, event.service, event.reason))
        }
        // A replayed request is remembered and nothing more. The log has not finished speaking:
        // the very next line may be the [AgentEvent.ConnectResolved] saying this was answered
        // weeks ago, and acting now would open a sheet and start a device flow on a box that is
        // already connected — which is what merely opening an old task used to do.
        if (replayingSession == null) raiseOutstandingConnection(sessionId)
    }

    /**
     * A request that is over, whoever ended it.
     *
     * Matched by id rather than cleared wholesale, because a log can hold several: an earlier
     * request that was answered must not take down a later one that is genuinely still waiting.
     */
    private fun settleConnection(requestId: String) {
        val outstanding = mutableUiState.value.connectRequest ?: return
        if (outstanding.requestId != requestId) return
        // Deliberately leaves the sheet alone. If it is open it is because somebody is looking at
        // it, and dropping the request is enough: the "Not now" button belongs to a waiting agent
        // and goes with it, while the rest of the sheet is still a useful thing to be looking at.
        mutableUiState.update { it.copy(connectRequest = null) }
    }

    /**
     * The sheet, for a request that is still waiting once everything is known.
     *
     * The flow is started here rather than waiting for a tap, and that is the whole difference
     * between this and a banner. The person asked for a private repository to be cloned; the agent
     * is holding its turn open; opening a sheet that says "press to begin" would spend the one
     * moment where everything is already in context on a button. So the sheet arrives with eight
     * characters in it, and the only thing left to do is the part only they can do.
     */
    private fun raiseOutstandingConnection(sessionId: String) {
        val request = mutableUiState.value.connectRequest ?: return
        if (request.sessionId != sessionId) return
        // Only for the conversation actually on screen. Raising a sheet over a different task
        // because a backgrounded one reached this point is somebody else's interruption.
        if (mutableUiState.value.selectedSessionId != sessionId) return
        mutableUiState.update { it.copy(githubVisible = true) }
        // The manual button has always been gated on the box being up; this path never was, and
        // the first thing the flow does is an outbound request. Started against a box whose
        // network has not come up yet, that fails instantly and paints the sheet red. The sheet
        // says "Waiting for your box…" instead, and [connectGitHub] runs when it is.
        if (mutableUiState.value.computerReady) connectGitHub(request.reason)
    }

    private fun answerConnectRequest(outcome: ConnectOutcome) {
        val request = mutableUiState.value.connectRequest ?: return
        mutableUiState.update { it.copy(connectRequest = null) }
        viewModelScope.launch {
            agents.resolveConnect(request.sessionId, request.requestId, outcome)
        }
    }

    /** "Not now", said out loud, so the agent can work around it rather than wait. */
    fun declineConnection() {
        answerConnectRequest(ConnectOutcome(connected = false))
        mutableUiState.update { it.copy(githubVisible = false) }
        github.cancel()
    }

    /**
     * Coming back to a request that is still waiting.
     *
     * Not [showGitHub]: that opens the sheet and asks the box what it knows, which is right when
     * somebody went looking for the setting and wrong here. An agent is already waiting, the
     * answer is already known to be "not connected", and a sheet that opens on a Connect button
     * spends a tap re-establishing what the banner they just tapped had already said.
     */
    fun resumeConnection() {
        mutableUiState.update { it.copy(githubVisible = true) }
        connectGitHub(mutableUiState.value.connectRequest?.reason)
    }

    fun showGitHub() {
        mutableUiState.update { it.copy(githubVisible = true) }
        wakeComputerIfNeeded()
        control?.let(github::check)
    }

    fun dismissGitHub() {
        mutableUiState.update { it.copy(githubVisible = false) }
        // Deliberately does not answer an outstanding request: see [offerConnection].
        github.cancel()
    }

    fun connectGitHub(reason: String? = null) {
        val runtime = control ?: return showNotice("Your box is still starting.")
        github.connect(runtime, reason)
    }

    fun githubRepositoriesChosen() = github.repositoriesChosen()

    /** A token the user made themselves. Never stored by Box, never logged. */
    fun submitGitHubToken(token: String) = github.submitToken(token)

    fun disconnectGitHub() {
        control?.let(github::disconnect) ?: showNotice("Your box is still starting.")
    }

    // -----------------------------------------------------------------------
    // Signing in
    // -----------------------------------------------------------------------

    fun showSignIn() {
        mutableUiState.update { it.copy(signInVisible = true) }
        // The computer has to be up before Claude Code can be asked to log in at all.
        wakeComputerIfNeeded()
        control?.let(auth::check)
    }

    fun dismissSignIn() {
        mutableUiState.update { it.copy(signInVisible = false) }
    }

    fun beginSignIn() {
        val runtime = control ?: return showNotice("The computer is still starting.")
        auth.beginSignIn(runtime)
    }

    /** The code the user brought back from their browser. Never stored, never logged. */
    fun submitSignInCode(code: String) = auth.submitCode(code)

    fun cancelSignIn() = auth.cancel()

    /**
     * The box finished opening.
     *
     * The first one on this device takes the window — it is the only moment where saying what Box
     * can do costs nothing, because the user is already looking at the screen waiting for it. Every
     * later one is a line in the corner, on the way past.
     */
    private fun announceOpenBox() {
        if (openings.hasBeenGreeted()) {
            showNotice("Your box is open.")
            return
        }
        openings.rememberGreeting()
        mutableUiState.update { it.copy(readyGreeting = true) }
    }

    fun dismissReadyGreeting() {
        mutableUiState.update { it.copy(readyGreeting = false) }
    }

    /**
     * Who is driving.
     *
     * Told to the transport as well as recorded here, and from a scope that outlives the screen: a
     * key held down when the desktop goes away would otherwise stay held in the guest forever. A
     * composable cannot do this — its own scope is cancelled as it leaves, before the call runs.
     */
    /**
     * The size the guest last agreed to be. Null means "assume it is at whatever it boots with",
     * which is the honest state both before the first resize and after any guest restart.
     */
    private var appliedGuestScreen: GuestScreen? = null

    /**
     * Keep the guest's screen the same shape as the window showing it.
     *
     * The size is decided by the transport, the only thing that sees every view of the desktop at
     * once ([DesktopTransport.wantedGuestScreen]); this carries it to `:computer`, the only process
     * that reaches the guest. Neither half is a good home for both jobs, which is why the trip
     * exists.
     *
     * Two defences. A fold, rotation or DeX drag emits a run of sizes ending at the one that
     * matters, so [collectLatest] plus a settle delay lets each be cancelled by its successor — an
     * X mode set costs a full framebuffer over an emulated link, and paying that per frame of a
     * drag is worse than letterboxing. And a restarted guest is back at its built-in 1280x800 with
     * no idea Box asked for anything, so leaving `Ready` forgets what was applied and re-asks.
     */
    private fun followWindowWithGuestScreen() {
        val desktop = BoxContainer.desktop(getApplication())
        viewModelScope.launch {
            combine(
                desktop.wantedGuestScreen,
                mutableUiState.map { it.runtimeState }.distinctUntilChanged(),
            ) { wanted, state -> wanted to (state == RuntimeState.Ready) }
                .collectLatest { (wanted, ready) ->
                    if (!ready) {
                        appliedGuestScreen = null
                        return@collectLatest
                    }
                    // Cancelled outright if another size arrives first, which is the point.
                    delay(SCREEN_SETTLE_MILLIS)
                    val target = wanted ?: return@collectLatest
                    if (!GuestScreenFit.changeIsWorthIt(appliedGuestScreen, target)) return@collectLatest
                    val runtime = control ?: return@collectLatest
                    // Recorded before the call rather than after: it is oneway, so there is no
                    // "after", and a second identical request is worse than a missed one.
                    appliedGuestScreen = target
                    runCatching { runtime.setDisplaySize(target.width, target.height) }
                        .onFailure { appliedGuestScreen = null }
                }
        }
    }

    fun setDesktopControl(holder: ControlHolder) {
        mutableUiState.update { it.copy(desktopControl = holder) }
        viewModelScope.launch { BoxContainer.desktop(getApplication()).setControl(holder) }
    }

    /**
     * Opens something the agent is serving in the guest, over wherever the user already is.
     *
     * Deliberately no navigation. This used to send the user to the computer and draw the page as a
     * panel on the desktop, which was two mistakes at once: it took away the conversation that
     * offered the link — transcript, composer, and the thread of what they were doing — and it put
     * a web page in a container built for a tool over a machine, so a page arrived as a small card
     * parked over an xterm. A preview is not a fourth panel on the desktop; it is a thing to look
     * at, and it belongs over whatever you are doing, like every other sheet in Box.
     *
     * The forward is asked for every time rather than cached here: the runtime hands back the same
     * one for a guest port it has already opened, and that is the only place that can know whether
     * the VM has restarted underneath it since.
     */
    fun openPreview(artifact: Artifact.Preview) {
        val runtime = control ?: return showNotice("The computer is still starting.")
        // Up on the tap, before the forward exists, so the sheet can say it is coming.
        mutableUiState.update { it.copy(preview = OpenedPreview(guestPort = artifact.guestPort)) }
        runCatching {
            runtime.forwardPort(
                artifact.guestPort,
                object : IPortForwardCallback.Stub() {
                    override fun onForwarded(guestPort: Int, url: String) {
                        mutableUiState.update { state ->
                            // Only if this is still the preview being waited on: a sheet closed
                            // while the forward was being set up must not reopen when it lands.
                            val waiting = state.preview
                            if (waiting == null || waiting.guestPort != guestPort) {
                                return@update state
                            }
                            state.copy(preview = waiting.copy(url = url))
                        }
                    }

                    override fun onError(message: String) {
                        mutableUiState.update { it.copy(preview = null) }
                        showNotice(message)
                    }
                },
            )
        }.onFailure { error ->
            mutableUiState.update { it.copy(preview = null) }
            showNotice(error.message ?: "Box could not open that preview.")
        }
    }

    /**
     * Closes the preview and gives the port back.
     *
     * `release` is honoured rather than left to the VM's lifetime because a forward is a hole in
     * the phone's loopback interface: it should not outlive the sheet that asked for it.
     */
    fun closePreview() {
        val open = mutableUiState.value.preview ?: return
        mutableUiState.update { it.copy(preview = null) }
        runCatching { control?.releasePort(open.guestPort) }
    }

    // -----------------------------------------------------------------------
    // Computer
    // -----------------------------------------------------------------------

    /**
     * Open the box.
     *
     * One verb for what used to be three buttons — set up, start, try again. The distinction was
     * the runtime's, not the user's: unpacking the image happens inside `ACTION_START` when it is
     * needed, and "try again" is the same request after a failure.
     */
    fun openBox() = start()

    fun start() {
        // Optimistic only until the first broadcast lands; the service reports every later state,
        // including an immediate failure.
        mutableUiState.update {
            it.copy(
                runtimeState = RuntimeState.Starting,
                // Starts the clock the progress indicator runs on. Kept across the whole opening,
                // including the transient Stopped between unpacking and booting.
                openingSince = it.openingSince ?: SystemClock.elapsedRealtime(),
            )
        }
        getApplication<Application>().startForegroundService(
            Intent(getApplication(), RuntimeService::class.java)
                .setAction(RuntimeService.ACTION_START)
                // Carried on the start rather than sent after it: `:computer` is a fresh process
                // here and has no other way to know, and the broadcast below would arrive before
                // it had a receiver registered.
                .putExtra(RuntimeService.EXTRA_KEEP_SAVED, mutableUiState.value.openFaster)
                .withGuestSizing(),
        )
    }

    /** The size of the machine, carried on every start because that is the only time it applies. */
    private fun Intent.withGuestSizing(): Intent = apply {
        putExtra(RuntimeService.EXTRA_MEMORY_MB, mutableUiState.value.guestSizing.memoryMb)
        putExtra(RuntimeService.EXTRA_PROCESSORS, mutableUiState.value.guestSizing.processors)
    }

    /**
     * How much of the phone the box gets.
     *
     * Takes effect at the next open and never sooner: `-m` and `-smp` are handed to QEMU once, and
     * a running guest has no idea how to grow. The larger cost is quieter — the size is part of
     * the machine fingerprint, so a box saved by "Open faster" no longer matches and is discarded
     * on the way up. Changing this turns one reopen into a full boot, which is why the sheet says
     * so rather than leaving the user to discover it.
     */
    fun setGuestSizing(sizing: GuestSizing) {
        preferences.edit()
            .putInt(GUEST_MEMORY_KEY, sizing.memoryMb)
            .putInt(GUEST_PROCESSORS_KEY, sizing.processors)
            .apply()
        mutableUiState.update { it.copy(guestSizing = sizing) }
    }

    /**
     * "Open faster": keep a saved copy of the box, so opening it is a second rather than a boot.
     *
     * The cost is the honest half of the switch — the saved guest is about 430 MB inside the system
     * disk. Turning it off does not go and delete that; the next open discards whatever snapshot is
     * there on its way up, so the space comes back without a sweep that could race the VM.
     */
    fun setOpenFaster(enabled: Boolean) {
        preferences.edit().putBoolean(OPEN_FASTER_KEY, enabled).apply()
        mutableUiState.update { it.copy(openFaster = enabled) }
        // Reaches `:computer` if it is running and is dropped if it is not, which is what should
        // happen: a box that is not up has nothing to save, and [start] will carry the value.
        getApplication<Application>().sendBroadcast(
            Intent(RuntimeService.ACTION_SET_KEEP_SAVED)
                .setPackage(getApplication<Application>().packageName)
                .putExtra(RuntimeService.EXTRA_KEEP_SAVED, enabled),
        )
    }

    /**
     * Put the box away: save the guest as it stands, and end the machine.
     *
     * The difference from [stop] is entirely in what the next [openBox] costs — about a second
     * against a boot — so this is the one to reach for whenever the box is simply not being used.
     * The one thing it cannot carry across is an agent that is still working; the guest kills what
     * it was running when the host goes away.
     */
    fun putAway() {
        getApplication<Application>().startForegroundService(
            Intent(getApplication(), RuntimeService::class.java).setAction(RuntimeService.ACTION_SUSPEND),
        )
        mutableUiState.update {
            it.copy(
                runtimeState = RuntimeState.Suspending,
                openingSince = null,
                runningCommand = null,
                openedFile = null,
            )
        }
    }

    fun stop() {
        getApplication<Application>().startForegroundService(
            Intent(getApplication(), RuntimeService::class.java).setAction(RuntimeService.ACTION_STOP),
        )
        mutableUiState.update {
            it.copy(
                runtimeState = RuntimeState.Stopped,
                // Nobody is waiting any more, so Stopped means the box is closed rather than
                // halfway open. See [BoxUiState.boxStage].
                openingSince = null,
                runningCommand = null,
                openedFile = null,
            )
        }
    }

    /**
     * Fold a completed opening into what the next one is expected to cost.
     *
     * The estimate is per device and survives restarts, because the number that matters is how
     * long *this* phone takes — a Fold 7 measured 168 s cold and 252 s with the SoC already hot,
     * and a figure baked into the app can never know which one it is looking at.
     */
    private fun rememberHowLongThatTook() {
        val startedAt = mutableUiState.value.openingSince ?: return
        val observed = SystemClock.elapsedRealtime() - startedAt
        val learned = BoxProgress.learn(openings.expectedMillis(), observed)
        openings.record(learned)
        mutableUiState.update { it.copy(expectedOpenMillis = learned) }
    }

    /**
     * Whether anyone is still waiting on the box after a state change.
     *
     * The awkward case is `Stopped`. Unpacking the image reports it on success, one broadcast
     * before `Starting` — so mid-opening it means progress, and at any other time it means the
     * opening is over. Getting this wrong strands the hero on a progress bar for a box that is
     * not coming.
     */
    private fun openingAfter(since: Long?, previous: RuntimeState, next: RuntimeState): Long? = when {
        since == null -> null
        next == RuntimeState.Ready -> null
        next is RuntimeState.Failed -> null
        next == RuntimeState.Stopped && previous !is RuntimeState.Provisioning -> null
        else -> since
    }

    fun runCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || mutableUiState.value.runningCommand != null) return
        val runtime = control ?: return showNotice("Box is not connected yet.")

        mutableUiState.update { it.copy(runningCommand = trimmed) }
        val workingDirectory = mutableUiState.value.currentPath
        runCatching {
            runtime.exec(
                arrayOf("/bin/sh", "-lc", trimmed),
                workingDirectory,
                COMMAND_TIMEOUT_SECONDS,
                object : IExecCallback.Stub() {
                    override fun onResult(exitCode: Int, stdout: String, stderr: String, truncated: Boolean) {
                        recordCommand(trimmed, exitCode, stdout, stderr, truncated)
                    }

                    override fun onError(message: String) {
                        recordCommand(trimmed, exitCode = -1, stdout = "", stderr = message, truncated = false)
                    }
                },
            )
        }.onFailure { error ->
            mutableUiState.update { it.copy(runningCommand = null) }
            showNotice(error.message ?: "Box could not run that command.")
        }
    }

    private fun recordCommand(
        command: String,
        exitCode: Int,
        stdout: String,
        stderr: String,
        truncated: Boolean,
    ) {
        val record = CommandRecord(
            id = ids.incrementAndGet(),
            command = command,
            stdout = if (truncated) "$stdout\n… output truncated" else stdout,
            stderr = stderr,
            exitCode = exitCode,
        )
        mutableUiState.update {
            it.copy(commandHistory = it.commandHistory + record, runningCommand = null)
        }
    }

    fun openDirectory(path: String) {
        if (mutableUiState.value.filesPlace == FilesPlace.Shared) return openSharedDirectory(path)
        mutableUiState.update { it.copy(currentPath = path, openedFile = null) }
        refreshFiles()
    }

    fun navigateUp() {
        if (mutableUiState.value.filesPlace == FilesPlace.Shared) {
            val here = mutableUiState.value.sharedPath
            if (here.isEmpty()) return
            return openSharedDirectory(here.substringBeforeLast('/', ""))
        }
        val current = mutableUiState.value.currentPath
        if (current == "/workspace") return
        val parent = current.substringBeforeLast('/').ifBlank { "/workspace" }
        openDirectory(if (parent.startsWith("/workspace")) parent else "/workspace")
    }

    fun refreshFiles() {
        if (mutableUiState.value.filesPlace == FilesPlace.Shared) return refreshSharedFiles()
        val runtime = control ?: return
        val path = mutableUiState.value.currentPath
        mutableUiState.update { it.copy(filesLoading = true) }
        runCatching {
            runtime.listFiles(
                path,
                object : IFileListCallback.Stub() {
                    override fun onResult(
                        paths: Array<out String>,
                        names: Array<out String>,
                        directories: BooleanArray,
                        sizes: LongArray,
                    ) {
                        val entries = paths.indices.map { index ->
                            FileEntry(paths[index], names[index], directories[index], sizes[index])
                        }
                        mutableUiState.update { it.copy(files = entries, filesLoading = false) }
                    }

                    override fun onError(message: String) {
                        mutableUiState.update { it.copy(files = emptyList(), filesLoading = false) }
                        showNotice(message)
                    }
                },
            )
        }.onFailure { error ->
            mutableUiState.update { it.copy(filesLoading = false) }
            showNotice(error.message ?: "Box could not read that folder.")
        }
    }

    /**
     * Opens an artifact the agent offered, in the panel that already knows how to show a file.
     *
     * Not a new surface. The Files panel is a view onto the machine that floats over it one at a
     * time — exactly what a document is — and it already reads a guest path, truncates the same
     * way, and closes the same way. Adding a second viewer would be two places to keep right about
     * what "too big" means.
     */
    fun openDocument(artifact: Artifact.Document) {
        mutableUiState.update {
            it.copy(
                destination = BoxDestination.Computer,
                computerPanel = ComputerPanel.Files,
                filesPlace = FilesPlace.InTheBox,
            )
        }
        openFile(
            FileEntry(
                path = artifact.guestPath,
                name = artifact.name,
                isDirectory = false,
                size = 0L,
            ),
        )
    }

    fun openFile(entry: FileEntry) {
        if (entry.isDirectory) return openDirectory(entry.path)
        if (mutableUiState.value.filesPlace == FilesPlace.Shared) return openSharedFile(entry)
        val runtime = control ?: return showNotice("Box is not connected yet.")
        mutableUiState.update { it.copy(openingFilePath = entry.path) }
        runCatching {
            runtime.readFile(
                entry.path,
                object : IFileReadCallback.Stub() {
                    override fun onResult(path: String, name: String, content: String, truncated: Boolean) {
                        mutableUiState.update {
                            it.copy(
                                openingFilePath = null,
                                openedFile = OpenedFile(path, name, content, truncated),
                            )
                        }
                    }

                    override fun onError(message: String) {
                        mutableUiState.update { it.copy(openingFilePath = null) }
                        showNotice(message)
                    }
                },
            )
        }.onFailure { error ->
            mutableUiState.update { it.copy(openingFilePath = null) }
            showNotice(error.message ?: "Box could not open that file.")
        }
    }

    fun closeFile() {
        mutableUiState.update { it.copy(openedFile = null) }
    }

    // -----------------------------------------------------------------------
    // The shared folder
    // -----------------------------------------------------------------------

    /**
     * Browsing the phone's own copy, which is why none of this goes near the runtime.
     *
     * Reading it here rather than through `:computer` is the point of the design: these are local
     * files, so the panel works with the box closed, mid-boot, or broken. The guest's copy is
     * kept level by [dev.localagent.runtime.qemu.shared.SharedFolderBridge], which runs in the
     * process that owns the VM and needs nothing from this one.
     */
    fun selectFilesPlace(place: FilesPlace) {
        if (mutableUiState.value.filesPlace == place) return
        mutableUiState.update { it.copy(filesPlace = place, openedFile = null) }
        if (place == FilesPlace.Shared) refreshSharedFiles() else refreshFiles()
    }

    fun openSharedDirectory(relativePath: String) {
        mutableUiState.update { it.copy(sharedPath = relativePath.trim('/'), openedFile = null) }
        refreshSharedFiles()
    }

    fun refreshSharedFiles() {
        val relative = mutableUiState.value.sharedPath
        viewModelScope.launch {
            val (entries, note) = withContext(Dispatchers.IO) {
                val root = SharedFolder.on(getApplication())
                val here = if (relative.isEmpty()) root else File(root, relative)
                val listed = here.listFiles().orEmpty().map { file ->
                    FileEntry(
                        path = listOf(relative, file.name).filter(String::isNotEmpty).joinToString("/"),
                        name = file.name,
                        isDirectory = file.isDirectory,
                        size = file.length(),
                    )
                }
                listed to SharedFolder.records(getApplication()).lastOutcome()?.let { outcome ->
                    SharedSyncNote(
                        atMillis = outcome.atMillis,
                        pushedIn = outcome.pushedIn.size,
                        broughtOut = outcome.broughtOut.size,
                        kept = outcome.kept,
                        trouble = outcome.trouble.map { it.path },
                    )
                }
            }
            mutableUiState.update { it.copy(sharedFiles = entries, sharedSync = note) }
        }
    }

    private fun openSharedFile(entry: FileEntry) {
        mutableUiState.update { it.copy(openingFilePath = entry.path) }
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(SharedFolder.on(getApplication()), entry.path)
                    val bytes = file.readBytes()
                    val text = bytes.toString(Charsets.UTF_8)
                    check(!text.contains('\u0000')) { "This looks like a binary file" }
                    val preview = text.take(MAX_PREVIEW_CHARS)
                    OpenedFile(
                        path = "Shared/${entry.path}",
                        name = entry.name,
                        content = preview,
                        truncated = preview.length < text.length,
                    )
                }
            }
            mutableUiState.update { it.copy(openingFilePath = null) }
            opened
                .onSuccess { file -> mutableUiState.update { it.copy(openedFile = file) } }
                .onFailure { error -> showNotice(error.message ?: "Box could not open that file.") }
        }
    }

    /**
     * Hand the folder to the phone's own Files app.
     *
     * Browsing here is read-only on purpose — creating, renaming and deleting already work in
     * Files and in every Open/Save dialog, and reimplementing them inside Box would be three more
     * surfaces to keep correct for no new ability. This button is the bridge to where those live.
     */
    fun openSharedInPhoneFiles() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                DocumentsContract.buildDocumentUri(
                    SharedFolder.authority(getApplication()),
                    SharedFolder.ROOT_DOCUMENT_ID,
                ),
                DocumentsContract.Document.MIME_TYPE_DIR,
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure {
                showNotice("This phone has no Files app that can open the folder.")
            }
    }

    fun noticeShown() {
        mutableUiState.update { it.copy(notice = null) }
    }

    private fun showNotice(message: String) {
        mutableUiState.update { it.copy(notice = UiNotice(ids.incrementAndGet(), message)) }
    }

    private companion object {
        const val TAG = "BoxViewModel"

        /** See [setOpenFaster]. Absent means true: saving an idle box is the older behaviour. */
        const val OPEN_FASTER_KEY = "open_faster"
        const val GUEST_MEMORY_KEY = "guest_memory_mb"
        const val GUEST_PROCESSORS_KEY = "guest_processors"

        /**
         * The image a saved box was last seeded for, so a guest that cannot boot cannot cost a
         * boot on every launch. See [seedSavedBoxIfImageIsPending].
         */
        const val SEEDED_IMAGE_KEY = "seeded_image"

        /**
         * Below this, a background boot is not a trade worth making on the user's behalf.
         *
         * Deliberately low. The point of the number is to refuse the case where the answer is
         * obvious to anybody looking at the phone, not to build a power policy — a threshold set
         * where it started declining ordinary afternoons would quietly turn this feature off.
         */
        const val MINIMUM_SEED_BATTERY_PERCENT = 25

        const val COMMAND_TIMEOUT_SECONDS = 120

        /**
         * How long a window size has to hold still before the guest is asked to match it. Long
         * enough to swallow a fold or a rotation, short enough that letting go of a DeX window
         * edge and the desktop filling it read as one action.
         */
        const val SCREEN_SETTLE_MILLIS = 450L

        /** Matches what `:computer` allows a guest preview, so the two places read the same. */
        const val MAX_PREVIEW_CHARS = 128 * 1024
    }
}
