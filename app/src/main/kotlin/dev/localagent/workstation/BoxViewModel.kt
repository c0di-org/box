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
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.runtime.qemu.IExecCallback
import dev.localagent.runtime.qemu.IFileListCallback
import dev.localagent.runtime.qemu.IFileReadCallback
import dev.localagent.runtime.qemu.IRuntimeControl
import dev.localagent.runtime.qemu.RuntimeService
import dev.localagent.runtime.qemu.RuntimeStateCodec
import dev.localagent.runtime.qemu.RuntimeStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Product state for Box. Runtime operations are intentionally routed through RuntimeService;
 * the VM is never instantiated in the UI process.
 */
class BoxViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableUiState = MutableStateFlow(BoxUiState())
    val uiState: StateFlow<BoxUiState> = mutableUiState.asStateFlow()
    private val ids = AtomicLong()

    /** RuntimeService owns the VM in another process; this is the only source of runtime truth. */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val payload = intent?.getBundleExtra(RuntimeService.EXTRA_STATE) ?: return
            val state = RuntimeStateCodec.decode(payload) ?: return
            mutableUiState.update { it.copy(runtimeState = state) }
            if (state == RuntimeState.Ready) bindRuntime() else releaseRuntime()
        }
    }

    @Volatile private var control: IRuntimeControl? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            control = IRuntimeControl.Stub.asInterface(binder)
            // Files opens on /workspace, so fill it as soon as the guest can answer.
            refreshFiles()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            control = null
        }
    }

    /** Only ever called once the VM reports Ready, so this never starts `:computer` on its own. */
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

    fun selectDestination(destination: BoxDestination) {
        mutableUiState.update { it.copy(destination = destination) }
    }

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
