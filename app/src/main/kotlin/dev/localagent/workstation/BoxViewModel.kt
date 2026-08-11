package dev.localagent.workstation

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
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
import dev.localagent.workstation.agent.AgentBackend
import dev.localagent.workstation.agent.AgentEvent
import dev.localagent.workstation.agent.FakeAgentBackend
import dev.localagent.workstation.agent.PermissionDecision
import dev.localagent.workstation.agent.SessionConnection
import dev.localagent.workstation.agent.TranscriptBuilder
import dev.localagent.workstation.computer.ControlHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private var transcriptJob: Job? = null
    private var connectionJob: Job? = null

    /** RuntimeService owns the VM in another process; this is the only source of runtime truth. */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val payload = intent?.getBundleExtra(RuntimeService.EXTRA_STATE) ?: return
            val state = RuntimeStateCodec.decode(payload) ?: return
            mutableUiState.update { it.copy(runtimeState = state) }
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
        // Files opens on /workspace, so fill it as soon as the guest can answer.
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
        ContextCompat.registerReceiver(
            getApplication(),
            stateReceiver,
            IntentFilter(RuntimeService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        resyncRuntimeState()
        observeAgents()
        viewModelScope.launch {
            auth.state.collect { signIn -> mutableUiState.update { it.copy(signIn = signIn) } }
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
        super.onCleared()
    }

    // -----------------------------------------------------------------------
    // Conversations
    // -----------------------------------------------------------------------

    fun selectDestination(destination: BoxDestination) {
        mutableUiState.update { it.copy(destination = destination) }
    }

    fun selectComputerTool(tool: ComputerTool) {
        mutableUiState.update { it.copy(computerTool = tool, openedFile = null) }
        if (tool == ComputerTool.Files) refreshFiles()
    }

    fun toggleHarness(harnessId: String) {
        mutableUiState.update { state ->
            val collapsed = state.collapsedHarnesses
            state.copy(
                collapsedHarnesses = if (harnessId in collapsed) collapsed - harnessId else collapsed + harnessId,
            )
        }
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
        if (decision is PermissionDecision.AllowAlways) {
            mutableUiState.update { it.copy(alwaysAllowed = it.alwaysAllowed + decision.scope) }
        }
        viewModelScope.launch { agents.resolvePermission(sessionId, requestId, decision) }
    }

    fun interruptSession() {
        val sessionId = mutableUiState.value.selectedSessionId ?: return
        viewModelScope.launch { agents.interrupt(sessionId) }
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
     * Show the guest's screen full window.
     *
     * Starts the computer if it is off, for the same reason sending a message does: the answer to
     * "show me the machine" is never "no", it is "in a moment".
     */
    fun openDesktop() {
        wakeComputerIfNeeded()
        mutableUiState.update { it.copy(desktopVisible = true) }
    }

    /** Control returns to the agent on the way out; see [BoxUiState.desktopControl]. */
    fun closeDesktop() {
        mutableUiState.update { it.copy(desktopVisible = false, desktopControl = ControlHolder.Agent) }
    }

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

    fun setupAndStart() = start()

    fun start() {
        // Optimistic only until the first broadcast lands; the service reports every later state,
        // including an immediate failure.
        mutableUiState.update { it.copy(runtimeState = RuntimeState.Starting) }
        getApplication<Application>().startForegroundService(
            Intent(getApplication(), RuntimeService::class.java).setAction(RuntimeService.ACTION_START),
        )
    }

    fun stop() {
        getApplication<Application>().startForegroundService(
            Intent(getApplication(), RuntimeService::class.java).setAction(RuntimeService.ACTION_STOP),
        )
        mutableUiState.update {
            it.copy(runtimeState = RuntimeState.Stopped, runningCommand = null, openedFile = null)
        }
    }

    fun retry() = start()

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
        mutableUiState.update { it.copy(currentPath = path, openedFile = null) }
        refreshFiles()
    }

    fun navigateUp() {
        val current = mutableUiState.value.currentPath
        if (current == "/workspace") return
        val parent = current.substringBeforeLast('/').ifBlank { "/workspace" }
        openDirectory(if (parent.startsWith("/workspace")) parent else "/workspace")
    }

    fun refreshFiles() {
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

    fun noticeShown() {
        mutableUiState.update { it.copy(notice = null) }
    }

    private fun showNotice(message: String) {
        mutableUiState.update { it.copy(notice = UiNotice(ids.incrementAndGet(), message)) }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 120
    }
}
