package dev.localagent.workstation

import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.runtime.qemu.GuestSizing
import dev.localagent.runtime.qemu.GuestSizingChoices
import dev.localagent.workstation.agent.AgentPermissionMode
import dev.localagent.workstation.agent.Attachment
import dev.localagent.workstation.agent.ConnectService
import dev.localagent.workstation.agent.GitHubAuth
import dev.localagent.workstation.agent.GuestAuth
import dev.localagent.workstation.agent.HarnessDescriptor
import dev.localagent.workstation.agent.SessionConnection
import dev.localagent.workstation.agent.SessionStatus
import dev.localagent.workstation.agent.SessionSummary
import dev.localagent.workstation.agent.Transcript
import dev.localagent.workstation.computer.ControlHolder

/**
 * Box's two top-level surfaces, and they are peers.
 *
 * Tasks is where the app opens, because most people arrive with something to ask for. Computer is
 * the machine itself — the whole window, driven directly — and it is a place someone can live in
 * without ever talking to an agent. Neither is a detail of the other.
 */
enum class BoxDestination { Tasks, Computer }

/**
 * What is floating over the computer.
 *
 * Not tabs, and not a second navigation level: the desktop is always the surface, and these are
 * panels drawn on top of it — the agent, a shell, the workspace — one at a time, dismissable back
 * to nothing. A tab bar would have made the desktop one of four equal things instead of the thing.
 */
enum class ComputerPanel { None, Chat, Terminal, Files, Preview }

/**
 * The two places files live, and the Files panel opens on the first of them.
 *
 * **Shared** is a real directory on the phone, published to Android, and the source of truth for
 * everything in it. **InTheBox** is the guest's `/workspace`, reachable only while the VM is up.
 *
 * Shared is first because it is the one that always works. The panel used to be a single browser
 * over the guest, which meant tapping Files on a closed box showed a progress screen and a
 * three-minute wait — for files that were, in the shared case, sitting on the phone all along.
 */
enum class FilesPlace { Shared, InTheBox }

/**
 * What the last sync did, for the Shared place to say out loud.
 *
 * Copying files between two machines behind the user's back is the kind of feature that is either
 * observable or spooky. [kept] is the one that has to be shown rather than counted: a `.from-box`
 * file appearing beside the user's own is the visible half of the conflict rule, and it needs a
 * sentence explaining why it is there.
 */
data class SharedSyncNote(
    val atMillis: Long,
    val pushedIn: Int,
    val broughtOut: Int,
    val kept: List<String>,
    val trouble: List<String>,
)

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

/**
 * A message that has been sent but not yet taken by a harness.
 *
 * [sessionId] is null for the first message of a conversation, typed before the session it starts
 * has an id, and is filled in as soon as the session exists — without that, selecting the new
 * conversation would drop the message just typed.
 *
 * [heldForSignIn] separates the two reasons a message sits here, which are not the same promise. An
 * ordinary queued message is already with the backend and runs itself when the guest can take it; a
 * held one has deliberately not been handed over, because handing it to a box with no credential
 * *spends* it — the agent answers "Box is not signed in yet" and the user retypes. Held messages
 * are what a successful sign-in goes back and sends.
 *
 * [attachments] live here rather than on the composer because a held message outlives the composer
 * it was typed into, and they are cleared from the composer as soon as the message is queued so a
 * second tap cannot send them twice. This is the only copy.
 */
data class QueuedPrompt(
    val sessionId: String?,
    val text: String,
    val heldForSignIn: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
)

/**
 * Where the box is, in the user's terms rather than the runtime's.
 *
 * The runtime has ten states and the user has three questions: can I use it, is it coming, or do I
 * have to ask for it. Everything the home surface does is driven by this.
 */
enum class BoxStage { Closed, Working, Open }

