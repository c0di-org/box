package dev.localagent.workstation

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.runtime.qemu.RuntimeService
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

    fun selectDestination(destination: BoxDestination) {
        mutableUiState.update { it.copy(destination = destination) }
    }

    fun setupAndStart() = start()

    fun start() {
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
        showNotice("The runtime control channel is still connecting.")
    }

    fun openDirectory(path: String) {
        mutableUiState.update { it.copy(currentPath = path, openedFile = null) }
    }

    fun navigateUp() {
        val current = mutableUiState.value.currentPath
        if (current == "/workspace") return
        val parent = current.substringBeforeLast('/').ifBlank { "/workspace" }
        openDirectory(if (parent.startsWith("/workspace")) parent else "/workspace")
    }

    fun refreshFiles() = Unit

    fun openFile(entry: FileEntry) {
        showNotice("File preview will be available when the runtime control channel is ready.")
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
}
