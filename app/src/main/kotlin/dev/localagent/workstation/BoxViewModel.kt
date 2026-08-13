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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.runtime.qemu.IExecCallback
import dev.localagent.runtime.qemu.IFileListCallback
import dev.localagent.runtime.qemu.IFileReadCallback
import dev.localagent.runtime.qemu.IRuntimeControl
import dev.localagent.runtime.qemu.RuntimeService
import dev.localagent.runtime.qemu.RuntimeStateCodec
import dev.localagent.runtime.qemu.RuntimeStorage
import dev.localagent.runtime.qemu.shared.SharedFolder
import dev.localagent.workstation.agent.AgentBackend
import dev.localagent.workstation.agent.AgentEvent
import dev.localagent.workstation.agent.AgentPermissionMode
import dev.localagent.workstation.agent.AgentViewport
import dev.localagent.workstation.agent.FakeAgentBackend
import dev.localagent.workstation.agent.PermissionDecision
import dev.localagent.workstation.agent.SessionConnection
import dev.localagent.workstation.agent.TranscriptBuilder
import dev.localagent.workstation.computer.ControlHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val mutableUiState = MutableStateFlow(BoxUiState())
    val uiState: StateFlow<BoxUiState> = mutableUiState.asStateFlow()
    private val ids = AtomicLong()

    private val agents: AgentBackend = backend ?: FakeAgentBackend(viewModelScope)
    private val auth = BoxContainer.auth
    private val openings = OpeningHistory(application)
    private var transcriptJob: Job? = null
    private var connectionJob: Job? = null

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
        observeAgents()
        watchSharedFolder()
        viewModelScope.launch {
            auth.state.collect { signIn -> mutableUiState.update { it.copy(signIn = signIn) } }
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
        val provisioned = runCatching {
            RuntimeStorage(getApplication()).hasHeadlessBootSet()
        }.getOrDefault(false)
        if (provisioned) {
            mutableUiState.update { it.copy(runtimeState = RuntimeState.Stopped) }
        }
        getApplication<Application>().sendBroadcast(
            Intent(RuntimeService.ACTION_QUERY_STATE)
                .setPackage(getApplication<Application>().packageName),
        )
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
            )
        }
        val id = sessionId ?: return

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
     * Sending is never refused because the computer is off.
     *
     * A message to a cold runtime starts it and is held until the guest can take it — the backend
     * queues the write, and the boot is ~3 minutes of visible, normal waiting rather than an error.
     * The text is shown as queued in the meantime so the user's own words never vanish for the
     * length of a boot; the harness echoes each prompt into the session log when it finally runs,
     * and that echo is what clears the queued copy.
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        wakeComputerIfNeeded()
        val sessionId = mutableUiState.value.selectedSessionId
        mutableUiState.update { it.copy(queued = it.queued + QueuedPrompt(sessionId, trimmed)) }

        viewModelScope.launch {
            if (sessionId == null) {
                val harness = mutableUiState.value.harnesses.firstOrNull() ?: return@launch
                startSession(harness.id, trimmed)
            } else {
                agents.send(sessionId, trimmed)
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

    fun startSession(harnessId: String, prompt: String? = null) {
        if (mutableUiState.value.startingSession) return
        mutableUiState.update { it.copy(startingSession = true) }
        viewModelScope.launch {
            val id = runCatching { agents.startSession(harnessId, prompt) }
                .onFailure { error -> showNotice(error.message ?: "Box could not start that session.") }
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
        }
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

    fun closeSession(sessionId: String) {
        viewModelScope.launch {
            agents.closeSession(sessionId)
            if (mutableUiState.value.selectedSessionId == sessionId) selectSession(null)
        }
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
    fun setDesktopControl(holder: ControlHolder) {
        mutableUiState.update { it.copy(desktopControl = holder) }
        viewModelScope.launch { BoxContainer.desktop(getApplication()).setControl(holder) }
    }

    /** Port forwarding still does not exist, so a preview says so rather than opening nothing. */
    fun openPreview(label: String) {
        showNotice("$label isn’t connected yet — the runtime transport for it is still being built.")
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
            Intent(getApplication(), RuntimeService::class.java).setAction(RuntimeService.ACTION_START),
        )
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
        const val COMMAND_TIMEOUT_SECONDS = 120

        /** Matches what `:computer` allows a guest preview, so the two places read the same. */
        const val MAX_PREVIEW_CHARS = 128 * 1024
    }
}
