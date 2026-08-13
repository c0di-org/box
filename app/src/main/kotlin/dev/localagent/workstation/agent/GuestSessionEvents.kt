package dev.localagent.workstation.agent

import dev.localagent.runtime.qemu.IAgentSession
import dev.localagent.runtime.qemu.IAgentSessionCallback

/**
 * The two pieces every guest program Box drives directly needs, in one place.
 *
 * Signing in to Claude and connecting to GitHub are the same shape of thing — a short-lived
 * program in the guest that narrates itself in JSON lines and waits for an answer — and they were
 * about to grow their own copies of the same twenty lines. Shared rather than duplicated because
 * the framing rule below is a real bug when it is got wrong, and one of two copies is exactly how
 * it gets got wrong.
 */

/**
 * Reassembles whole event lines out of arbitrary chunks.
 *
 * The guest writes one event per line, but a pipe splits wherever it likes. Parsing what happens to
 * arrive would drop an event whose newline landed in the next chunk.
 */
internal class LineBuffer {
    private val pending = StringBuilder()

    fun absorb(text: String, onLine: (String) -> Unit) {
        pending.append(text)
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) break
            val line = pending.substring(0, newline).trim()
            pending.delete(0, newline + 1)
            if (line.isNotEmpty()) onLine(line)
        }
    }
}

/** Defaults, so each use only overrides the callbacks it actually cares about. */
internal abstract class GuestSessionCallback : IAgentSessionCallback.Stub() {
    override fun onAttached(session: IAgentSession?, logPath: String) = Unit
    override fun onData(offset: Long, chunk: ByteArray) = Unit
    override fun onDiagnostic(text: String) = Unit
    override fun onClosed(exitCode: Int, error: String?) = Unit
}
