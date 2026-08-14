package dev.localagent.runtime.qemu

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Where an image's files go, and how a device that predates images being named catches up.
 *
 * Split out of [RuntimeStorage] and given plain directories rather than a Context, because the one
 * operation here that can lose something irreplaceable — moving a user's `/workspace` disk out of
 * the old flat layout — should be provable without a phone.
 */
class GuestImageLayout(private val images: File, private val disks: File) {

    /**
     * The layout Box used before images were described: one image, four fixed names.
     *
     * Kept only for [migrateLegacy]. Nothing writes these again.
     */
    val legacyFlatFiles: Map<GuestImageRole, File> = mapOf(
        GuestImageRole.KERNEL to File(images, "kernel"),
        GuestImageRole.INITRD to File(images, "initrd.img"),
        GuestImageRole.SYSTEM to File(disks, "system.qcow2"),
        GuestImageRole.WORKSPACE to File(disks, "workspace.qcow2"),
    )

    /** The proof build wrote its image here directly, before there were separate disks at all. */
    private val legacyProofSystemDisk = File(images, "base-system.qcow2")

    /** `images/<id>/kernel`, `disks/<id>/workspace.qcow2`, and so on. */
    fun fileFor(manifest: GuestImageManifest, role: GuestImageRole): File {
        // Immutable payloads sit with the images, the disks the guest writes sit with the disks.
        // That split is unchanged from the flat layout; only the keying by image id is new.
        val parent = if (role.owner == GuestImageOwner.IMAGE && role.rewritable) images else disks
        return File(File(parent, manifest.storageKey), role.installedName)
    }

    fun presentRoles(manifest: GuestImageManifest): Set<GuestImageRole> =
        GuestImageRole.entries.filter { fileFor(manifest, it).isFile }.toSet()

    /** The manifest of the last install that ran to completion, kept beside what it describes. */
    fun recordFor(manifest: GuestImageManifest): File =
        File(File(images, manifest.storageKey), INSTALLED_RECORD_NAME)

    fun installedIdentity(manifest: GuestImageManifest): GuestImageIdentity? {
        val record = recordFor(manifest)
        if (!record.isFile) return null
        return runCatching { GuestImageManifest.parseIdentity(record.readText(Charsets.UTF_8)) }
            .getOrNull()
    }

    /**
     * Records the manifest verbatim rather than just its id and version.
     *
     * The identity is all provisioning compares, but what an installed image *contains* is the
     * question a picker will ask about images it did not install — and by then the APK's manifest
     * describes a different one.
     */
    fun writeRecord(manifest: GuestImageManifest, json: String) {
        val record = recordFor(manifest)
        ensureDirectory(record.parentFile!!)
        val temporary = File(record.parentFile, ".${record.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(json, Charsets.UTF_8)
            atomicReplace(temporary, record)
        } finally {
            temporary.delete()
        }
    }

    /**
     * Moves a pre-manifest install into the keyed layout.
     *
     * The workspace is the whole reason this exists. It is the user's Linux machine, and on a device
     * provisioned before images had names it sits at `disks/workspace.qcow2`, where nothing keyed
     * would look — so the next start would create a second, empty one and the user's work would
     * simply stop existing.
     *
     * The other three move rather than being deleted and reinstalled. They are replaced moments
     * later, but moving first means a device that loses power mid-provision still has a bootable
     * system disk, and it costs a rename instead of half a gigabyte of copying.
     *
     * Anything already in the keyed layout wins: a half-finished migration running again must not
     * overwrite the copy it already made.
     */
    fun migrateLegacy(manifest: GuestImageManifest) {
        migrateProofSystemDisk()
        legacyFlatFiles.forEach { (role, legacy) ->
            val destination = fileFor(manifest, role)
            if (!legacy.isFile || destination.exists()) return@forEach
            ensureDirectory(destination.parentFile!!)
            moveFile(legacy, destination)
        }
    }

    private fun migrateProofSystemDisk() {
        val flatSystemDisk = legacyFlatFiles.getValue(GuestImageRole.SYSTEM)
        if (flatSystemDisk.exists() || !legacyProofSystemDisk.isFile) return
        moveFile(legacyProofSystemDisk, flatSystemDisk)
    }

    private companion object {
        const val INSTALLED_RECORD_NAME = "installed.json"
    }
}

internal fun ensureDirectory(directory: File) {
    check((directory.exists() || directory.mkdirs()) && directory.isDirectory) {
        "Could not create private runtime directory ${directory.absolutePath}"
    }
}

internal fun moveFile(source: File, destination: File) {
    try {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath())
    }
}

internal fun atomicReplace(source: File, destination: File) {
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
