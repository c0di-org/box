package dev.localagent.workstation.agent

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reads a session's log exactly once, whether it arrives from disk or over the wire.
 *
 * A session outlives the UI process, so when a new one attaches, everything said while nobody was
 * watching is already in a log file *and* new output is arriving live. Both must reach the
 * transcript, in order, nothing twice and nothing missed.
 *
 * The runtime service writes each chunk to the log *before* announcing it, stamped with the offset
 * it was written at — so this keeps one number, how far it has read, and any chunk behind that is
 * already accounted for.
 *
 * Byte-oriented on purpose: a chunk boundary can fall inside a multi-byte character or mid-line, so
 * decoding per chunk would corrupt text and split JSON. Only whole lines are decoded.
 */
internal class SessionLogCursor {
    private val pending = ByteArrayOutputStream()

    /** Bytes of the log accounted for so far. */
    var consumed: Long = 0
        private set

    /**
     * Everything already written to [file], from wherever this cursor left off.
     *
     * Read before live chunks are applied: it establishes the watermark they are measured against.
     */
    fun readFile(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val bytes = runCatching {
            file.inputStream().use { stream ->
                stream.skip(consumed)
                stream.readBytes()
            }
        }.getOrElse { return emptyList() }
        consumed += bytes.size
        return drain(bytes)
    }

    /**
     * A live chunk that begins at [offset] in the log.
     *
     * Chunks wholly behind the watermark were in the file already and are dropped. A chunk that
     * straddles it — the file was read part-way through this write — contributes only its tail.
     */
    fun accept(offset: Long, bytes: ByteArray): List<String> {
        val end = offset + bytes.size
        if (end <= consumed) return emptyList()
        val skip = (consumed - offset).coerceAtLeast(0L).toInt()
        consumed = end
        return drain(bytes.copyOfRange(skip, bytes.size))
    }

    /** Splits on newlines, holding back a trailing partial line until the rest of it arrives. */
    private fun drain(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] != NEWLINE) continue
            pending.write(bytes, start, index - start)
            lines += pending.toByteArray().toString(Charsets.UTF_8)
            pending.reset()
            start = index + 1
        }
        if (start < bytes.size) pending.write(bytes, start, bytes.size - start)
        return lines
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}
