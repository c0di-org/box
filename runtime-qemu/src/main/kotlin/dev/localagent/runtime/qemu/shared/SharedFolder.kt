package dev.localagent.runtime.qemu.shared

import android.content.Context
import java.io.File

/**
 * Where the shared folder is, on both sides.
 *
 * The folder is **Shared**, and in the guest `/workspace/shared`. Plain on purpose: the user reads
 * this name in their Files app for as long as Box is installed, and a clever one would need
 * explaining every time. The exception is Android's roots list, where there is no context to make
 * "Shared" mean anything — shared with what? — so the published root is titled **Box** with
 * "Shared with your box" as its summary.
 *
 * The guest path sits inside `/workspace` deliberately: agentd's `resolve_path` already refuses
 * every file method a way out of it, so the guest half needed no guard of its own.
 *
 * `/workspace/.config`, where the OAuth credential lives, is structurally not part of this — an
 * app granted access to this folder sees files under [on], and the credential is not one of them.
 */
object SharedFolder {

    /** The guest's copy. Inside the workspace, so agentd's existing path guard covers it. */
    const val IN_BOX = "/workspace/shared"

    /**
     * The authority the DocumentsProvider is published under, declared in `:app`'s manifest as
     * `${applicationId}.documents`.
     *
     * Derived from the package rather than fixed, because Box ships two flavours whose application
     * ids differ by a suffix — and two installed apps declaring the same provider authority is an
     * install failure, not a warning.
     */
    fun authority(context: Context): String = context.applicationContext.packageName + ".documents"

    /** One root, one id. Stable forever: Android persists grants against it. */
    const val ROOT_ID = "shared"

    /**
     * The real directory on the phone, and the source of truth for everything in it.
     *
     * A sibling of the runtime's own `computer/` tree rather than a child of it. The disks and
     * sockets under `computer/` belong to the VM and are Box's business; this belongs to the user
     * and is the one directory in the app's storage that other apps are invited into.
     */
    fun on(context: Context): File =
        File(context.applicationContext.filesDir, "shared").apply { mkdirs() }

    /** Bookkeeping, deliberately beside the folder rather than in it. See [SharedSyncRecords]. */
    fun records(context: Context): SharedSyncRecords =
        SharedSyncRecords(File(context.applicationContext.filesDir, "shared-sync.json"))

    /**
     * A document id is the path inside the folder, with a leading slash.
     *
     * Android persists a permission grant against these strings, so they have to stay stable
     * across releases — which rules out anything absolute, since the app's data directory carries
     * a user id that changes on a work-profile move. The root is `"/"` rather than the empty
     * string because an empty last path segment does not survive a round trip through a `Uri`.
     */
    const val ROOT_DOCUMENT_ID = "/"

    fun documentId(relative: String): String = "/" + relative.trim('/')

    fun parentDocumentId(relative: String): String =
        documentId(relative.trim('/').substringBeforeLast('/', ""))
}
