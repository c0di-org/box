package dev.localagent.runtime.qemu

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Application-private locations. No management socket is ever placed in shared storage. */
class RuntimeStorage(context: Context) {
    private val root = File(context.filesDir, "computer")
    val privateRoot: File get() = root
    val images = File(root, "images")
    val disks = File(root, "disks")
    val sockets = File(root, "sockets")

    val baseSystem = File(images, "base-system.qcow2")
    val uefiCode = File(images, "edk2-aarch64-code.fd")
    val uefiVars = File(images, "edk2-arm-vars.fd")
    val kernel = File(images, "kernel")
    val initrd = File(images, "initrd.img")
    val systemOverlay = File(disks, "system-overlay.qcow2")
    val workspace = File(disks, "workspace.qcow2")
    val qmpSocket = File(sockets, "qmp.sock")
    val agentSocket = File(sockets, "agentd.sock")
    val serialSocket = File(sockets, "serial.sock")

    fun hasUefiBootSet(): Boolean = baseSystem.isFile && uefiCode.isFile && uefiVars.isFile

    fun ensureDirectories() {
        listOf(root, images, disks, sockets).forEach {
            check(it.exists() || it.mkdirs()) { "Could not create ${it.absolutePath}" }
        }
    }
}

object AssetVerifier {
    fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(file: File, expected: String): Boolean =
        sha256(file).equals(expected.trim().lowercase(), ignoreCase = true)
}
