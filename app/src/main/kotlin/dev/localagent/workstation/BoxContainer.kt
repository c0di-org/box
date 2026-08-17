package dev.localagent.workstation

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.localagent.workstation.agent.AgentBackend
import dev.localagent.workstation.agent.GuestAgentBackend
import dev.localagent.workstation.agent.GuestAuth
import dev.localagent.workstation.agent.HarnessControls
import dev.localagent.workstation.computer.DesktopTransport
import dev.localagent.workstation.computer.VncDesktop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The few objects that outlive a screen.
 *
 * Box has no dependency-injection framework and does not need one; what it needs is for these two
 * to be *process*-scoped rather than ViewModel-scoped. The backend binds `:computer` and holds the
 * attachment to every live session, and the sign-in exchange is a guest process sitting on a
 * blocking read while the user is away in their browser. Rebuilding either one because an Activity
 * was recreated would drop a running agent or a half-finished login on the floor.
 */
object BoxContainer {

    /**
     * Deliberately not a ViewModel scope. Work started here belongs to the app process, not to
     * whichever screen happened to be on top when it started.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var backendInstance: AgentBackend? = null

    fun backend(application: Application): AgentBackend =
        backendInstance ?: synchronized(this) {
            backendInstance ?: GuestAgentBackend(application, scope).also { backendInstance = it }
        }

    /** One Claude sign-in at a time, for the whole app. Existing behavior stays unchanged. */
    val auth: GuestAuth by lazy { GuestAuth() }

    @Volatile private var harnessControlsInstance: HarnessControls? = null

    /**
     * Account/model hand-offs also outlive an Activity. Constructing this starts no guest process;
     * a harness helper is launched only after the person opens that harness's settings sheet.
     */
    fun harnessControls(application: Application): HarnessControls =
        harnessControlsInstance ?: synchronized(this) {
            harnessControlsInstance ?: HarnessControls(application).also { harnessControlsInstance = it }
        }

    @Volatile private var desktopInstance: VncDesktop? = null

    /**
     * The guest's screen.
     *
     * Process-scoped like the rest of this object, so rotating the phone or folding it does not
     * drop the RFB connection and make the guest resend a whole framebuffer over an emulated link.
     */
    fun desktop(application: Application): DesktopTransport =
        desktopInstance ?: synchronized(this) {
            desktopInstance ?: VncDesktop(
                socketPath = VncDesktop.socketPath(application.filesDir),
                scope = scope,
            ).also { desktopInstance = it }
        }

    /**
     * Supplies the real backend.
     *
     * Without a factory the default `viewModel()` reflects on the single-argument constructor,
     * which means [BoxViewModel]'s `backend` parameter takes its null default and the scripted fake
     * wins every time. That is why `@JvmOverloads` is on that constructor and why it has to stay:
     * remove it and the reflective path this factory replaces stops resolving at all.
     */
    val factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
            BoxViewModel(application, backend(application))
        }
    }
}
