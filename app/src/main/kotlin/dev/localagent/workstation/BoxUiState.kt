package dev.localagent.workstation

import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.agent.HarnessDescriptor
import dev.localagent.workstation.agent.SessionConnection
import dev.localagent.workstation.agent.SessionStatus
import dev.localagent.workstation.agent.SessionSummary
import dev.localagent.workstation.agent.Transcript

/**
 * Box's two top-level surfaces. The VM is substrate, so it gets one destination; the conversation
 * with the agent gets the other, and it is where the app opens.
 */
enum class BoxDestination { Conversations, Computer }

/** Secondary tools inside Computer. Not top-level destinations any more. */
enum class ComputerTool { Overview, Terminal, Files }

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

/** One harness and its sessions, in the order the session list draws them. */
data class HarnessGroup(
    val harness: HarnessDescriptor,
    val sessions: List<SessionSummary>,
) {
    val activeCount: Int
        get() = sessions.count { it.status is SessionStatus.Active || it.status is SessionStatus.NeedsYou }
}

data class BoxUiState(
    val runtimeState: RuntimeState = RuntimeState.NotProvisioned,
    val destination: BoxDestination = BoxDestination.Conversations,

    // ---- conversations ----
    val harnesses: List<HarnessDescriptor> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val collapsedHarnesses: Set<String> = emptySet(),
    val selectedSessionId: String? = null,
    val transcript: Transcript? = null,
    val transcriptLoading: Boolean = false,
    val connection: SessionConnection = SessionConnection.Live,
    /** Scopes the user granted with "Always allow". Suppresses matching sheets. */
    val alwaysAllowed: Set<String> = emptySet(),
    val startingSession: Boolean = false,

    // ---- computer ----
    val computerTool: ComputerTool = ComputerTool.Overview,
    val commandHistory: List<CommandRecord> = emptyList(),
    val runningCommand: String? = null,
    val currentPath: String = "/workspace",
    val files: List<FileEntry> = emptyList(),
    val filesLoading: Boolean = false,
    val openingFilePath: String? = null,
    val openedFile: OpenedFile? = null,

    val notice: UiNotice? = null,
) {
    val selectedSession: SessionSummary?
        get() = sessions.firstOrNull { it.id == selectedSessionId }

    /** Harness-grouped session list, harnesses in declaration order, sessions newest first. */
    val groups: List<HarnessGroup>
        get() = harnesses.map { harness ->
            HarnessGroup(
                harness = harness,
                sessions = sessions
                    .filter { it.harnessId == harness.id }
                    .sortedByDescending { it.updatedAt },
            )
        }

    /** The computer can be reached but is not usable yet. Chat never blocks on this. */
    val computerReady: Boolean
        get() = runtimeState == RuntimeState.Ready

    val computerBusy: Boolean
        get() = runtimeState == RuntimeState.Starting ||
            runtimeState == RuntimeState.Connecting ||
            runtimeState is RuntimeState.Provisioning
}
