package dev.localagent.workstation.files

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import dev.localagent.runtime.qemu.shared.SharedFolder
import dev.localagent.workstation.R
import java.io.File
import java.io.FileNotFoundException

/**
 * The shared folder, as a place in Android.
 *
 * This is the whole reason the folder lives on the phone rather than on the guest's disk. Because
 * it is an ordinary directory, publishing it makes copy, edit, create, delete and rename work in
 * the system Files app and in every app's Open/Save dialog with **no code here to implement any of
 * them** — there is no size limit, no transfer to wait on, and it is all readable while the box is
 * closed. The version of this feature that put the provider in front of agentd needed a booted VM,
 * capped files at 64 MiB, pushed every byte through a 64 KiB-framed socket on an emulated CPU, and
 * still could not delete or rename, because agentd has no method for either.
 *
 * ### What is not published
 *
 * Exactly one directory: [SharedFolder.on]. Not `/workspace`, and emphatically not
 * `/workspace/.config`, where Claude Code's OAuth credential lives. Those are on the guest's disk
 * and this provider never speaks to the guest at all, so the credential is not something a rule
 * here has to keep out — it is not reachable from this code by any path. A user deliberately
 * dropping a token into the shared folder is their decision; every app they hand folder access to
 * being able to read credentials that were already there is not.
 *
 * [resolve] still checks, because the tree is handed to other apps and a symlink or a `..` inside
 * a document id would otherwise be a way out of it.
 *
 * ### The manifest permission
 *
 * The declaration carries `android:permission="android.permission.MANAGE_DOCUMENTS"`, which is not
 * a permission Box holds or needs. It *restricts* who may call this provider directly to the
 * system's own document machinery; every other app reaches a file through a per-URI grant the user
 * makes by picking it. This is what `ExternalStorageProvider` does, for the same reason.
 */
class SharedFolderProvider : DocumentsProvider() {

    /** Resolved once. It was a getter, which meant an `mkdirs` syscall for every listed row. */
    private val root: File by lazy { SharedFolder.on(appContext()) }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, SharedFolder.ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, SharedFolder.ROOT_DOCUMENT_ID)
            // Titled "Box", summarised "Shared with your box". In a list next to Downloads and
            // Drive, the word "Shared" on its own answers nothing; the app's name does. See the
            // naming note on [SharedFolder].
            add(Root.COLUMN_TITLE, appContext().getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, appContext().getString(R.string.shared_folder_summary))
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY,
            )
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, root.usableSpace)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { it.addFile(resolve(documentId)) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = resolve(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        parent.listFiles().orEmpty()
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .forEach { cursor.addFile(it) }
        // The box writes into this folder behind the Files app's back, so the listing has to be
        // one the framework can invalidate. [SharedFolderBridge] fires it after every sync.
        cursor.setNotificationUri(
            appContext().contentResolver,
            DocumentsContract.buildChildDocumentsUri(SharedFolder.authority(appContext()), parentDocumentId),
        )
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor =
        ParcelFileDescriptor.open(resolve(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = resolve(parentDocumentId)
        val target = freeName(parent, displayName, mimeType)
        val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!created) throw FileNotFoundException("Could not create $displayName")
        announce(parentDocumentId)
        return documentIdOf(target)
    }

    override fun deleteDocument(documentId: String) {
        val target = resolve(documentId)
        // Worked out before the file is gone, so the notification names a real parent.
        val parent = SharedFolder.parentDocumentId(relativeOf(target))
        if (!target.deleteRecursively()) throw FileNotFoundException("Could not delete $documentId")
        announce(parent)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val target = resolve(documentId)
        val parent = target.parentFile ?: throw FileNotFoundException("Cannot rename the root")
        val renamed = File(parent, displayName)
        guard(renamed)
        if (renamed.exists() || !target.renameTo(renamed)) {
            throw FileNotFoundException("Could not rename $documentId")
        }
        announce(SharedFolder.parentDocumentId(relativeOf(target)))
        return documentIdOf(renamed)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        resolve(documentId).canonicalPath.startsWith(resolve(parentDocumentId).canonicalPath + File.separator)

    override fun getDocumentType(documentId: String): String = mimeTypeOf(resolve(documentId))

    // ---- names and paths ---------------------------------------------------

    /**
     * A document id to a file, refusing anything that leaves the folder.
     *
     * The canonical check catches both halves of the problem: a `..` in the id, and a symlink
     * inside the folder pointing somewhere else. Neither can be created through this provider —
     * but the folder is deliberately reachable by other apps, so "nothing we wrote can do it" is
     * not the same as "it cannot happen".
     */
    private fun resolve(documentId: String): File {
        val relative = documentId.trim('/')
        val file = if (relative.isEmpty()) root else File(root, relative)
        guard(file)
        if (!file.exists()) throw FileNotFoundException("No such document: $documentId")
        return file
    }

    private fun guard(file: File) {
        val inside = root.canonicalPath
        val candidate = file.canonicalPath
        if (candidate != inside && !candidate.startsWith(inside + File.separator)) {
            throw FileNotFoundException("That is not inside the shared folder")
        }
    }

    private fun relativeOf(file: File): String =
        file.canonicalPath.removePrefix(root.canonicalPath).trim('/')

    private fun documentIdOf(file: File): String = SharedFolder.documentId(relativeOf(file))

    /** Never overwrite by creating. `notes.md` twice is `notes.md` and `notes (1).md`. */
    private fun freeName(parent: File, displayName: String, mimeType: String): File {
        val requested = displayName.substringAfterLast('/').ifBlank { "file" }
        val withExtension = withExtensionFor(requested, mimeType)
        val stem = withExtension.substringBeforeLast('.', withExtension)
        val extension = withExtension.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var candidate = File(parent, withExtension)
        var attempt = 1
        while (candidate.exists()) candidate = File(parent, "$stem (${attempt++})$extension")
        guard(candidate)
        return candidate
    }

    /**
     * Other apps hand over a display name and a MIME type separately, and often no extension. A
     * file called `note` that is really text is one the phone cannot open afterwards.
     */
    private fun withExtensionFor(displayName: String, mimeType: String): String {
        if (mimeType == Document.MIME_TYPE_DIR) return displayName
        val known = MimeTypeMap.getSingleton()
        if (known.getMimeTypeFromExtension(displayName.substringAfterLast('.', "")) == mimeType) {
            return displayName
        }
        val extension = known.getExtensionFromMimeType(mimeType) ?: return displayName
        return "$displayName.$extension"
    }

    private fun mimeTypeOf(file: File): String = when {
        file.isDirectory -> Document.MIME_TYPE_DIR
        else -> MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
    }

    private fun MatrixCursor.addFile(file: File) {
        val directory = file.isDirectory
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentIdOf(file))
            // The root carries the app's name; the internal directory it happens to sit in is
            // an implementation detail nobody should be shown.
            val name = relativeOf(file).ifEmpty { appContext().getString(R.string.app_name) }
            add(Document.COLUMN_DISPLAY_NAME, name.substringAfterLast('/'))
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(
                Document.COLUMN_FLAGS,
                (if (directory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE) or
                    Document.FLAG_SUPPORTS_DELETE or
                    Document.FLAG_SUPPORTS_RENAME,
            )
        }
    }

    private fun announce(documentId: String) {
        runCatching {
            appContext().contentResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(SharedFolder.authority(appContext()), documentId),
                null,
            )
        }
    }

    /** Not `requireContext()`: that is `ContentProvider`'s own, and it needs API 30. */
    private fun appContext() = checkNotNull(context) { "The provider has no context" }

    private companion object {
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
        )
        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
