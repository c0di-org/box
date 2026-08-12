package dev.localagent.runtime.qemu.shared

import dev.localagent.runtime.api.ComputerRuntime
import dev.localagent.runtime.api.ExecRequest

/**
 * [BoxFiles] over a real guest.
 *
 * Three calls, all of which agentd already answered before this feature existed. `read_file` and
 * `write_file` carry the bytes; the listing is a shell command rather than `list_files`, for the
 * two reasons in [BoxFiles.snapshot] — one round trip for the whole tree instead of one per
 * directory, and a modification time, which `list_files` does not report.
 */
internal class RuntimeBoxFiles(private val runtime: ComputerRuntime) : BoxFiles {

    override suspend fun snapshot(): Map<String, SharedSync.Stamp> {
        val result = runtime.exec(
            ExecRequest(listOf("/bin/sh", "-c", SNAPSHOT), timeoutSeconds = SNAPSHOT_TIMEOUT_SECONDS),
        )
        check(result.exitCode == 0) {
            "The box could not list its shared folder: " +
                result.stderr.trim().ifBlank { "exit ${result.exitCode}" }
        }

        // NUL-separated, because a newline is a legal character in a filename and a listing that
        // can be split wrongly by a filename is a listing that copies the wrong file.
        val records = result.stdout.split(RECORD_SEPARATOR).filter { it.isNotEmpty() }
        check(records.size <= MAX_ENTRIES) {
            "The shared folder in the box holds more than $MAX_ENTRIES files"
        }
        return records.mapNotNull { record ->
            val fields = record.split('\t', limit = 3)
            if (fields.size < 3) return@mapNotNull null
            val size = fields[0].toLongOrNull() ?: return@mapNotNull null
            fields[2] to SharedSync.Stamp(size, epochMillis(fields[1]))
        }.toMap()
    }

    override suspend fun read(path: String): ByteArray = runtime.readFile("$IN_BOX/$path")

    override suspend fun write(path: String, bytes: ByteArray) = runtime.writeFile("$IN_BOX/$path", bytes)

    private companion object {
        val IN_BOX = SharedFolder.IN_BOX

        /**
         * `mkdir` first, so the folder exists in the guest from the first sync onwards even when
         * there is nothing to put in it. An agent that has been told to leave a file for the user
         * should find the place already there rather than have to know to create it.
         *
         * `%T@` is seconds with a fraction, `%P` is the path with the `./` start point stripped,
         * and the records are separated by NUL rather than newline.
         */
        const val SNAPSHOT =
            """mkdir -p /workspace/shared && cd /workspace/shared && find . -type f -printf '%s\t%T@\t%P\0'"""

        const val RECORD_SEPARATOR = '\u0000'
        const val SNAPSHOT_TIMEOUT_SECONDS = 60

        /** A sanity bound, not a product limit. A tree this size means something has gone wrong. */
        const val MAX_ENTRIES = 10_000

        /**
         * `1723459200.1234567890` to milliseconds. Anything unparseable stamps as zero, which
         * reads as "changed" and costs one redundant copy rather than a missed one.
         */
        fun epochMillis(value: String): Long {
            val seconds = value.substringBefore('.').toLongOrNull() ?: return 0L
            val fraction = value.substringAfter('.', "").takeWhile(Char::isDigit).take(3).padEnd(3, '0')
            return seconds * 1_000 + (fraction.toLongOrNull() ?: 0L)
        }
    }
}
