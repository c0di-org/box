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
