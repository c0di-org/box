package dev.localagent.workstation.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.localagent.runtime.qemu.shared.SharedFolder
import dev.localagent.workstation.agent.Attachment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a file the user hands to the agent lands.
 *
 * One directory inside the shared folder, so it needs no transport of its own: the folder is
 * already synchronised both ways, already in the phone's Files app, and already inside the box at
 * `/workspace/shared`. Writing here and naming the guest path is the whole mechanism.
 *
 * A subdirectory rather than the folder itself, because the shared folder is a place the user
 * keeps things and attachments are a stream of one-off files pushed at a conversation. Mixing them
 * would fill the documents folder with screenshots named after the second they were taken, and it
 * gives the agent an address for "the thing you just sent me".
 *
 * Names are stamped always, even when nothing collides. Two sync rules make that the cheap choice:
 * a name existing on both sides with different content keeps the phone's and parks the other as
 * `name.from-box`, and *nothing is ever deleted to settle it* — so two screenshots half an hour
 * apart would turn `screenshot.png` into an argument. The stamp is local time to the second, which
 * also keeps the folder readable later: the user's own copy, in the order they sent it.
 */
object Inbox {

    /** Relative to the shared folder on both sides. */
    const val FOLDER = "inbox"

    /**
     * The most a single attachment may be.
     *
     * There is a ceiling whether or not one is chosen: every byte here is copied into a VM disk on
     * a phone, over a virtio channel, on a machine with no hardware virtualisation. 32 MiB is a
     * photograph, a slide deck, or a long PDF, and is not a video — which is the honest boundary of
     * what an agent in this box can do anything useful with anyway. Refusing loudly is better than
     * a copy that appears to work and stalls the guest for a minute.
     */
    const val MAX_BYTES = 32L * 1024 * 1024

    /** What the guest sees. The only address that means anything to the agent. */
    fun guestPath(name: String): String = "${SharedFolder.IN_BOX}/$FOLDER/$name"

    /** The phone-side directory. Created on demand; the sync makes the guest's copy. */
    fun on(context: Context): File =
        File(SharedFolder.on(context), FOLDER).apply { mkdirs() }

    /**
     * The phone's copy of something already attached, for drawing a thumbnail.
     *
     * Derived from the guest path rather than carried beside it, because the guest path is the one
     * the agent was told and therefore the one that must be right; a second stored path could
     * disagree with it. Null for anything that is not in this box's inbox — including a path an
     * older transcript recorded before this existed.
     */
    fun phoneFile(context: Context, guestPath: String): File? {
        val prefix = "${SharedFolder.IN_BOX}/$FOLDER/"
        if (!guestPath.startsWith(prefix)) return null
        val name = guestPath.removePrefix(prefix)
        if (name.isEmpty() || name.contains('/')) return null
        return File(on(context), name).takeIf { it.isFile }
    }

    /**
     * Copies what [uri] points at into the inbox and describes it.
     *
     * Returns null when the copy could not be made, which is a normal outcome rather than an
     * exceptional one: a share sheet hands over a uri another app owns, that app can revoke it or
     * die, and a picture that cannot be read is something to tell the user about rather than crash
     * over. The caller reports [Refusal] to them; nothing is left half-written either way.
     */
    fun receive(context: Context, uri: Uri): Result<Attachment> {
        val resolver = context.contentResolver
        val described = describe(context, uri)
        if (described.bytes > MAX_BYTES) {
            return Result.failure(Refusal("${described.name} is too big to put in the box (limit 32 MB)"))
        }

        val name = stamped(described.name)
        val destination = File(on(context), name)
        return runCatching {
            var written = 0L
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "nothing to read" }
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        // The size a content provider reports is a claim, not a measurement, and a
                        // stream can be longer than its own metadata says. Checked as it is copied
                        // so a wrong claim costs a partial file rather than the ceiling.
                        if (written > MAX_BYTES) throw Refusal("${described.name} is too big to put in the box (limit 32 MB)")
                        output.write(buffer, 0, read)
                    }
                }
            }
            Attachment(
                guestPath = guestPath(name),
                name = described.name,
                mimeType = described.mimeType,
                bytes = written,
            )
        }.onFailure { failure ->
            destination.delete()
            if (failure !is Refusal) Log.w(TAG, "could not take in $uri", failure)
        }.recoverCatching { failure ->
            throw if (failure is Refusal) failure else Refusal("That file could not be opened.")
        }
    }

    /** A reason the user should see, as opposed to a stack trace they should not. */
    class Refusal(override val message: String) : Exception(message)

    private data class Described(val name: String, val mimeType: String, val bytes: Long)

    /**
     * What the other app says this file is.
     *
     * Everything here is that app's word: the display name, the size, and the type. None of it is
     * trusted as a *path* — [stamped] rebuilds the name from scratch — because a display name is
     * the one field a hostile sender controls completely, and `../../` in it would otherwise be a
     * write outside the folder.
     */
    private fun describe(context: Context, uri: Uri): Described {
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val cursor = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )
        }.getOrNull() ?: return Described(fallbackName, mimeType, 0L)

        return cursor.use {
            if (!it.moveToFirst()) return@use Described(fallbackName, mimeType, 0L)
            val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
            Described(
                name = if (nameColumn >= 0 && !it.isNull(nameColumn)) it.getString(nameColumn) else fallbackName,
                mimeType = mimeType,
                bytes = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn) else 0L,
            )
        }
    }

    /**
     * `20260812-214755-holiday.png` — the local time to the second, then a name built only from
     * characters that cannot mean anything to a path.
     */
    internal fun stamped(displayName: String, now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val safe = displayName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .map { if (it.isLetterOrDigit() || it in "._-") it else '-' }
            .joinToString("")
            .trim('.', '-')
            .take(80)
        return if (safe.isEmpty()) "$stamp-file" else "$stamp-$safe"
    }

    private const val TAG = "BoxInbox"
}
