package dev.localagent.runtime.qemu

import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Who owns a payload once it is on the device, which is the only thing that decides whether it may
 * be overwritten.
 *
 * The distinction is the whole point of this file. Three of a guest image's four files belong to
 * the APK and are as replaceable as the app itself; the fourth is the user's Linux machine and
 * must survive every update Box will ever ship. Attaching that to the role rather than to a
 * `preserveExisting` flag at each call site is what stops the two being confused.
 */
enum class GuestImageOwner {
    /** The image's own bytes. A new image version is entitled to replace them. */
    IMAGE,

    /** The user's work. Never replaced once it exists — not by an update, not by a new image. */
    USER,
}

/**
 * What a payload *is*, as opposed to what it happens to be called.
 *
 * The build once shipped four names in a fixed order and three places agreed on that order by
 * hand. A role says which of the four a file is, so the manifest can rename `base-system.qcow2`
 * or a different image can call it something else without anything downstream noticing.
 *
 * @param wire the value used in `image.json`; changing one breaks every manifest already built.
 * @param installedName the name the payload is stored under on the device. Deliberately *not* the
 *   manifest's filename: two images may use different source names for the same role, and the
 *   installed layout should not inherit that. These match the historical flat names, which is what
 *   lets an already-provisioned device migrate by moving files rather than re-downloading them.
 * @param rewritable true when QEMU only ever reads the file, so a copy that fails verification can
 *   simply be replaced. The disks are false: the guest writes to them from its first boot, so
 *   their bytes stop matching the manifest immediately and a hash says nothing about their health.
 */
enum class GuestImageRole(
    val wire: String,
    val installedName: String,
    val owner: GuestImageOwner,
    val rewritable: Boolean,
) {
    KERNEL("kernel", "kernel", GuestImageOwner.IMAGE, rewritable = true),
    INITRD("initrd", "initrd.img", GuestImageOwner.IMAGE, rewritable = true),
    SYSTEM("system", "system.qcow2", GuestImageOwner.IMAGE, rewritable = false),
    WORKSPACE("workspace", "workspace.qcow2", GuestImageOwner.USER, rewritable = false);

    companion object {
        fun ofWire(value: String): GuestImageRole? = entries.firstOrNull { it.wire == value }
    }
}

/** One file of an image: which role it fills, what it is called in the APK, and its digest. */
data class GuestImagePayload(
    val role: GuestImageRole,
    val assetName: String,
    val sha256: String,
    val bytes: Long,
)

/**
 * Which image this is, and the only comparison provisioning actually makes.
 *
 * `id` is the machine — a bare Ubuntu and a Claude box are different ids, and may sit on disk at
 * the same time. `version` is that machine's contents; the image build derives it from the payload
 * digests, so any rebuild produces a new one. Same id and same version means "already installed";
 * anything else means there is work to do.
 */
data class GuestImageIdentity(val id: String, val version: String) {
    override fun toString(): String = "$id@$version"
}

/**
 * Where one installed image's payloads actually are on this device.
 *
 * Resolved once from the manifest and handed to whatever needs paths, so that the code building a
 * QEMU command line no longer holds four fixed fields and, with them, the assumption that there is
 * only ever one image.
 */
data class GuestImageFiles(
    val identity: GuestImageIdentity,
    val kernel: File,
    val initrd: File,
    val system: File,
    val workspace: File,
)

/** A harness the image advertises. Nothing consumes this yet; it is here so the image can say. */
data class GuestHarness(val id: String, val name: String, val entry: String)

/**
 * What an image claims to offer, for a chooser that does not exist yet.
 *
 * Deliberately parsed and carried even though nothing reads it: the alternative is a manifest that
 * has to be re-versioned the first time Box wants to ask "does this image have a desktop?", and by
 * then there are devices in the field holding manifests that cannot answer.
 */
data class GuestImageContents(
    val desktop: Boolean,
    val harnesses: List<GuestHarness>,
    val sourceCommit: String?,
    val sourcePath: String?,
)

/**
 * `image.json`, as written by `guest/build-image.sh` and shipped in the APK beside its payloads.
 */
