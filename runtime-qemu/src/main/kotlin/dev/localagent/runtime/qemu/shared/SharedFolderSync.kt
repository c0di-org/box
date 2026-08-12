package dev.localagent.runtime.qemu.shared

import java.io.File
import java.io.IOException

/**
 * One pass of the shared folder, phone and box.
 *
 * Does what [SharedSync] decided and forms no opinions of its own. Everything here is `java.io`
 * and the [BoxFiles] port — no Android, no `Context`, no coroutine machinery beyond `suspend` —
 * so a whole sync including the conflict path can be run in a unit test against two temporary
 * directories.
 *
 * A pass is **idempotent and self-healing**. Anything that fails is simply not recorded as agreed,
 * so the next pass plans it again. That is what makes the trigger question uninteresting: the sync
 * does not need to be run at exactly the right moment, only eventually.
 */
class SharedFolderSync(
    private val folder: File,
    private val box: BoxFiles,
    private val records: SharedSyncRecords,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What a pass did, in the terms the user would use. Persisted, so the UI can say it. */
    data class Outcome(
        val atMillis: Long,
        val pushedIn: List<String> = emptyList(),
        val broughtOut: List<String> = emptyList(),
        /** The `.from-box` copies actually written, i.e. real disagreements. */
        val kept: List<String> = emptyList(),
        val trouble: List<Trouble> = emptyList(),
    ) {
        val quiet: Boolean get() = pushedIn.isEmpty() && broughtOut.isEmpty() && kept.isEmpty()
    }

    data class Trouble(val path: String, val reason: String)

    suspend fun run(): Outcome {
        folder.mkdirs()
        val onPhone = scanPhone()
        val inBox = box.snapshot()
        val known = records.load()

        val next = known.toMutableMap()
        val pushedIn = mutableListOf<String>()
        val broughtOut = mutableListOf<String>()
        val kept = mutableListOf<String>()
        val trouble = mutableListOf<Trouble>()
        // Which paths need their stamps re-read afterwards, because bytes moved under them.
        val settle = mutableSetOf<String>()
        var boxChanged = false

        for (action in SharedSync.plan(onPhone, inBox, known)) {
            val path = action.path
            if (!isSafe(path)) {
                // A path that climbs out of the folder is not a file this is willing to name. It
                // cannot come from the phone side — the provider refuses to mint one — so it would
                // have to come from a guest listing, which is exactly when refusing matters.
                trouble += Trouble(path, "that name is not allowed")
                next -= path
                continue
            }
            try {
                when (action) {
                    is SharedSync.SyncAction.Untrack -> next -= path

                    is SharedSync.SyncAction.Push -> {
                        box.write(path, phoneFile(path).readBytes())
                        boxChanged = true
                        pushedIn += path
                        settle += path
                    }

                    is SharedSync.SyncAction.Pull -> {
                        writeToPhone(path, box.read(path))
                        broughtOut += path
                        settle += path
                    }

                    is SharedSync.SyncAction.Resolve -> {
                        // Both versions have to be in hand to write the backup anyway, so this is
                        // the one place where comparing the actual bytes is free. Two sides that
                        // drifted to the *same* content are not a disagreement, and a `.from-box`
                        // file for one would be litter the user has to reason about.
                        val fromBox = box.read(path)
                        val fromPhone = phoneFile(path).readBytes()
                        if (!fromBox.contentEquals(fromPhone)) {
                            if (!isSafe(action.boxCopy)) throw IOException("cannot name a copy of $path")
                            writeToPhone(action.boxCopy, fromBox)
                            box.write(path, fromPhone)
                            boxChanged = true
                            kept += action.boxCopy
                            pushedIn += path
                        }
                        settle += path
                    }
                }
            } catch (error: Exception) {
                trouble += Trouble(path, error.message ?: "could not be copied")
                // Never leave a record claiming the two sides agree when the copy did not happen.
                next -= path
            }
        }

        val phoneAfter = if (broughtOut.isEmpty() && kept.isEmpty()) onPhone else scanPhone()
        val boxAfter = if (boxChanged) box.snapshot() else inBox
        settle.forEach { path ->
            val phone = phoneAfter[path]
            val guest = boxAfter[path]
            if (phone != null && guest != null) {
                next[path] = SharedSync.Record(phone, guest)
            } else {
                // The copy reported success but the file is not on both sides. Forget it and let
                // the next pass see it fresh rather than record a fiction.
                next -= path
            }
        }

        val outcome = Outcome(now(), pushedIn, broughtOut, kept, trouble)
        records.save(next, outcome)
        return outcome
    }

    /**
     * The phone's tree, as relative paths.
     *
     * Symlinks are skipped rather than followed. Nothing Box or the Files app can do creates one
     * here, so this is not a case that has to work — but the folder is handed to other apps, and a
     * link pointing at the guest's disk image would otherwise be a way to read it.
     */
    private fun scanPhone(): Map<String, SharedSync.Stamp> {
        val root = folder.canonicalFile
        val found = LinkedHashMap<String, SharedSync.Stamp>()
        fun walk(directory: File, prefix: String) {
            val children = directory.listFiles() ?: return
            children.sortedBy { it.name }.forEach { child ->
                if (child.canonicalFile.parentFile != directory.canonicalFile) return@forEach
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                when {
                    child.isDirectory -> walk(child, relative)
                    child.isFile -> found[relative] = SharedSync.Stamp(child.length(), child.lastModified())
                }
            }
        }
        walk(root, "")
        return found
    }

    private fun phoneFile(path: String) = File(folder, path)

    /**
     * Write beside the folder, then move in.
     *
     * Two reasons, and the second is the one that bites. The folder is published to every app on
     * the phone, so a file that is halfway written is a file some other app can open halfway — a
     * rename means they see the old bytes or the new ones and never a truncated middle. And the
     * staging file is deliberately *outside* the published tree: `:computer` is a process Android
     * kills, and a half-written file left inside the folder would be read by the next pass as one
     * of the user's own and copied into the box.
     */
    private fun writeToPhone(path: String, bytes: ByteArray) {
        val destination = phoneFile(path)
        destination.parentFile?.mkdirs()
        val staging = File(folder.parentFile, "${folder.name}-incoming").apply { mkdirs() }
        val temporary = File(staging, destination.name + ".incoming")
        try {
            temporary.writeBytes(bytes)
            // Same filesystem, so the move is atomic. A rename that fails is not worth falling
            // back on a direct write for — that is the exact case this exists to avoid.
            check(temporary.renameTo(destination)) { "could not be moved into the shared folder" }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        /** Relative, no climbing, no absolute escape. The same guard agentd applies on its side. */
        fun isSafe(path: String): Boolean =
            path.isNotEmpty() &&
                !path.startsWith('/') &&
                path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}
