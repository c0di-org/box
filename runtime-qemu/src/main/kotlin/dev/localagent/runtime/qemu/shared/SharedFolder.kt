package dev.localagent.runtime.qemu.shared

import android.content.Context
import java.io.File

/**
 * Where the shared folder is, on both sides.
 *
 * ### The name
 *
 * The folder is **Shared**, and in the guest it is `/workspace/shared`. It is the obvious choice,
 * which is the argument for it: this is a name the user reads in their Files app for as long as
 * they have Box installed, and a clever one would need explaining every time. "Drop", "Handoff",
 * "Exchange" all say less than the plain word does.
 *
 * The one place plain is not enough is the Android roots list, where Box's folder sits between
 * Downloads and whatever else is installed and there is no context to make "Shared" mean anything
 * — shared with what? So the published root is titled **Box** and carries "Shared with your box"
 * as its summary. Inside Box the place is just *Shared*, because there the context is the app.
 *
 * The guest keeps the lowercase path because that is what an agent types, and because it sits
 * inside `/workspace` on purpose: agentd's `resolve_path` already refuses every file method a way
 * out of `/workspace`, so the guest half of this feature needed no guard of its own.
 *
 * ### What is not in it
 *
 * `/workspace/.config` — where Claude Code's OAuth credential lives — is not part of this and
 * never can be. The published tree is a *phone* directory; the credential is in the guest, on the
 * workspace disk, and nothing here copies out of anywhere but the shared folder itself. That is
 * structural rather than a rule someone has to remember: an app the user grants access to this
 * folder is looking at files under [on], and the credential is not one of them.
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