data class GuestImageManifest(
    val schema: Int,
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val payloads: List<GuestImagePayload>,
    val contents: GuestImageContents,
) {
    val identity: GuestImageIdentity get() = GuestImageIdentity(id, version)

    /**
     * The directory name this image's files live under.
     *
     * The id and *not* the version, which is worth being explicit about because the obvious
     * reading of "keyed by id and version" is a path containing both. A version in the path would
     * mean every rebuilt image arrives at a directory with no workspace in it, and the user's
     * Linux machine would be silently replaced by an empty one on each update — the exact
     * accident this whole change exists to make impossible. Versions of one image are the same
     * machine brought up to date, so they share a directory; different ids are different machines,
     * so they do not. The version's job is the identity check, not the layout.
     *
     * Safe as a path element because [parse] refuses an id that is not.
     */
    val storageKey: String get() = id

    fun payload(role: GuestImageRole): GuestImagePayload =
        payloads.firstOrNull { it.role == role }
            ?: error("Guest image $identity declares no $role payload")

    companion object {
        /** The name the manifest is shipped under, inside the APK's `guest/` asset directory. */
        const val ASSET_NAME = "image.json"

        /**
         * Every role the runtime needs to boot. An image missing one of these is not a partial
         * image, it is an unbootable one, so it is rejected where it can still be a build failure.
         */
        val REQUIRED_ROLES: Set<GuestImageRole> = GuestImageRole.entries.toSet()

        /**
         * Lowercase, and no separators or dots.
         *
         * This id becomes a directory name under the app's private files. Nothing today writes a
         * manifest Box did not build, but the point of describing an image is that one day
         * something might, and `../../` is the difference between a keyed layout and an arbitrary
         * write. Refusing here costs nothing and keeps [storageKey] trivially safe.
         */
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,63}")

        /**
         * Ids that would collide with a file rather than name a directory.
         *
         * [storageKey] puts an image's files in `images/<id>/`, next to the flat filenames the
         * pre-manifest layout used and that a device upgrading from it still has. Only `kernel`
         * can actually clash — every other legacy name contains a dot, which [ID_PATTERN] already
         * forbids — but the set is written out because the reason is the collision, not the dot.
         */
        private val RESERVED_IDS: Set<String> =
            GuestImageRole.entries.map { it.installedName }.toSet() + "base-system.qcow2"
        private val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

        /** Payload filenames are resolved against an asset directory, so they must stay flat. */
        private val ASSET_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun parse(json: String): GuestImageManifest {
            val root = try {
                JSONObject(json)
            } catch (error: JSONException) {
                throw IllegalArgumentException("Guest image manifest is not valid JSON", error)
            }

            val schema = root.optInt("schema", 0)
            require(schema == SUPPORTED_SCHEMA) {
                "Guest image manifest schema $schema is not supported (this build reads $SUPPORTED_SCHEMA)"
            }

            val id = root.optString("id").trim()
            require(ID_PATTERN.matches(id)) { "Guest image manifest has an unusable id: '$id'" }
            require(id !in RESERVED_IDS) { "Guest image manifest uses a reserved id: '$id'" }
            val version = root.optString("version").trim()
            require(VERSION_PATTERN.matches(version)) {
                "Guest image $id has an unusable version: '$version'"
            }

            val declared = root.optJSONArray("payloads")
            require(declared != null && declared.length() > 0) {
                "Guest image $id declares no payloads"
            }
            val payloads = List(declared.length()) { index ->
                val entry = declared.optJSONObject(index)
                    ?: throw IllegalArgumentException("Guest image $id has a malformed payload entry")
                parsePayload(id, entry)
            }

            val roles = payloads.map { it.role }
            require(roles.size == roles.toSet().size) {
                "Guest image $id declares the same role twice"
            }
            val missing = REQUIRED_ROLES - roles.toSet()
            require(missing.isEmpty()) {
                "Guest image $id is missing ${missing.joinToString { it.wire }}"
            }

            return GuestImageManifest(
                schema = schema,
                id = id,
                version = version,
                // A blank name is not worth failing a build over; the id is always a usable label.
                name = root.optString("name").trim().ifBlank { id },
                description = root.optString("description").trim(),
                payloads = payloads,
                contents = parseContents(root.optJSONObject("contains")),
            )
        }

        /**
         * Reads only the identity, and tolerates everything else.
         *
         * Used on the record an install leaves behind, which may have been written by a *newer*
         * Box than the one reading it — an app can be downgraded, and a device that has been
         * downgraded must still be able to recognise its own image rather than silently reinstall
         * over it. So this deliberately does not go through [parse]: an unfamiliar schema or a
         * role this build has never heard of is not a reason to fail to recognise an id.
         */
        fun parseIdentity(json: String): GuestImageIdentity? = runCatching {
            val root = JSONObject(json)
            val id = root.optString("id").trim()
            val version = root.optString("version").trim()
            if (id.isEmpty() || version.isEmpty()) null else GuestImageIdentity(id, version)
        }.getOrNull()

        private fun parsePayload(imageId: String, entry: JSONObject): GuestImagePayload {
            val wire = entry.optString("role").trim()
            val role = GuestImageRole.ofWire(wire)
                ?: throw IllegalArgumentException("Guest image $imageId declares unknown role '$wire'")
            val assetName = entry.optString("file").trim()
            require(ASSET_NAME_PATTERN.matches(assetName)) {
                "Guest image $imageId gives role $wire an unusable filename: '$assetName'"
            }
            val sha256 = entry.optString("sha256").trim()
            require(SHA256_PATTERN.matches(sha256)) {
                "Guest image $imageId gives role $wire no usable SHA-256"
            }
            // Only ever used to weight a progress bar, so an image that omits it is still usable.
            val bytes = entry.optLong("bytes", 0L).coerceAtLeast(0L)
            return GuestImagePayload(role, assetName, sha256.lowercase(), bytes)
        }

        private fun parseContents(contains: JSONObject?): GuestImageContents {
            if (contains == null) {
                return GuestImageContents(desktop = false, harnesses = emptyList(), null, null)
            }
            val declared = contains.optJSONArray("harnesses")
            val harnesses = buildList {
                repeat(declared?.length() ?: 0) { index ->
                    val entry = declared?.optJSONObject(index) ?: return@repeat
                    val id = entry.optString("id").trim()
                    val entryPath = entry.optString("entry").trim()
                    if (id.isNotEmpty() && entryPath.isNotEmpty()) {
                        add(GuestHarness(id, entry.optString("name").trim().ifBlank { id }, entryPath))
                    }
                }
            }
            val source = contains.optJSONObject("source")
            return GuestImageContents(
                desktop = contains.optBoolean("desktop", false),
                harnesses = harnesses,
                sourceCommit = source?.optString("commit")?.trim()?.ifBlank { null },
                sourcePath = source?.optString("path")?.trim()?.ifBlank { null },
            )
        }

        private const val SUPPORTED_SCHEMA = 1
    }
}
