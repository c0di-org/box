package dev.localagent.runtime.qemu;

/**
 * A live agent session running in `:computer`.
 *
 * The handle is what makes a permission prompt possible: the harness keeps running while the user
 * thinks, and [write] reaches its stdin whenever they answer. Holding this object does not keep
 * the session alive — the session belongs to `:computer` and outlives any UI process that drops it.
 */
interface IAgentSession {
    /** A decision, a follow-up message: anything the harness is waiting to read. */
    oneway void write(in byte[] data);

    /** Half-close. The harness sees EOF and finishes what it was doing. */
    oneway void closeInput();

    /** Stop now. Safe on an already-dead session. */
    oneway void cancel();
}
