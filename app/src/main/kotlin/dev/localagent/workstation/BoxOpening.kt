package dev.localagent.workstation

import dev.localagent.runtime.api.RuntimeState
import kotlin.math.exp

/**
 * How far along opening the box is.
 *
 * The honest problem: the guest spends nearly all of its ~170 seconds inside one runtime state,
 * waiting on emulated udev, and reports nothing while it does. So there are only three real
 * checkpoints — the image was unpacked, QEMU is up, the guest agent answered — and a bar that moved
 * only on those would sit dead still for two and a half minutes.
 *
 * What this does instead: run a clock from the moment the user pressed, against how long *this
 * phone* took last time, and let the checkpoints correct it. The checkpoints are ground truth and
 * only ever push the bar forward ([floorFor]); the clock fills in between them. Time is a guess, so
 * the curve is built to never lie in the direction that matters — it reaches 92% at the expected
 * moment and then approaches the end without arriving, because the only thing that may claim the
 * box is open is the box saying so.
 */
data class BoxProgress(
    /** 0..1. Monotonic within one opening. Reaches exactly 1 only when the box is actually up. */
    val fraction: Float,
    /** What is happening now, in the user's terms. */
    val phase: String,
    /** Rounded seconds left, or null when the estimate has been overrun or is unknown. */
    val remainingSeconds: Int?,
    /**
     * False when Box cannot honestly place the opening on a scale — the UI process was replaced
     * mid-boot and the clock was lost with it. The indicator spins instead of filling.
     */
    val determinate: Boolean,
) {
    /** Past the estimate and still going. Worth saying out loud; the guest gets slow when hot. */
    val overdue: Boolean get() = determinate && remainingSeconds == null

    companion object {
        /**
         * What a first opening is assumed to cost, before this device has told us better.
         *
         * Measured tap-to-Ready on a Galaxy Z Fold 7 against the v2 image: 171 s cooled, 168 s on a
         * cold first provision, 252 s with the SoC already hot. The middle of that is the least
         * wrong starting guess, and one real boot replaces it.
         */
        const val ASSUMED_MILLIS = 175_000L

        /** Bounds on a learned duration, so one pathological boot cannot poison the estimate. */
        const val MIN_LEARNED_MILLIS = 45_000L
        const val MAX_LEARNED_MILLIS = 600_000L

        /** Where the curve hands over to the asymptote — the last honest fraction. */
        private const val EXPECTED_ARRIVAL = 0.92f

        /** How sharply the tail flattens past the estimate. Higher closes the gap faster. */
        private const val TAIL_DECAY = 1.6f

        fun of(
            state: RuntimeState,
            elapsedMillis: Long?,
            expectedMillis: Long = ASSUMED_MILLIS,
        ): BoxProgress {
            val phase = phaseOf(state)
            if (state == RuntimeState.Ready) {
                return BoxProgress(1f, phase, 0, determinate = true)
            }
            val floor = floorFor(state)
            if (elapsedMillis == null || expectedMillis <= 0) {
                return BoxProgress(floor, phase, null, determinate = false)
            }
            val ratio = elapsedMillis.toFloat() / expectedMillis
            val remaining = expectedMillis - elapsedMillis
            return BoxProgress(
                fraction = maxOf(curve(ratio), floor).coerceIn(0f, 1f),
                phase = phase,
                remainingSeconds = if (remaining > 0) ((remaining + 999) / 1_000).toInt() else null,
                determinate = true,
            )
        }

        /**
         * Elapsed-over-expected to a fraction of the bar.
         *
         * Linear until the moment the box was expected to be open, then asymptotic: at twice the
         * expected time it reads 96%, at three times 98%, and it never reaches 100%. A bar parked
         * at 99% is annoying; a bar that hit 100% and kept the user waiting is a lie.
         */
        private fun curve(ratio: Float): Float = when {
            ratio <= 0f -> 0f
            ratio <= 1f -> EXPECTED_ARRIVAL * ratio
            else -> {
                val tail = 1f - exp(-(ratio - 1f) * TAIL_DECAY)
                EXPECTED_ARRIVAL + (1f - EXPECTED_ARRIVAL) * tail
            }
        }

        /**
         * The lowest the bar may read given what the runtime has actually confirmed.
         *
         * These are the three real checkpoints. A phone faster than its own estimate reaches
         * `Connecting` while the clock still says 4%, and the bar should jump rather than pretend.
         */
        private fun floorFor(state: RuntimeState): Float = when (state) {
            is RuntimeState.Provisioning -> 0.01f + 0.07f * state.progress.coerceIn(0f, 1f)
            RuntimeState.Starting -> 0.09f
            RuntimeState.Connecting -> 0.15f
            RuntimeState.Ready -> 1f
            // Provisioning finishes by reporting Stopped, one broadcast before Starting. Mid-opening
            // that is a step forward, not the box being off; see [BoxUiState.opening].
            else -> 0.08f
        }

        private fun phaseOf(state: RuntimeState): String = when (state) {
            is RuntimeState.Provisioning -> "Unpacking the Linux system"
            RuntimeState.Starting -> "Starting the machine"
            RuntimeState.Connecting -> "Booting Debian"
            RuntimeState.Ready -> "Ready"
            RuntimeState.Stopping -> "Closing the box"
            RuntimeState.Suspending -> "Pausing the box"
            else -> "Getting the machine ready"
        }

        /**
         * Fold a finished opening into the estimate for the next one.
         *
         * Weighted towards history rather than the newest number, because the thing that moves a
         * boot time most is how hot the phone already is — a 252-second opening straight after
         * another one should nudge the estimate, not become it.
         */
        fun learn(previousMillis: Long?, observedMillis: Long): Long {
            val observed = observedMillis.coerceIn(MIN_LEARNED_MILLIS, MAX_LEARNED_MILLIS)
            val previous = previousMillis?.coerceIn(MIN_LEARNED_MILLIS, MAX_LEARNED_MILLIS)
                ?: return observed
            return (previous * 0.65 + observed * 0.35).toLong()
                .coerceIn(MIN_LEARNED_MILLIS, MAX_LEARNED_MILLIS)
        }
    }
}
