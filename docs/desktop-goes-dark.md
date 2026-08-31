# The desktop goes dark, and Box cannot get it back

An investigation with a live reproduction, on the image built from `d0af4db`, 31 Aug 2026. Found
from inside the guest while a user was trying to reach `xterm` to paste an API key — see
`docs/pasting-a-credential.md` for why they were going that way in the first place.

Two defects, on opposite sides of the process boundary. Either alone is recoverable. Together they
are a desktop that turns itself off after ten idle minutes and stays off.

## 1. The guest blanks its own display and cannot wake itself

X was started with its defaults, and its defaults assume a monitor.

Read from inside the running guest, at the moment the user was looking at "No picture":

```
$ xset q
Screen Saver:
  prefer blanking:  yes    allow exposures:  yes
  timeout:  0    cycle:  600
DPMS (Energy Star):
  Standby: 600    Suspend: 600    Off: 600
  DPMS is Enabled
  Monitor is Off          <-- this
```

The screen saver proper is disabled (`timeout: 0`). DPMS is not, and DPMS is what fired: standby,
suspend and off all at 600 seconds, and the monitor duly off.

**Nothing in the image turns it off.** `guest/systemd/local-agent-desktop.service` runs:

```
ExecStart=/usr/bin/xinit /usr/bin/openbox-session -- /usr/bin/X :0 vt1 -keeptty -nolisten tcp
```

There is no `xset s off -dpms` in that unit, in `openbox-session`, or anywhere else in `guest/`.
The only file in `guest/xorg.conf.d/` is the software-cursor one, which is about something else
entirely. So X comes up with power management enabled on a machine that has no power to manage.

**And nothing can wake it.** DPMS wakes on input to the X server. There are only two possible
sources of that here, and neither fires:

- The user, through Box's desktop pane — but their input only reaches the guest when they have
  explicitly taken control (`VncDesktop.send:108` drops input unless `control == ControlHolder.User`),
  and they cannot take control of a pane showing them nothing.
- The agent — which never generates X input at all. There is no `xdotool` in the image; the agent
  can screenshot the desktop but cannot drive it.

So the display switches itself off after ten idle minutes, and the two things that could wake it
are the thing that is blocked and the thing that does not exist.

### Verified, positively

Not inferred from the config — performed, and the result looked at:

```bash
$ xset -dpms; xset s off; xset dpms force on
$ xset q | grep Monitor
  Monitor is On
$ scrot /tmp/screen.png
```

The screenshot shows a live openbox desktop with an `xterm` already open at `/workspace`. The
session had been running the whole time. Only the output was off.

**The fix is one line in the image**: `xset s off -dpms` at session start — in
`local-agent-desktop.service` before `openbox-session`, or in openbox's `autostart`. On a machine
whose only display is a VNC framebuffer, DPMS has nothing to save and one thing to break.

## 2. When the desktop stream ends, Box cannot restart it

The message the user sees is two strings from two places. `ui/DesktopPane.kt:151` supplies the
title, and the body is the exception's own message verbatim:

```kotlin
is DesktopState.Failed -> DesktopPlaceholder(
    title = "No picture",
    body = snapshot.message,
    busy = false,
)
```

"the guest closed its display" is `computer/Rfb.kt:54` — an `EOFException` thrown when a socket
read returns `-1`.

The handling of that is where the second defect is. `computer/VncDesktop.kt:176`:

```kotlin
} catch (error: Exception) {
    Log.w(TAG, "the desktop stream ended", error)
    desktopState.value = DesktopState.Failed(error.message ?: "Box lost the picture from the computer.")
} finally {
    runCatching { socket.close() }
    synchronized(lock) {
        connection = null
        bitmap = null
    }
}
```

`connection` is cleared. `pump` is not — and `pump` is what `attach` tests:

```kotlin
if (pump != null) {
    // Already streaming; a view was resized, recreated, or newly opened. Repaint into
    // it rather than reconnecting, which would cost a full framebuffer resend.
    connection?.let { redraw(it) }
    return
}
```