data class BoxUiState(
    val runtimeState: RuntimeState = RuntimeState.NotProvisioned,
    val destination: BoxDestination = BoxDestination.Tasks,

    // ---- opening the box ----
    /**
     * When the user asked for the box, or null when nobody is waiting on it.
     *
     * Held here rather than derived from [runtimeState] because the runtime passes back through
     * `Stopped` between unpacking the image and booting it — one broadcast that would otherwise
     * read as "the box is off" in the middle of opening it. It is also what makes the progress
     * indicator possible at all: the runtime reports states, not elapsed time.
     */
    val openingSince: Long? = null,
    /** What opening is expected to cost on this phone, learned from the last few. */
    val expectedOpenMillis: Long = BoxProgress.ASSUMED_MILLIS,
    /**
     * The box has just opened for the very first time on this device and nobody has been told yet.
     *
     * Exactly once in the life of an install. The first time is the only time the arrival is worth
     * the whole window — it is also the moment the two things Box can do have to be shown, because
     * afterwards they are a row and a tab that people find by looking. Every later opening gets a
     * line in the corner and the row filling with the machine's own screen.
     */
    val readyGreeting: Boolean = false,

    // ---- conversations ----
    val harnesses: List<HarnessDescriptor> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: String? = null,
    val transcript: Transcript? = null,
    val transcriptLoading: Boolean = false,
    val connection: SessionConnection = SessionConnection.Live,
    /** Scopes the user granted with "Always allow". Suppresses matching sheets. */
    val alwaysAllowed: Set<String> = emptySet(),
    /**
     * Whether Box still asks. One value for the whole box, held here rather than per conversation
     * because it is a statement about the user's trust, not about a session.
     */
    val permissionMode: AgentPermissionMode = AgentPermissionMode.Ask,
    /**
     * "Open faster": whether an idle box is saved rather than closed.
     *
     * True by default, because saving an idle box is what Box did before this was a choice.
     */
    val openFaster: Boolean = true,
    /**
     * How big the box is built: guest memory and processors.
     *
     * The box that is *open* may be a different size, because a machine cannot be resized under a
     * running guest — this is what the next one gets. See [BoxViewModel.setGuestSizing].
     */
    val guestSizing: GuestSizing = GuestSizing.DEFAULT,
    /** The sizes this particular phone has room for. Read once, at startup. */
    val guestSizingChoices: GuestSizingChoices = GuestSizingChoices(
        memoryMb = listOf(GuestSizing.DEFAULT.memoryMb),
        processors = listOf(GuestSizing.DEFAULT.processors),
    ),
    val startingSession: Boolean = false,
    /**
     * The task swiped off the list, still waiting to find out whether the user meant it.
     *
     * Held here rather than closed on the spot because closing is the one thing on this surface
     * that cannot be taken back: the record goes, the index is rewritten, and `:computer` is told
     * to let the session go. So the row leaves immediately — a swipe that snaps back has not
     * happened — and the actual close waits out the undo snackbar. One at a time; swiping a second
     * task commits the first, because the snackbar it was relying on has gone.
     */
    val closingTaskId: String? = null,
    /**
     * What the user typed before the guest could take it. Shown in the transcript's place so a
     * message sent to a booting computer is visibly waiting rather than apparently lost.
     */
    val queued: List<QueuedPrompt> = emptyList(),

    /**
     * Files picked or shared in, waiting on the next thing the user sends.
     *
     * They are already written into the box's shared folder by the time they are in here — this
     * list is what the composer draws, not a staging area. Held on the box rather than inside the
     * composer because a file can arrive from outside it: the share sheet reaches Box with no
     * conversation open and nothing focused, and the picture has to be somewhere when it does.
     */
    val pendingAttachments: List<Attachment> = emptyList(),

    // ---- signing in ----
    val signIn: GuestAuth.State = GuestAuth.State.Unknown,
    val signInVisible: Boolean = false,
    /** The hint that survives a restart. See [SignInHistory]. */
    val signedInBefore: Boolean = false,

    // ---- GitHub ----
    val github: GitHubAuth.State = GitHubAuth.State.Unknown,
    val githubVisible: Boolean = false,
    /**
     * An agent waiting on an account, if one is.
     *
     * Held on the box rather than in the transcript because the agent's tool call outlives the
     * screen: it blocks until somebody answers, and somebody may answer from the box sheet, from
     * the card in the conversation, or after switching to another task and coming back. One place
     * to look means the answer reaches the session that asked wherever it was given.
     */
    val connectRequest: ConnectRequest? = null,

    // ---- computer ----
    /**
     * Who the guest's input belongs to. Walking into the computer takes it, unless an agent is
     * mid-task; leaving gives it back, so a session cannot be left with the keyboard pointed
     * somewhere the user forgot.
     */
    val desktopControl: ControlHolder = ControlHolder.Agent,
    val computerPanel: ComputerPanel = ComputerPanel.None,
    val commandHistory: List<CommandRecord> = emptyList(),
    val runningCommand: String? = null,
    val currentPath: String = "/workspace",
    val files: List<FileEntry> = emptyList(),
    val filesLoading: Boolean = false,
    val openingFilePath: String? = null,
    val openedFile: OpenedFile? = null,

    /**
     * Something the agent is serving in the guest, reachable on the phone's loopback.
     *
     * The guest port is kept beside the url because releasing the forward needs it, and the url is
     * a loopback address that says nothing about which guest port it reaches.
     */
    val preview: OpenedPreview? = null,

    // ---- the shared folder ----
    val filesPlace: FilesPlace = FilesPlace.Shared,
    /** Relative to the shared folder; empty at its root. Never an absolute phone path. */
    val sharedPath: String = "",
    val sharedFiles: List<FileEntry> = emptyList(),
    val sharedSync: SharedSyncNote? = null,

    val notice: UiNotice? = null,
) {
    val selectedSession: SessionSummary?
        get() = sessions.firstOrNull { it.id == selectedSessionId }

    /**
     * Queued messages belonging to the conversation on screen, oldest first.
     *
     * A message with no session id is one typed before the task it starts existed, and it normally
     * belongs to whatever conversation is open — because sending it *opens* that conversation, and
     * the id lands a moment later. A held one does not: it can sit with no session for as long as
     * the sign-in takes, and the box's own screen is where it is being shown. Without this it would
     * turn up inside whichever unrelated task the user opened while they were signed out.
     */
    val queuedForSelected: List<QueuedPrompt>
        get() = queued.filter { prompt ->
            when {
                prompt.sessionId != null -> prompt.sessionId == selectedSessionId
                else -> !prompt.heldForSignIn
            }
        }

    /**
     * Every task, newest first, with no harness above it.
     *
     * The list used to be grouped by harness, which put "Claude Code" between the user and their
     * own work and implied the harness was the thing they had several of. What they have several of
     * is tasks; there is one box, and it belongs at the top. Which agent is running a task is a
     * property of that task, drawn on its row.
     */
    val tasks: List<SessionSummary>
        get() = sessions
            .filterNot { it.id == closingTaskId }
            .sortedByDescending { it.updatedAt }

    fun harnessOf(session: SessionSummary): HarnessDescriptor? =
        harnesses.firstOrNull { it.id == session.harnessId }

    /** See [BoxStage]. */
    val boxStage: BoxStage
        get() = when {
            runtimeState == RuntimeState.Ready -> BoxStage.Open
            runtimeState is RuntimeState.Provisioning ||
                runtimeState == RuntimeState.Starting ||
                runtimeState == RuntimeState.Connecting ||
                runtimeState == RuntimeState.Stopping ||
                runtimeState == RuntimeState.Suspending -> BoxStage.Working
            // The gap between unpacking and booting. Someone is still waiting.
            openingSince != null && runtimeState == RuntimeState.Stopped -> BoxStage.Working
            else -> BoxStage.Closed
        }

    /**
     * The guest answered, and said no credential. Deliberately not true for `Unknown` — before the
     * computer has booted Box has not asked yet, and guessing would nag every cold start.
     *
     * A failed sign-in counts too. This banner is the only way into the sign-in sheet, so leaving
     * it hidden after a failure strands the user with no route back to the thing that failed.
     */
    val needsSignIn: Boolean
        get() = signIn is GuestAuth.State.SignedOut || signIn is GuestAuth.State.Failed

    /**
     * Whether signing in is still ahead of this user — known from the guest, or expected.
     *
     * The wider question than [needsSignIn], and the one the *first-run* screens ask. Before the
     * box has booted there is nobody to ask, so a fresh install answers from [signedInBefore]:
     * nothing on this phone has ever signed in, therefore the next thing to happen is signing in.
     * That is what lets the closed box say so before someone commits to a three-minute wait, and
     * what lets the arrival paint its sign-in door on the first frame instead of swapping a door
     * out from under the one moment an install ever gets.
     *
     * A sign-in already under way counts as wanted: it has not finished.
     */
    val signInWanted: Boolean
        get() = when (signIn) {
            is GuestAuth.State.SignedIn -> false
            GuestAuth.State.Unknown, GuestAuth.State.Checking -> !signedInBefore
            else -> true
        }

    /** What the user typed that is waiting on a sign-in rather than on the computer. */
    val heldForSignIn: List<QueuedPrompt>
        get() = queued.filter { it.heldForSignIn }

    /** The computer can be reached but is not usable yet. Chat never blocks on this. */
    val computerReady: Boolean
        get() = runtimeState == RuntimeState.Ready

    val computerBusy: Boolean
        get() = runtimeState == RuntimeState.Starting ||
            runtimeState == RuntimeState.Connecting ||
            runtimeState is RuntimeState.Provisioning

    /**
     * Whether the box itself is worth the whole home surface.
     *
     * It is, whenever there is nothing else on that surface: a first-run box with nothing in it, a
     * first opening with no tasks under it yet, and the one arrival an install ever gets. It is
     * not, the moment there is real work to look at — reopening the box after a restart must not
     * hide a list of tasks behind a progress screen for three minutes, so that opening is a row.
     *
     * The same is true of a *closed* box, and used to not be: Android reclaims `:computer`
     * routinely, so "closed, with a week of tasks in the list" is the ordinary state of a returning
     * user, and they were meeting a full-window splash with their own work hidden behind it.
     */
    val boxOwnsWindow: Boolean
        get() = when (boxStage) {
            BoxStage.Closed -> tasks.isEmpty()
            BoxStage.Working -> tasks.isEmpty()
            BoxStage.Open -> readyGreeting
        }

    /**
     * Whether an agent is in the middle of something.
     *
     * The one question that decides who gets the keyboard when the user opens the computer: an
     * idle box is theirs to drive, and a box with an agent typing in it is not something to take
     * out from under it by accident.
     */
    val agentAtWork: Boolean
        get() = sessions.any { it.status == SessionStatus.Active }
}

/** A forwarded guest port, and the address on the phone that reaches it. */
data class OpenedPreview(val url: String, val guestPort: Int)


/** An agent's outstanding request for an account, and the session that is waiting on it. */
data class ConnectRequest(
    val sessionId: String,
    val requestId: String,
    val service: ConnectService,
    /** The agent's own half-line for what it needs the account for. */
    val reason: String?,
)
