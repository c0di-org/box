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
     * Bytes of the log that a chunk starting at [offset] has left behind, or zero.
     *
     * `offset > consumed` is the one arithmetic fact that separates a chunk which went missing
     * from one that merely overlaps what has been read, and it must be asked *before* [accept] —
     * the bytes are on disk (the runtime service appends before it announces), so a gap is
     * recoverable by [readFile] and only by [readFile].
     *
     * A chunk can go missing: `record.chunks` is a buffered flow whose live emission is a
     * `tryEmit` that returns false on a full buffer. That branch exists because the code knows it
     * can happen.
     */
    fun gapBefore(offset: Long): Long = (offset - consumed).coerceAtLeast(0L)

    /**
     * A live chunk that begins at [offset] in the log.
     *
     * Chunks wholly behind the watermark were in the file already and are dropped. A chunk that
     * straddles it — the file was read part-way through this write — contributes only its tail.
     *
     * A chunk that begins *ahead* of the watermark is refused rather than guessed at: see
     * [gapBefore]. This used to be `coerceAtLeast(0)`, which turned the gap into a zero skip and
     * then wrote `consumed = end` — throwing away the signal and moving the watermark past bytes
     * nobody had read, so the later re-read that could have recovered them skipped them too. And
     * because [drain] holds a trailing partial line in `pending`, a chunk dropped mid-line welded
     * its truncated head onto the next survivor's first fragment: a spliced string that either
     * vanished at the parser or, worse, parsed — a transcript line nobody emitted.
     *
     * Refusing costs nothing: the watermark does not move, so the next chunk reports the same gap
     * and the reader recovers from the file.
     */
    fun accept(offset: Long, bytes: ByteArray): List<String> {
        val end = offset + bytes.size
        if (end <= consumed) return emptyList()
        if (offset > consumed) return emptyList()
        val skip = (consumed - offset).toInt()
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
