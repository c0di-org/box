package dev.localagent.workstation

import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState

enum class BoxDestination { Home, Terminal, Files }

data class CommandRecord(
    val id: Long,
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

data class OpenedFile(
    val path: String,
    val name: String,
    val content: String,
    val truncated: Boolean,
)

data class UiNotice(val id: Long, val message: String)

data class BoxUiState(
    val runtimeState: RuntimeState = RuntimeState.NotProvisioned,
    val destination: BoxDestination = BoxDestination.Home,
    val commandHistory: List<CommandRecord> = emptyList(),
    val runningCommand: String? = null,
    val currentPath: String = "/workspace",
    val files: List<FileEntry> = emptyList(),
    val filesLoading: Boolean = false,
    val openingFilePath: String? = null,
    val openedFile: OpenedFile? = null,
    val notice: UiNotice? = null,
)
