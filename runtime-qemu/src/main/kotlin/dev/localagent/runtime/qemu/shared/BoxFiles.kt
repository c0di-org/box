package dev.localagent.runtime.qemu.shared

/**
 * The three things a sync needs from the box, and deliberately not one more.
 *
 * The port exists so [SharedFolderSync] can be run against a directory on a laptop instead of a
 * booted VM. It is also the shape of the constraint this feature was designed around: *no new
 * agentd methods*. Reading, writing and listing already exist and already refuse to leave
 * `/workspace`, so the guest side of file sharing needed no protocol change at all — which is why
 * a job like this is small rather than a quarter.
 *
 * Paths are relative to the shared folder, so the same string names a file on both sides.
 */
interface BoxFiles {
    /**
     * Every regular file under the shared folder in the guest, with its stamp.
     *
     * Not `list_files`, and the reason is worth stating because the temptation to "just use the
     * API" is strong. `list_files` answers for one directory and reports no modification time —
     * so a tree walk would be one round trip per directory over an emulated CPU, and would still
     * leave the sync unable to tell a changed file from an untouched one of the same size.
     */
    suspend fun snapshot(): Map<String, SharedSync.Stamp>

    suspend fun read(path: String): ByteArray

    /** Creates the parent directories. Fails for a file the transport cannot carry. */
    suspend fun write(path: String, bytes: ByteArray)
}
