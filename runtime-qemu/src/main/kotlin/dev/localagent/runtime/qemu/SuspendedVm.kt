package dev.localagent.runtime.qemu

import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The note a closed box leaves for the process that opens it next.
 *
 * A suspended guest cannot be held in memory: QEMU can only be initialised once per process, so
 * saving the VM means ending the process that hosted it, and `:computer` retires. Everything the
 * next process needs to know therefore has to survive on disk — which snapshot to load, and
 * whether it still belongs to the image that is installed.
 *
 * The identity check is the one that matters. The snapshot lives *inside* the guest's qcow2 disks,
 * so replacing those disks — an app update carrying a newer image, or a reinstall — leaves a note
 * pointing at a snapshot that no longer exists, or worse, at one taken from a different Debian.
 * A note that does not name the installed image is ignored and the box boots cold.
 */
internal data class SuspendedVm(
    val tag: String,
    val image: String,
    val savedAtMillis: Long,
    /** How long the save itself took, so the resume can be reported against its real cost. */
    val saveMillis: Long,
) {
    fun toJson(): String = JSONObject()
        .put(KEY_TAG, tag)
        .put(KEY_IMAGE, image)
        .put(KEY_SAVED_AT, savedAtMillis)
        .put(KEY_SAVE_MILLIS, saveMillis)
        .toString()

    /**
     * Written whole or not at all. A half-written note is indistinguishable from a valid one that
     * names a snapshot which was never finished, and loading that would revert the user's disks
     * to a point the guest's memory does not match.
     */
    fun writeTo(file: File) {
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(toJson(), Charsets.UTF_8)
            atomicReplace(temporary, file)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        /**
         * The snapshot's name inside the qcow2. Fixed rather than generated: a suspended box is a
         * single place a user left off, not a history, and QEMU's `savevm` replaces a snapshot of
         * the same name — so the disks cannot silently accumulate copies of the guest's memory.
         */
        const val TAG = "box-suspend"

        private const val KEY_TAG = "tag"
        private const val KEY_IMAGE = "image"
        private const val KEY_SAVED_AT = "savedAt"
        private const val KEY_SAVE_MILLIS = "saveMillis"

        /** Null for every unreadable form: absent, truncated, or not describing a snapshot. */
        fun read(file: File): SuspendedVm? = runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val tag = json.getString(KEY_TAG)
            val image = json.getString(KEY_IMAGE)
            check(tag.isNotBlank() && image.isNotBlank())
            SuspendedVm(
                tag = tag,
                image = image,
                savedAtMillis = json.optLong(KEY_SAVED_AT),
                saveMillis = json.optLong(KEY_SAVE_MILLIS),
            )
        }.getOrNull()
    }
}
