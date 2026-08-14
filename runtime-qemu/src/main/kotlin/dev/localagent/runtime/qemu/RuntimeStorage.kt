package dev.localagent.runtime.qemu

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Application-private VM storage. Management sockets never leave this directory tree.
 *
 * Images are stored under a key taken from the manifest that describes them, so a device can hold
 * more than one and can answer whether the one in the APK is already installed. What has not
 * changed, and must not, is which files belong to whom: the kernel, the initrd and the system disk
 * are the APK's and may be replaced; `/workspace` is the user's Linux machine and is never
 * replaced by anything. That rule is applied in [GuestImageInstall], and where the files go is
 * [GuestImageLayout] — both kept free of Android so they can be held to it by tests.
 */
class RuntimeStorage(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "computer")

    val privateRoot: File get() = root
    val images = File(root, "images")
    val disks = File(root, "disks")
    val sockets = File(root, "sockets")

    private val layout = GuestImageLayout(images, disks)

    /**
     * The UEFI proof-build boot set, which predates manifests entirely and stays in the flat
     * layout. Nothing installs these; a device only has them if something put them there by hand.
     */
    val uefiCode = File(images, "edk2-aarch64-code.fd")
    val uefiVars = File(images, "edk2-arm-vars.fd")
    val uefiSystemDisk: File get() = layout.legacyFlatFiles.getValue(GuestImageRole.SYSTEM)

    /**
     * Where a suspended box leaves its note. See [SuspendedVm].
     *
     * At the root rather than beside a particular image, because the question it answers is asked
     * before an image is chosen: "is there a box to reopen at all". Which image it belongs to is
     * recorded *in* the note, and checked against what is installed before it is acted on.
     */
    private val suspendMarker = File(root, "suspend.json")

    val qmpSocket = File(sockets, "qmp.sock")
    val agentSocket = File(sockets, "agentd.sock")
    val serialSocket = File(sockets, "serial.sock")

    /**
     * The guest's screen, as an RFB server QEMU opens here.
     *
     * App-private like the others, and for the same reason: being a filesystem path rather than a
     * port is what keeps the guest's display off the network entirely. It carries no password
     * because it cannot be opened by anything that is not this UID.
     */
    val vncSocket = File(sockets, "vnc.sock")

    /**
     * QEMU's own data directory, passed to it with `-L`.
     *
     * QEMU expects to find files next to itself on a normal system; inside an APK there is no such
     * place, and the build compiled into this one looks under paths that do not exist on Android.
     * The concrete casualty is the VNC server, which loads a keymap to turn the keysyms a client
     * sends into scancodes the guest understands, and **exits** when it cannot — with the message
     * going to logcat rather than stderr, so it presents as a VM that silently never starts.
     */
    val qemuData = File(root, "qemu")
    private val keymaps = File(qemuData, "keymaps")

    /**
     * The image this APK carries, or null if it carries none.
     *
     * Null is a real answer rather than a failure: the `avf` flavour ships no guest assets at all.
     * A manifest that exists but cannot be read is a different matter and throws — but only out of
     * [requireBundledImage], so the queries below stay total and the loud failure lands in
     * provisioning, where there is already a path for reporting it to the user.
     */
    private val bundledImage: BundledImage? by lazy { runCatching { readBundledImage() }.getOrNull() }

    /** What the APK's image is called and which build of it this is. */
    fun bundledIdentity(): GuestImageIdentity? = bundledImage?.manifest?.identity

    /** Everything the APK's image says about itself, for a chooser that does not exist yet. */
    fun bundledManifest(): GuestImageManifest? = bundledImage?.manifest

    /** The identity of the last install that ran to completion, or null if none ever did. */
    fun installedIdentity(): GuestImageIdentity? =
        bundledImage?.manifest?.let(layout::installedIdentity)

    /**
     * Where the APK's image lives once installed, or null until all four of its files are there.
     *
     * Returning the set rather than four fields is what lets [QemuCommand] stop knowing filenames.
     */
    fun headlessBootFiles(): GuestImageFiles? {
        val manifest = bundledImage?.manifest ?: return null
        if (layout.presentRoles(manifest) != GuestImageRole.entries.toSet()) return null
        return GuestImageFiles(
            identity = manifest.identity,
            kernel = layout.fileFor(manifest, GuestImageRole.KERNEL),
            initrd = layout.fileFor(manifest, GuestImageRole.INITRD),
            system = layout.fileFor(manifest, GuestImageRole.SYSTEM),
            workspace = layout.fileFor(manifest, GuestImageRole.WORKSPACE),
        )
    }

    fun hasHeadlessBootSet(): Boolean = headlessBootFiles() != null

    fun hasUefiBootSet(): Boolean =
        uefiSystemDisk.isFile && uefiCode.isFile && uefiVars.isFile

    /**
     * True when this device is already running exactly the image in the APK.
     *
     * The question the old layout could not ask, and the reason a rebuilt guest image used to be
     * ignored: "the disk is already there" answered both "you are up to date" and "you are running
     * something else entirely", and provisioning could only act on the first reading.
     */
    fun isImageUpToDate(): Boolean {
        val manifest = bundledImage?.manifest ?: return false
        return GuestImageInstall.isUpToDate(
            manifest.identity,
            layout.installedIdentity(manifest),
            layout.presentRoles(manifest),
        )
    }

    fun ensureDirectories() {
        listOf(root, images, disks, sockets, qemuData, keymaps).forEach(::ensureDirectory)
    }

    /** Remove only stale socket nodes. Call this before QEMU starts, never while it is running. */
    fun removeStaleSockets() {
        listOf(qmpSocket, agentSocket, serialSocket, vncSocket).forEach { socket ->
            check(!socket.exists() || socket.delete()) { "Could not remove stale socket ${socket.name}" }
        }
    }

    /**
     * Whether a saved box is waiting to be reopened.
     *
     * Public and boolean where [suspendedVm] is neither, because the UI process asks this too. It
     * has no other way to know: `:computer` ends when the guest is saved, so there is no live
     * runtime to broadcast the state, and a UI that starts afterwards would otherwise report a box
     * that is full of the user's work as simply closed.
     */
    fun hasSuspendedVm(): Boolean = suspendedVm() != null

    /** The suspended guest waiting to be reopened, or null if this box was closed properly. */
    internal fun suspendedVm(): SuspendedVm? = SuspendedVm.read(suspendMarker)

    internal fun writeSuspendedVm(record: SuspendedVm) {
        ensureDirectory(root)
        record.writeTo(suspendMarker)
    }

    /**
     * Forgets the suspended guest.
     *
     * Called at the moment the snapshot is handed to QEMU, not once it has loaded: a saved guest
     * is loaded exactly once. If the load then fails, the next start is a cold boot from disks
     * that `loadvm` has already reverted to the same point — which loses nothing. Clearing the
     * note later would allow the opposite and much worse case, where a crash leaves a snapshot
     * that is older than the disks and a note still inviting the next start to load it.
     */
    fun clearSuspendedVm() {
        suspendMarker.delete()
    }

    /**
     * Brings this device up to the image the APK is carrying.
     *
     * Installs only what the manifest says is missing or out of date. An image whose id and
     * version already match is left entirely alone; a newer version of the same image replaces the
     * kernel, initrd and system disk and leaves `/workspace` exactly where it is.
     */
    fun provisionBundledAssets(onProgress: (Float) -> Unit = {}) =
        install(replaceImage = false, onProgress = onProgress)

    /**
     * Reinstalls the image over itself, keeping the user's workspace.
     *
     * For the case the version check cannot help with: the same image, but a system disk the
     * developer or the guest has since made a mess of. A separate entry point rather than a flag
     * on provisioning because it is the only operation here that destroys something on purpose,
     * and it should have to be asked for by name. It still cannot reach `/workspace` — that is
     * refused in [GuestImageInstall.decide], not here.
     */
    fun reprovisionImage(onProgress: (Float) -> Unit = {}) =
        install(replaceImage = true, onProgress = onProgress)

    private fun install(replaceImage: Boolean, onProgress: (Float) -> Unit) {
        val bundled = requireBundledImage()
        val manifest = bundled.manifest
        ensureDirectories()
        onProgress(PROGRESS_FLOOR / 2f)

        layout.migrateLegacy(manifest)
        onProgress(PROGRESS_FLOOR)

        val installed = layout.installedIdentity(manifest)
        val plan = GuestImageRole.entries.filter { role ->
            decide(manifest, role, installed, replaceImage) == GuestImageInstall.Decision.INSTALL
        }

        // Byte counts, so the bar tracks the 425 MB system disk rather than treating it as one
        // step in four. A manifest that omits them degrades to equal weighting rather than to a
        // division by zero.
        val weight = { role: GuestImageRole -> manifest.payload(role).bytes.coerceAtLeast(1L) }
        val total = plan.sumOf(weight).coerceAtLeast(1L)
        var written = 0L
        plan.forEach { role ->
            installPayload(manifest.payload(role), layout.fileFor(manifest, role))
            written += weight(role)
            onProgress(PROGRESS_FLOOR + (1f - PROGRESS_FLOOR) * (written.toFloat() / total))
        }

        // A suspended guest is memory that matches disks which are about to be replaced. Nothing
        // installed here can reach `/workspace`, but the system disk is where `savevm` puts the
        // vmstate, so an install of any kind invalidates the note that points at it.
        if (plan.isNotEmpty()) clearSuspendedVm()

        // Written last, and only once every payload landed, so a provisioning run killed half way
        // through leaves no claim to have installed anything. The next attempt then sees an
        // unknown image and reinstalls, which is the safe direction to fail in.
        layout.writeRecord(manifest, bundled.json)
        onProgress(1f)

        check(hasHeadlessBootSet()) { "Provisioning did not produce a complete guest boot set" }
    }

    private fun decide(
        manifest: GuestImageManifest,
        role: GuestImageRole,
        installed: GuestImageIdentity?,
        replaceImage: Boolean,
    ): GuestImageInstall.Decision {
        val destination = layout.fileFor(manifest, role)
        val present = destination.isFile
        // Only worth hashing when everything else already points at KEEP; any other time the file
        // is about to be overwritten and reading 30 MB to confirm that would be wasted work.
        val checkable = role.rewritable && present && !replaceImage && installed == manifest.identity
        val intact = !checkable || AssetVerifier.verifySha256(destination, manifest.payload(role).sha256)
        return GuestImageInstall.decide(role, manifest.identity, installed, present, intact, replaceImage)
    }

    /**
     * Unpack QEMU's data files where QEMU can reach them.
     *
     * On every start, not from provisioning. Provisioning runs once, when there is no guest disk
     * yet; these files belong to the *APK*, so an app update changing them has to reach a device
     * whose disks are already in place — and that device never provisions again. Rewriting them
     * each start is a few kilobytes and keeps them matched to the installed build. Deliberately the
     * opposite of the rule for the disks, which are the user's work and are never replaced.
     *
     * Not checksum-verified like the boot set: those are large payloads assembled by a separate
     * build step where a truncated copy is possible, while these are small files read straight out
     * of the APK, and a corrupt one means the APK itself is corrupt.
     */
    fun installQemuData() {
        val assets = appContext.assets
        val names = assets.list(KEYMAP_ASSET_DIR).orEmpty()
        check(names.isNotEmpty()) { "The APK is missing QEMU's keymaps; the display cannot start" }
        names.forEach { name ->
            assets.open("$KEYMAP_ASSET_DIR/$name").use { source ->
                FileOutputStream(File(keymaps, name)).use { destination -> source.copyTo(destination) }
            }
        }
    }

    private fun installPayload(payload: GuestImagePayload, destination: File) {
        if (destination.exists() && !destination.isFile) {
            error("Runtime artifact path is not a regular file: ${destination.absolutePath}")
        }
        ensureDirectory(destination.parentFile!!)

        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            val actual = appContext.assets.open("$ASSET_DIRECTORY/${payload.assetName}").use { input ->
                copyAndDigest(input, temporary)
            }
            check(actual.equals(payload.sha256, ignoreCase = true)) {
                "Bundled ${payload.assetName} failed checksum verification"
            }
            atomicReplace(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    private fun requireBundledImage(): BundledImage =
        // Re-read rather than returning the cached null, so the failure reported is the real one:
        // a missing asset and a malformed manifest are very different things to be told.
        bundledImage ?: readBundledImage()

    private fun readBundledImage(): BundledImage {
        val name = "$ASSET_DIRECTORY/${GuestImageManifest.ASSET_NAME}"
        val json = try {
            appContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: IOException) {
            throw IllegalStateException("This build ships no guest image (no $name in the APK)", error)
        }
        return BundledImage(GuestImageManifest.parse(json), json)
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

    private class BundledImage(val manifest: GuestImageManifest, val json: String)

    private companion object {
        const val ASSET_DIRECTORY = "guest"
        const val KEYMAP_ASSET_DIR = "qemu/keymaps"

        /** Reserved for migrating and planning, which have no size to report progress against. */
        const val PROGRESS_FLOOR = 0.05f
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
