package dev.localagent.runtime.qemu

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService

/**
 * How big the machine is: guest RAM, and how many processors the guest gets.
 *
 * Carried as a value rather than read out of preferences down here, because the process that owns
 * the VM is not the process the user sets this in — see [RuntimeService.EXTRA_MEMORY_MB]. It is
 * also part of [QemuCommand.machine], so changing it invalidates a saved guest automatically: the
 * next open discards the snapshot and boots cold rather than restoring 2 GB of memory into a
 * 3 GB machine, which QEMU would accept and the guest would not survive.
 */
data class GuestSizing(val memoryMb: Int, val processors: Int) {

    /**
     * The same sizing with the launch contract's ceilings applied.
     *
     * Called on the way into [QemuCommand] rather than trusted from the caller, because everything
     * either number can be wrong in presents as "the box won't open": QEMU refuses to start at all
     * with more RAM than the board can address, and says so on a stderr nobody is reading yet.
     */
    fun clamped(): GuestSizing = GuestSizing(
        memoryMb = memoryMb.coerceIn(MIN_MEMORY_MB, MAX_MEMORY_MB),
        processors = processors.coerceIn(MIN_PROCESSORS, MAX_PROCESSORS),
    )

    companion object {
        /** Below this Debian boots but the agent's own toolchain does not fit beside it. */
        const val MIN_MEMORY_MB = 1024

        /**
         * `-machine virt,highmem=off` keeps the whole address map under 4 GB, and guest RAM starts
         * at the 1 GB mark, so the board itself tops out here. Asking for more is not a slow box —
         * QEMU exits with "addressing limited to 32 bits" before the kernel is loaded. Raising this
         * means turning `highmem` on, which moves PCIe and the interrupt controller and so needs
         * its own boot test on the device.
         */
        const val MAX_MEMORY_MB = 3072

        const val MIN_PROCESSORS = 1

        /**
         * Every vCPU is a host thread translating ARM into ARM, and they contend far sooner than
         * the core count suggests. Measured cold boot to a ready agent on a Fold 7, two runs each
         * from a cooled SoC: **72 s at one processor, 177 s at two, 317 s at four**, and at six one
         * run took 439 s while the other was killed by Android's low-memory killer before it
         * finished. Every boot phase degrades monotonically — udev coldplug alone goes 18 s → 43 s →
         * 63 s → 100 s — because an emulated kernel boot is mostly cross-CPU TLB invalidation, and
         * TCG has to serialise every one of them.
         *
         * Four is kept as a ceiling rather than lowered because it is a choice the user can make
         * and parallel work does benefit; nothing above it is worth offering. [choicesFor] lowers
         * it again on a device with fewer cores.
         */
        const val MAX_PROCESSORS = 4

        /**
         * What a phone with no opinion gets. 2 GB is what a desktop session plus an agent's
         * toolchain fits in, and it is not the binding constraint: under four parallel Python
         * processes and a compile the guest reported 1.76 GiB still available and no swap.
         *
         * Two processors, which was briefly one. A second processor used to cost 105 seconds on
         * every cold boot — 177 s against 72 s — because every processor online while the kernel
         * probes devices makes that probing slower. It no longer does: the kernel boots with
         * `maxcpus=1` and `local-agent-online-cpus.service` hands the rest back once boot is over,
         * which measured 81 s on a two-processor box. So the boot argument for one processor is
         * gone, and what is left is the work: four concurrent jobs finish about 40% sooner on two,
         * which is the shape of an agent compiling and running things at once.
         */
        val DEFAULT = GuestSizing(memoryMb = 2048, processors = 2)

        private val MEMORY_LADDER_MB = listOf(1024, 2048, 3072)
        private val PROCESSOR_LADDER = listOf(1, 2, 4)

        /**
         * The sizes worth offering on *this* phone.
         *
         * Guest RAM is one anonymous mapping inside `:computer`, so the honest limit is not the
         * ceiling above but how fat a process Android will tolerate before its low-memory killer
         * picks this one. Judged against total RAM rather than the "available" figure, which on a
         * phone counts neither reclaimable page cache nor the background apps Android is happy to
         * close — an 11 GB device reports about 2.7 GB available and can still host a 3 GB guest.
         * Two spare gigabytes is roughly what Android itself wants to keep.
         */
        fun choicesFor(context: Context): GuestSizingChoices {
            val info = ActivityManager.MemoryInfo()
            context.applicationContext.getSystemService<ActivityManager>()?.getMemoryInfo(info)
            val totalMb = (info.totalMem / (1024L * 1024L)).toInt()
            val cores = Runtime.getRuntime().availableProcessors()
            return GuestSizingChoices(
                // Never empty: a device too small for any of them is still offered the smallest,
                // because a box that cannot be sized is worse than one that is sized optimistically.
                memoryMb = MEMORY_LADDER_MB.filter { totalMb <= 0 || totalMb >= it + ANDROID_HEADROOM_MB }
                    .ifEmpty { listOf(MIN_MEMORY_MB) },
                processors = PROCESSOR_LADDER.filter { it <= cores.coerceAtLeast(1) }
                    .ifEmpty { listOf(MIN_PROCESSORS) },
            )
        }

        /** What Android wants left over once the guest has taken its share. */
        private const val ANDROID_HEADROOM_MB = 2048
    }
}

/** The sizes a particular phone is offered, in the order they are shown. */
data class GuestSizingChoices(val memoryMb: List<Int>, val processors: List<Int>)
