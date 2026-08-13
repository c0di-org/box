package dev.localagent.workstation.computer

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Watches for real keyboards and pointers.
 *
 * An on-screen keyboard is worse than useless when there's hardware attached — it's stolen space,
 * and the space it steals is the guest's screen. This is capability detection rather than brand
 * detection on purpose: DeX, a Bluetooth keyboard and a USB mouse all present the same way here,
 * and there is no reliable feature string to test for.
 */
internal class HardwareInput(
    private val context: Context,
    private val onChange: (Boolean) -> Unit,
) : InputManager.InputDeviceListener {

    private val manager = context.getSystemService(InputManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    var present: Boolean = false
        private set

    fun start() {
        manager?.registerInputDeviceListener(this, handler)
        refresh()
    }

    fun stop() {
        manager?.unregisterInputDeviceListener(this)
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    private fun refresh() {
        val next = hasRealKeyboardOrPointer() || isDesktopMode()
        if (next == present) return
        present = next
        onChange(next)
    }

    private fun hasRealKeyboardOrPointer(): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        if (device.isVirtual || isBuiltIn(device)) return@any false
        val sources = device.sources
        val alphabetic = sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        val pointer = sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE
        alphabetic || pointer
    }

    /**
     * Platform-internal devices report vendor and product 0; anything on USB or Bluetooth carries
     * real HID ids.
     *
     * This test is not optional. A foldable's own gesture pad is not virtual and reports
     * `SOURCE_MOUSE`, so without it every session on that phone looks like it has a mouse plugged
     * in and the on-screen keyboard never appears. `isExternal()` would say so directly, but it is
     * hidden API.
     */
    private fun isBuiltIn(device: InputDevice) = device.vendorId == 0 && device.productId == 0

    /**
     * Belt and braces for Samsung DeX, which does not list a feature string worth querying. The
     * input-device check above is what actually carries the logic; this only catches a DeX session
     * with no keyboard or mouse of its own.
     */
    private fun isDesktopMode(): Boolean = try {
        val config = context.resources.configuration
        val field = config.javaClass.getField("semDesktopModeEnabled")
        val enabled = field.getInt(config)
        val on = config.javaClass.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null)
        enabled == on
    } catch (_: Throwable) {
        false
    }
}

/**
 * True while a real keyboard or pointer is attached, recomposing as one is plugged in or unplugged.
 *
 * Plugging a keyboard in mid-session has to take the on-screen one away in the same beat — the
 * whole promise of the automatic mode is that nobody ever goes looking for a setting.
 */
@Composable
internal fun rememberHardwareInput(): Boolean {
    val context = LocalContext.current
    var present by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val watcher = HardwareInput(context) { present = it }
        watcher.start()
        present = watcher.present
        onDispose { watcher.stop() }
    }
    return present
}