After a failure `pump` still holds the *completed* Job, so that test passes, `connection` is null so
the repaint is skipped, and the method returns having done nothing. **`stream()` is never
relaunched.** The comment is correct about the case it was written for — a resize, a recreated
surface — and wrong about the one it now also catches, a stream that has already died.

`pump` is set to null in exactly one place: `detach`, and only once the *last* surface has gone
(`:95-97`). So recovery requires every view of the desktop to be torn down and rebuilt.

### And there is no in-app gesture that does that

This is the part that turns an annoyance into a dead end. The desktop is attached from **two**
places, not one. `ui/YourBox.kt:774` puts a minimap of it on the home surface:

```kotlin
DesktopSurface(
    desktop,
    interactive = false,
    preview = true,
    modifier = Modifier.fillMaxSize(),
)
```

That preview is the black tile in the left column — the one reading "No picture · the guest closed
its display" in the user's screenshot. It is on screen whenever the box panel is, and it registers a
surface of its own.

So closing the computer pane removes one surface and leaves the other. `surfaces` is never empty,
`detach` never reaches its `pump = null`, and the stream is never relaunched. Every recovery
available *inside* the app — closing the pane, switching tasks, navigating back — leaves the
preview attached and therefore does nothing.

What is left is destroying the surfaces from outside: backgrounding the app hard enough that its
window goes away, or swiping it out of recents. A user has no reason to guess that, and the failed
placeholder does not hint at it.

There is also no retry and no action on the card. `DesktopPlaceholder` gets `busy = false` and no
button, so a failed desktop presents as a statement of fact with nothing to do about it.

### The smallest honest fix

Clear `pump` where `connection` is cleared — in the `finally`, under the same lock. Re-opening the
pane then relaunches the stream, and the existing `attach` path does the rest. A retry button on
the failed placeholder would be better, and an automatic reconnect with a backoff better still, but
one line in the `finally` turns an unrecoverable state into a recoverable one.

Note that the `detach` path cannot be the fix on its own, precisely because of the preview above:
as long as any surface remains attached the stream will not restart, so the recovery has to happen
where the failure did.

## What connects them, and what does not

Defect 1 explains why the desktop was blank. Defect 2 explains why it stayed blank.

**What is not established is whether 1 *causes* 2.** For the pane to say "the guest closed its
display", QEMU's VNC server must have closed the connection, and it is plausible that tearing down
the scanout on DPMS-off does that — but this was not observed, only reasoned. It is equally
possible the stream ends for an unrelated reason and DPMS is a separate fault that merely looks the
same. Whoever picks this up should establish it:

1. Start the desktop, note the connection is live.
2. `xset dpms force off` in the guest.
3. Watch whether the VNC socket closes, and whether the pane goes to `Failed` or merely to black.

That single experiment separates "one bug with two symptoms" from "two bugs that meet". Both fixes
are worth making either way; the answer changes which one is urgent.

## A note for `docs/pasting-a-credential.md`

The reason this was being investigated is that the desktop was the proposed workaround for having
nowhere to paste an API key: open the computer, use `xterm`, paste there instead. **That workaround
does not exist either.**

`ui/GuestKeyboardView.kt:29` — *"The on-screen keyboard: draws every key and turns taps into X11
keysyms."* It is a custom view that maps taps to keysyms. There is no `InputConnection`, no
`commitText`, no paste handling anywhere in it. The phone's clipboard has no route into the guest
at all, so reaching `xterm` would only mean typing a thirty-character secret one key at a time on a
hand-drawn keyboard.

That strengthens the case in that proposal rather than weakening it: there is currently **no** way
to get a credential from the phone's clipboard into the box, and fixing the desktop does not create
one.

## What has *not* been done

- **No fix for either.** Both are one-line changes and both are left open deliberately.
- **The causal link is unestablished**, as above. Do the experiment before assuming.
- **The DPMS fix is untested against a suspend/resume cycle.** `docs/runtime.md` § "Putting the box
  away" describes the box being paused and restored; whether `xset -dpms` survives that, or needs
  re-applying on resume, has not been checked.
- **Applied at runtime on one device only.** `xset -dpms s off` was run inside this guest to
  confirm the diagnosis. That is a live X setting on the system disk's session; it is gone at the
  next VM restart, which is exactly why it belongs in the image.
