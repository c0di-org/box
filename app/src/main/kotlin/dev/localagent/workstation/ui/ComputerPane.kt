package dev.localagent.workstation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.workstation.BoxProgress
import dev.localagent.workstation.BoxUiState
import dev.localagent.workstation.FilesPlace
import dev.localagent.workstation.ComputerPanel
import dev.localagent.workstation.computer.ControlHolder
import dev.localagent.workstation.computer.DesktopInput
import dev.localagent.workstation.computer.DesktopTransport
import dev.localagent.workstation.computer.GuestPointer
import dev.localagent.workstation.computer.rememberHardwareInput
import kotlinx.coroutines.launch

/**
 * The computer.
 *
 * Not a tab with a picture of a machine on it — the machine, taking the whole window, with a real
 * pointer and a real keyboard going into it. Someone who never says a word to an agent should be
 * able to install Box, press Computer, and be using Debian; that is the whole test this surface has
 * to pass, and the old Overview/Terminal/Files switcher failed it by making the desktop one of
 * three equal things and the only interactive copy of it a modal two taps further in.
 *
 * Everything else Box can do here floats *over* the desktop instead of beside it — the agent, a
 * shell, the workspace — one panel at a time, closable back to nothing. See [ComputerPanel].
 */
@Composable
fun ComputerPane(
    state: BoxUiState,
    progress: BoxProgress,
    desktop: DesktopTransport?,
    onBack: (() -> Unit)?,
    onSelectPanel: (ComputerPanel) -> Unit,
    onSetControl: (ControlHolder) -> Unit,
    onOpenBox: () -> Unit,
    onStop: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onRunCommand: (String) -> Unit,
    onSelectFilesPlace: (FilesPlace) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onCloseFile: () -> Unit,
    onOpenInPhoneFiles: () -> Unit,
    chat: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // Held so the pointer scale and the system IME have something to work against. The desktop is
    // a SurfaceView, so there is no editor for Android to find on its own.
    var surface by remember { mutableStateOf<DesktopView?>(null) }
    val live = desktop != null && state.computerReady
    val driving = state.desktopControl == ControlHolder.User
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember(context) { KeyboardPrefs(context) }

    /**
     * The one cursor. Both the desktop's own touch handling and the on-screen keyboard's trackpad
     * move it, and it is what turns their relative gestures back into the absolute coordinate RFB
     * wants — see [GuestPointer].
     */
    val pointer = remember(desktop) {
        GuestPointer { input -> desktop?.let { transport -> scope.launch { transport.send(input) } } }
    }

    // Watched rather than asked once: plugging a keyboard in has to take the drawn one away in the
    // same beat, or the promise that nobody ever goes looking for a setting is broken.
    val hardware = rememberHardwareInput()
    var mode by remember { mutableStateOf(prefs.mode) }
    // Mirrored into state because a `SharedPreferences` read during composition is invisible to
    // Compose: the keyboard would change shape only on the next unrelated recomposition.
    var split by remember { mutableStateOf(prefs.split) }
    val keyboardWanted = when (mode) {
        OnScreenKeyboardMode.NEVER -> false
        OnScreenKeyboardMode.ALWAYS -> true
        OnScreenKeyboardMode.AUTO -> !hardware
    }
    // Only while the user is actually driving. Keys drawn under a desktop the agent holds are keys
    // that do nothing, and the space they take is the guest's screen.
    val showKeyboard = live && driving && keyboardWanted

    Column(modifier.fillMaxSize().background(BoxTerminal)) {
        ComputerBar(
            state = state,
            onBack = onBack,
            onSelectPanel = onSelectPanel,
            onSetControl = onSetControl,
            onShowDiagnostics = onShowDiagnostics,
            onStop = onStop,
            onShowSystemKeyboard = { surface?.showSystemKeyboard() },
            keyboardShown = showKeyboard,
            onToggleKeyboard = {
                mode = if (showKeyboard) OnScreenKeyboardMode.NEVER else OnScreenKeyboardMode.ALWAYS
                prefs.mode = mode
                if (!driving) onSetControl(ControlHolder.User)
            },
            split = split,
            onToggleSplit = {
                split = !split
                prefs.split = split
            },
            live = live,
            compact = compact,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // The whole pane, so the band the keys take can be remembered as a share of it rather
            // than in pixels: this window changes size several times a minute on a foldable.
            val paneHeightPx = constraints.maxHeight
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (live) {
                        DesktopSurface(
                            transport = desktop,
                            interactive = driving,
                            modifier = Modifier.fillMaxSize(),
                            pointer = pointer,
                            onViewReady = { surface = it },
                        )
                    } else {
                        ComputerComingUp(
                            state = state,
                            progress = progress,
                            onOpenBox = onOpenBox,
                            onStop = onStop,
                        )
                    }

                    FloatingPanel(
                        panel = state.computerPanel,
                        compact = compact,
                        onClose = { onSelectPanel(state.computerPanel) },
                    ) { panelModifier ->
                        when (state.computerPanel) {
                            ComputerPanel.Chat -> chat(panelModifier)
                            ComputerPanel.Terminal -> TerminalTool(
                                state = state,
                                onOpenBox = onOpenBox,
                                onRunCommand = onRunCommand,
                            )
                            ComputerPanel.Files -> FilesTool(
                                state = state,
                                onOpenBox = onOpenBox,
                                onSelectPlace = onSelectFilesPlace,
                                onOpenDirectory = onOpenDirectory,
                                onNavigateUp = onNavigateUp,
                                onRefresh = onRefreshFiles,
                                onOpenFile = onOpenFile,
                                onCloseFile = onCloseFile,
                                onOpenInPhoneFiles = onOpenInPhoneFiles,
                            )
                            ComputerPanel.None -> Unit
                        }
                    }
                }
                if (showKeyboard && desktop != null) {
                    OnScreenKeyboard(
                        pointer = pointer,
                        onKey = { keysym, down ->
                            scope.launch { desktop.send(DesktopInput.Key(keysym, down)) }
                        },
                        pointerScale = { surface?.pointerScale ?: 1f },
                        prefs = prefs,
                        paneHeightPx = paneHeightPx,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The bar
// ---------------------------------------------------------------------------

/**
 * One thin bar, and it is the only chrome the computer gets.
 *
 * It has to answer three things without being read: whose machine this is, who is typing into it,
 * and how to get back out. Everything else is behind the one menu.
 */
@Composable
private fun ComputerBar(
    state: BoxUiState,
    onBack: (() -> Unit)?,
    onSelectPanel: (ComputerPanel) -> Unit,
    onSetControl: (ControlHolder) -> Unit,
    onShowDiagnostics: () -> Unit,
    onStop: () -> Unit,
    onShowSystemKeyboard: () -> Unit,
    keyboardShown: Boolean,
    onToggleKeyboard: () -> Unit,
    split: Boolean,
    onToggleSplit: () -> Unit,
    live: Boolean,
    compact: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to tasks")
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Computer",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Spacer(Modifier.width(8.dp))
                    StatusDot(
                        if (live) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        7.dp,
                    )
                }
            }
            if (!compact) {
                Text(
                    "Debian · ARM64",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // On a phone this bar has room for about one more thing, so it spends it on the only state
        // worth a button: an agent holding the keyboard. "You're driving" is what the whole screen
        // already says, and giving it back is the menu's job and leaving's job.
        if (live && (!compact || state.desktopControl == ControlHolder.Agent)) {
            ControlButton(state.desktopControl, state.agentAtWork, onSetControl, compact)
        }

        PanelButton(ComputerPanel.Chat, Icons.Outlined.Forum, "Agent", state.computerPanel, onSelectPanel, compact)
        PanelButton(ComputerPanel.Terminal, Icons.Outlined.Terminal, "Terminal", state.computerPanel, onSelectPanel, compact)
        PanelButton(ComputerPanel.Files, Icons.Outlined.FolderOpen, "Files", state.computerPanel, onSelectPanel, compact)

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Computer options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (live && state.desktopControl == ControlHolder.User) {
                    DropdownMenuItem(
                        text = { Text("Give the keyboard back") },
                        onClick = {
                            menuOpen = false
                            onSetControl(ControlHolder.Agent)
                        },
                    )
                }
                if (live) {
                    // A touchscreen has no keys, and a phone with nothing plugged into it already
                    // has these on screen — this is the correction for when the automatic answer is
                    // wrong, in either direction. See [OnScreenKeyboard].
                    DropdownMenuItem(
                        text = { Text(if (keyboardShown) "Hide the keyboard" else "Show the keyboard") },
                        leadingIcon = { Icon(Icons.Outlined.Keyboard, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onToggleKeyboard()
                        },
                    )
                }
                if (live && keyboardShown) {
                    // Two halves under two thumbs, for the phone held in both hands. Worth a menu
                    // item rather than a gesture: which one suits you does not change during a
                    // session, and a keyboard that rearranged itself by accident would be worse
                    // than either shape.
                    DropdownMenuItem(
                        text = { Text(if (split) "Join the keyboard up" else "Split the keyboard") },
                        onClick = {
                            menuOpen = false
                            onToggleSplit()
                        },
                    )
                }
                if (live) {
                    // The way out for anyone who would rather have their own IME — dictation,
                    // another language, a layout Box does not draw. It types through the same
                    // editor-shaped fiction the desktop already maintains for hardware keyboards.
                    DropdownMenuItem(
                        text = { Text("Use the system keyboard") },
                        onClick = {
                            menuOpen = false
                            onSetControl(ControlHolder.User)
                            onShowSystemKeyboard()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Details") },
                    onClick = {
                        menuOpen = false
                        onShowDiagnostics()
                    },
                )
                if (state.runtimeState != RuntimeState.Stopped) {
                    DropdownMenuItem(
                        text = { Text("Close your box") },
                        onClick = {
                            menuOpen = false
                            onStop()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Who has the keyboard, as one control that is also the answer.
 *
 * The user usually already has it — walking in takes it, see `BoxViewModel.openComputer` — so most
 * of the time this reads "You're driving" and hands it back. It only asks to take over when an
 * agent is genuinely mid-task, which is the one case where taking it silently would be rude.
 */
@Composable
private fun ControlButton(
    control: ControlHolder,
    agentAtWork: Boolean,
    onSetControl: (ControlHolder) -> Unit,
    compact: Boolean,
) {
    when (control) {
        ControlHolder.Agent -> Button(
            onClick = { onSetControl(ControlHolder.User) },
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 18.dp, vertical = 8.dp),
        ) {
            Text(if (agentAtWork && !compact) "Agent is working · Take over" else "Take over")
        }

        ControlHolder.User -> OutlinedButton(
            onClick = { onSetControl(ControlHolder.Agent) },
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 16.dp, vertical = 8.dp),
        ) {
            StatusDot(MaterialTheme.colorScheme.primary, 7.dp)
            Spacer(Modifier.width(8.dp))
            Text(if (compact) "Driving" else "You're driving")
        }
    }
}

@Composable
private fun PanelButton(
    panel: ComputerPanel,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: ComputerPanel,
    onSelect: (ComputerPanel) -> Unit,
    compact: Boolean,
) {
    val active = panel == selected
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    if (compact) {
        IconButton(onClick = { onSelect(panel) }) {
            Icon(icon, contentDescription = label, tint = tint)
        }
    } else {
        Surface(
            onClick = { onSelect(panel) },
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
            contentColor = tint,
            modifier = Modifier.padding(horizontal = 3.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Panels
// ---------------------------------------------------------------------------

/**
 * A tool, over the desktop rather than next to it.
 *
 * On a phone it takes the bottom two thirds, because that is where thumbs are and the desktop
 * above stays visible. Given a wide window it becomes what the design always wanted: a window on a
 * desktop, parked bottom-right, with the machine still filling the screen behind it.
 */
@Composable
private fun FloatingPanel(
    panel: ComputerPanel,
    compact: Boolean,
    onClose: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val panelModifier = if (compact) {
            Modifier.fillMaxWidth().fillMaxHeight(0.68f)
        } else {
            Modifier
                .width(PANEL_WIDTH)
                .heightIn(max = 620.dp)
                .fillMaxHeight(0.82f)
                .padding(end = 18.dp, bottom = 18.dp)
        }
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = if (compact) Alignment.BottomCenter else Alignment.BottomEnd,
        ) {
            AnimatedVisibility(
                visible = panel != ComputerPanel.None,
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 },
                modifier = panelModifier,
            ) {
                Surface(
                    shape = if (compact) {
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    } else {
                        RoundedCornerShape(20.dp)
                    },
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    shadowElevation = 18.dp,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when (panel) {
                                    ComputerPanel.Chat -> "Agent"
                                    ComputerPanel.Terminal -> "Terminal"
                                    ComputerPanel.Files -> "Files"
                                    ComputerPanel.None -> ""
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontSize = 11.sp,
                                letterSpacing = 1.1.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onClose) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Close panel",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        content(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

private val PANEL_WIDTH = 420.dp

// ---------------------------------------------------------------------------
// Before there is a picture
// ---------------------------------------------------------------------------

/**
 * The stage while the machine is off, opening, or broken.
 *
 * Drawn in the desktop's own frame rather than as a separate screen, because it is the same place:
 * pressing Computer on a cold phone should start the box and leave you watching it come up, not
 * bounce you back somewhere else to press a second button.
 */
@Composable
private fun ComputerComingUp(
    state: BoxUiState,
    progress: BoxProgress,
    onOpenBox: () -> Unit,
    onStop: () -> Unit,
) {
    val opening = state.computerBusy || state.openingSince != null && !state.computerReady
    val failure = (state.runtimeState as? RuntimeState.Failed)?.reason

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (opening) {
            OpeningMark(progress, opening = true, size = 46.dp)
        } else {
            Icon(
                Icons.Outlined.Computer,
                contentDescription = null,
                modifier = Modifier.size(46.dp),
                tint = CodeColors.muted,
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            when {
                failure != null -> "Your box didn’t open"
                state.computerReady -> "Waiting for the picture"
                opening -> "Opening your box"
                state.runtimeState == RuntimeState.Suspended -> "Your box is paused"
                else -> "Your box is closed"
            },
            style = MaterialTheme.typography.titleLarge,
            color = CodeColors.plain,
            textAlign = TextAlign.Center,
        )
        // Only when there is something to say that the title does not already say: the reason it
        // broke, or the time left.
        val line = when {
            failure != null -> failure.message
            opening -> openingLine(progress)
            else -> null
        }
        if (line != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = CodeColors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        if (opening && progress.determinate) {
            Spacer(Modifier.height(24.dp))
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(4.dp),
                color = if (progress.overdue) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.height(26.dp))
        when {
            opening -> TextButton(onClick = onStop) { Text("Cancel") }
            state.computerReady -> Unit
            else -> Button(
                onClick = onOpenBox,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
            ) {
                Text(
                    if (failure != null) "Try again" else "Open your box",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The whole of what Box says about a wait it can only estimate. See [BoxProgress].
 *
 * A clock, not a sentence: the ring is already saying "working on it", and a paragraph next to a
 * progress indicator is a paragraph nobody reads twice.
 */
internal fun openingLine(progress: BoxProgress): String = when {
    !progress.determinate -> "Starting up"
    progress.overdue -> "Nearly there"
    progress.remainingSeconds == null -> "Nearly there"
    progress.remainingSeconds > 60 -> "~${(progress.remainingSeconds + 30) / 60} min left"
    else -> "~${progress.remainingSeconds}s left"
}
