package dev.localagent.workstation

/**
 * The first thing worth saying to a box, offered so nobody has to invent it.
 *
 * An empty composer on a machine nobody has used before is a harder question than it looks: the
 * honest answer to "Ask Box anything…" requires already knowing what a box *is*. This is the one
 * request that answers that by being carried out — the agent reads the machine it is running on
 * and the copy of Box's own source baked in beside it at `/usr/src/box`, and then builds something
 * small with whatever the person says they want it to be about.
 *
 * It is an ordinary message, not a script. Tapping it sends exactly these words down the path a
 * typed one takes — queued while the box opens, held if nobody has signed in yet — and what comes
 * back is real work, which is the entire reason for preferring it to a canned tour. The itinerary
 * it follows lives in `guest/agent-conventions.md`, in the guest image, so changing it needs
 * `tools/deploy.sh --image` rather than an app build.
 *
 * It lives here rather than beside the chip that draws it because `BoxViewModel.startTour` needs
 * it too, and the view model does not depend on `ui`.
 */
const val TOUR_PROMPT = "Show me what’s inside the box"
