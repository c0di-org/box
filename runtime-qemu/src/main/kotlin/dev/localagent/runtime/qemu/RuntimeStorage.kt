package dev.localagent.runtime.qemu

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Application-private VM storage. Management sockets never leave this directory tree. */
class RuntimeStorage(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "computer")

    val privateRoot: File get() = root
    val images = File(root, "images")
    val disks = File(root, "disks")
    val sockets = File(root, "sockets")

    /** Immutable direct-boot files; they are revalidated against the APK when provisioning. */
    val kernel = File(images, "kernel")
    val initrd = File(images, "initrd.img")
    val uefiCode = File(images, "edk2-aarch64-code.fd")
    val uefiVars = File(images, "edk2-arm-vars.fd")

    /** Mutable runtime state. These files are never replaced during an app update. */
    val systemDisk = File(disks, "system.qcow2")
    val workspace = File(disks, "workspace.qcow2")

    /** The proof build wrote this image directly. Migrate it without attempting to reverify it. */
    private val legacyMutableSystemDisk = File(images, "base-system.qcow2")

    val qmpSocket = File(sockets, "qmp.sock")
    val agentSocket = File(sockets, "agentd.sock")
    val serialSocket = File(sockets, "serial.sock")

    fun hasHeadlessBootSet(): Boolean =
        kernel.isFile && initrd.isFile && systemDisk.isFile && workspace.isFile

    fun hasUefiBootSet(): Boolean =
        systemDisk.isFile && uefiCode.isFile && uefiVars.isFile

    fun ensureDirectories() {
        listOf(root, images, disks, sockets).forEach { directory ->
            check((directory.exists() || directory.mkdirs()) && directory.isDirectory) {
                "Could not create private runtime directory ${directory.absolutePath}"
            }
        }
    }

    /** Remove only stale socket nodes. Call this before QEMU starts, never while it is running. */
    fun removeStaleSockets() {
        listOf(qmpSocket, agentSocket, serialSocket).forEach { socket ->
            check(!socket.exists() || socket.delete()) { "Could not remove stale socket ${socket.name}" }
        }
    }

    /**
     * Seeds a complete direct-boot set from checksum-pinned APK assets. Mutable disks are installed
     * only when absent; kernel/initrd can be safely repaired because QEMU never writes them.
     */
    fun provisionBundledAssets(onProgress: (Float) -> Unit = {}) {
        ensureDirectories()
        onProgress(0.05f)

        migrateLegacySystemDiskIfNeeded()
        onProgress(0.12f)

        installVerifiedAsset("kernel", kernel, preserveExisting = false)
        onProgress(0.25f)
        installVerifiedAsset("initrd.img", initrd, preserveExisting = false)
        onProgress(0.40f)
        installVerifiedAsset("base-system.qcow2", systemDisk, preserveExisting = true)
        onProgress(0.78f)
        installVerifiedAsset("workspace.qcow2", workspace, preserveExisting = true)
        onProgress(1.0f)

        check(hasHeadlessBootSet()) { "Provisioning did not produce a complete guest boot set" }
    }

    private fun migrateLegacySystemDiskIfNeeded() {
        if (systemDisk.exists() || !legacyMutableSystemDisk.isFile) return
        try {
            Files.move(
                legacyMutableSystemDisk.toPath(),
                systemDisk.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(legacyMutableSystemDisk.toPath(), systemDisk.toPath())
        }
    }

    private fun installVerifiedAsset(assetName: String, destination: File, preserveExisting: Boolean) {
        val expected = readExpectedDigest(assetName)
        if (destination.isFile && (preserveExisting || AssetVerifier.verifySha256(destination, expected))) return
        if (destination.exists() && !destination.isFile) {
            error("Runtime artifact path is not a regular file: ${destination.absolutePath}")
        }

        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            val actual = appContext.assets.open("$ASSET_DIRECTORY/$assetName").use { input ->
                copyAndDigest(input, temporary)
            }
            check(actual.equals(expected, ignoreCase = true)) {
                "Bundled $assetName failed checksum verification"
            }
            atomicReplace(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    private fun readExpectedDigest(assetName: String): String {
        val value = appContext.assets.open("$ASSET_DIRECTORY/$assetName.sha256")
            .bufferedReader(Charsets.US_ASCII)
            .use { it.readText() }
            .trim()
            .substringBefore(' ')
        require(SHA256_REGEX.matches(value)) { "Invalid SHA-256 metadata for $assetName" }
        return value
    }

    private fun copyAndDigest(input: InputStream, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
            }
            output.fd.sync()
        }
        return digest.digest().toHex()
    }

    private fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val ASSET_DIRECTORY = "guest"
        val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
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
        digest.digest().toHex()
    }

    fun verifySha256(file: File, expected: String): Boolean =
        runCatching { sha256(file).equals(expected.trim(), ignoreCase = true) }.getOrDefault(false)
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
