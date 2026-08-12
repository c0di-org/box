package dev.localagent.workstation

import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.agent.HarnessDescriptor
import dev.localagent.workstation.agent.HarnessMarkKind
import dev.localagent.workstation.agent.SessionStatus
import dev.localagent.workstation.agent.SessionSummary
import dev.localagent.workstation.computer.ControlHolder
import dev.localagent.workstation.computer.DesktopInput
import dev.localagent.workstation.computer.DesktopState
import dev.localagent.workstation.computer.DesktopTransport
import dev.localagent.workstation.ui.BoxApp
import dev.localagent.workstation.ui.BoxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug-only: the whole shell against a canned state, with no VM under it.
 *
 * Every interesting moment in Box's UI needs a Linux machine to exist — an emulator has none, and
 * the states worth looking at (the opening, the arrival, the computer) are exactly the ones a
 * developer cannot reach on their desk. `adb shell am start -n <pkg>/dev.localagent.workstation
 * .UiGalleryActivity --es scene computer`.
 */
class UiGalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scene = intent?.getStringExtra("scene") ?: "open"
        setContent {
            var state by remember { mutableStateOf(sceneState(scene)) }
            BoxTheme {
                BoxApp(
                    state = state,
                    onDestinationSelected = { state = state.copy(destination = it) },
                    onSelectSession = { state = state.copy(selectedSessionId = it) },
                    onNewConversation = {},
                    onSend = {},
                    onInterrupt = {},
                    onPermissionDecision = { _, _ -> },
                    onOpenArtifact = {},
                    onCloseSession = {},
                    onSelectComputerPanel = {
                        state = state.copy(
                            computerPanel = if (state.computerPanel == it) ComputerPanel.None else it,
                        )
                    },
                    onOpenBox = {},
                    onStop = {},
                    onRunCommand = {},
                    onOpenDirectory = {},
                    onNavigateUp = {},
                    onRefreshFiles = {},
                    onOpenFile = {},
                    onCloseFile = {},
                    onNoticeShown = {},
                    onDismissGreeting = { state = state.copy(readyGreeting = false) },
                    desktop = StubDesktop,
                    onSetDesktopControl = { state = state.copy(desktopControl = it) },
                )
            }
        }
    }
}

private val harness = HarnessDescriptor(
    id = "claude-code",
    name = "Claude Code",
    command = "claude",
    mark = HarnessMarkKind.Burst,
)

private fun tasks() = listOf(
    SessionSummary(
        id = "s1",
        harnessId = harness.id,
        title = "Clone the project and run its tests",
        status = SessionStatus.Active,
        updatedAt = 3L,
        preview = "Running the suite…",
    ),
    SessionSummary(
        id = "s2",
        harnessId = harness.id,
        title = "Set up a Python virtualenv",
        status = SessionStatus.Finished,
        updatedAt = 2L,
        preview = "Done — 12 packages installed",
    ),
)

private fun sceneState(scene: String): BoxUiState {
    val base = BoxUiState(harnesses = listOf(harness))
    return when (scene) {
        "closed" -> base
        "opening" -> base.copy(
            runtimeState = RuntimeState.Connecting,
            openingSince = android.os.SystemClock.elapsedRealtime() - 70_000L,
        )
        "opening-with-tasks" -> base.copy(
            runtimeState = RuntimeState.Connecting,
            openingSince = android.os.SystemClock.elapsedRealtime() - 70_000L,
            sessions = tasks(),
        )
        "greeting" -> base.copy(runtimeState = RuntimeState.Ready, readyGreeting = true)
        "computer" -> base.copy(
            runtimeState = RuntimeState.Ready,
            sessions = tasks(),
            destination = BoxDestination.Computer,
            desktopControl = ControlHolder.User,
        )
        "computer-chat" -> base.copy(
            runtimeState = RuntimeState.Ready,
            sessions = tasks(),
            selectedSessionId = "s1",
            destination = BoxDestination.Computer,
            desktopControl = ControlHolder.User,
            computerPanel = ComputerPanel.Chat,
        )
        "computer-cold" -> base.copy(destination = BoxDestination.Computer)
        else -> base.copy(runtimeState = RuntimeState.Ready, sessions = tasks())
    }
}

/** Reports a live screen and paints nothing. Layout is the only thing this is for. */
private object StubDesktop : DesktopTransport {
    override val state: StateFlow<DesktopState> =
        MutableStateFlow(DesktopState.Live(1280, 800, ControlHolder.User))

    override suspend fun attach(surface: Surface, widthPx: Int, heightPx: Int) = Unit
    override suspend fun detach(surface: Surface) = Unit
    override suspend fun send(input: DesktopInput) = Unit
    override suspend fun setControl(holder: ControlHolder) = Unit
}
