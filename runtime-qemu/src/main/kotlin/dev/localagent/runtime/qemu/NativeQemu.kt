package dev.localagent.runtime.qemu

import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Minimal JNI ownership boundary around the in-process QEMU system emulator. The native layer
 * owns the QEMU thread; Kotlin never executes guest commands on Android's host shell.
 */
internal object NativeQemu {
    init {
        System.loadLibrary("qemu-launcher")
    }

    external fun start(arguments: Array<String>, privateStorageDir: String): String?
    external fun stop(): String?
    external fun isRunning(): Boolean

    /**
     * Whether QEMU has already been initialised in this process.
     *
     * `qemu_init` is once-per-process — see the note in `qemu_launcher.cpp`. Once this is true the
     * VM can never run here again, however cleanly it exited.
     */
    external fun hasRun(): Boolean

    /**
     * Whether this device offers hardware virtualization, as a line for the log.
     *
     * Answers nothing else, and is wired to nothing else. [QemuCommand] fingerprints its own
     * arguments so a saved guest is only restored into the machine it left, so a launch that varied
     * with a device capability would invalidate paused boxes rather than report anything. See
     * `kvm_probe.cpp`.
     */
    external fun probeHypervisor(): String

    /**
     * Host CPU milliseconds burned by each thread of this process, keyed by thread id.
     *
     * Paired with the thread ids QMP reports per vCPU, this is what says whether multi-threaded TCG
     * is doing anything: two vCPU ids both climbing means two host cores are translating.
     *
     * Thread *names* were tried first and cannot answer it. This QEMU build never calls
     * `pthread_setname_np`, so every thread it creates inherits the name of the Kotlin dispatcher
     * thread that happened to launch it, and the whole emulator appears in `/proc` as a dozen
     * threads called `DefaultDispatch`.
     */
    fun threadCpuMillis(): Map<Int, Long> {
        val ticksPerSecond = 100L  // AT_CLKTCK is 100 on every Android arm64 device.
        return File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
            val id = task.name.toIntOrNull() ?: return@mapNotNull null
            val stat = runCatching { File(task, "stat").readText() }.getOrNull() ?: return@mapNotNull null
            // The comm field is parenthesised and may contain spaces, so fields are counted from
            // after the closing bracket rather than from the start of the line.
            val fields = stat.substringAfterLast(") ").split(" ")
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: return@mapNotNull null
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: return@mapNotNull null
            id to (utime + stime) * 1000L / ticksPerSecond
        }.toMap()
    }

    /** Compatibility callbacks required by the Android QEMU filesystem layer. They are only
     * used for Android content-provider paths; normal VM images remain private absolute paths. */
    @Suppress("unused")
    fun get_fd(path: String): Int = ParcelFileDescriptor.open(
        File(path),
        ParcelFileDescriptor.MODE_READ_WRITE,
    ).detachFd()

    @Suppress("unused")
    fun close_fd(fd: Int): Int = runCatching {
        ParcelFileDescriptor.adoptFd(fd).close()
        0
    }.getOrElse { -1 }
}
