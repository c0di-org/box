package dev.localagent.runtime.qemu.shared

import android.content.Context
import android.os.FileObserver
import android.provider.DocumentsContract
import android.util.Log
import dev.localagent.runtime.api.ComputerRuntime
import dev.localagent.runtime.api.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * When a sync happens.
 *
 * Lives in `:computer` rather than the UI process, for the reason the session log does: Android
 * kills the Compose process whenever it likes, and an agent working while the phone is pocketed is
 * the normal case. A sync needing the UI alive would not run when it most matters. The folder is
 * under `filesDir`, one directory per app rather than per process, so both sides see one tree.
 *
 * Three triggers:
 *
 * **The box finishes booting** — everything added while it was closed goes in at once, and this is
 * the pass that creates `/workspace/shared` so an agent finds it already there.
 *
 * **The folder changes while the box is up** — inotify, not a poll, so a file copied in from the
 * Files app is in the box a second later.
 *
 * **The agent goes quiet** — the one that brings the agent's own files out. Both the end of a
 * session and the end of each turn within it, because an agent works for an hour in turns and a
 * file made in the first minute should not be invisible for the other fifty-nine; the moment it is
 * most wanted is right after the agent says it made it. A turn ending is a real quiescent point
 * rather than a guess at one — the agent has stopped writing, by its own account — which is what
 * makes this cheap where a poll is not, and it is still nothing at all while the box sits idle.
 *
 * The alternatives, for the record: on opening Box's Files panel only helps someone already inside
 * Box, which is the opposite of the point; on shutdown is too late and unreliable, since the
 * process that would run it is the one being killed; a timer is work done on a schedule that has
 * no relationship to when anything changed. An agent that leaves a file *mid-turn* and keeps
 * working still waits for the end of that turn, which is the remaining price of not polling — and
 * a much smaller one than waiting for the session.
 *
 * Nothing here promises *when*. A pass is idempotent and self-healing, so a missed trigger costs a
 * delay and never a lost file.
 */
internal class SharedFolderBridge(
    context: Context,
    private val runtime: ComputerRuntime,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val folder = SharedFolder.on(appContext)
    private val sync = SharedFolderSync(folder, RuntimeBoxFiles(runtime), SharedFolder.records(appContext))

    /**
     * Conflated on purpose: fifty files dropped in at once are one sync, not fifty. The reason
     * string is only ever logged — it exists so a log line says *why* a pass ran.
     */
    private val requests = Channel<String>(Channel.CONFLATED)
    private var pump: Job? = null
    private val watcher = FolderWatcher(folder) { ask("the folder changed") }

    /** The box is usable. Catch up on everything, then watch. */
    fun onRuntimeState(state: RuntimeState) {
        if (state == RuntimeState.Ready) {
            start()
            ask("the box opened")
        } else {
            stop()
        }
    }

    /** An agent stopped talking, for a turn or for good. This is what brings its files out. */
    fun onAgentQuiet() = ask("the agent went quiet")

    fun stop() {
        watcher.stop()
        pump?.cancel()
        pump = null
    }

    private fun start() {
        if (pump != null) return
        watcher.start()
        pump = scope.launch {
            for (reason in requests) {
                // Settle first. A copy in the Files app is a create followed by writes followed by
                // a close, and a file being written is not a file worth carrying into the box.
                delay(SETTLE_MILLIS)
                requests.tryReceive()
                if (runtime.state().value != RuntimeState.Ready) continue
                runCatching { runOnce(reason) }
                    .onFailure { Log.w(TAG, "The shared folder could not be synced ($reason)", it) }
            }
        }
    }

    private suspend fun runOnce(reason: String) {
        val outcome = sync.run()
        if (outcome.quiet && outcome.trouble.isEmpty()) return

        // Bringing a file out writes into the watched folder, so this pass has already asked for
        // another one. That one finds nothing to do and ends the chain; it is one wasted listing
        // rather than a loop.
        Log.i(
            TAG,
            "Shared folder synced ($reason): ${outcome.pushedIn.size} in, " +
                "${outcome.broughtOut.size} out, ${outcome.kept.size} kept, " +
                "${outcome.trouble.size} could not be copied",
        )
        announce(outcome.broughtOut + outcome.kept)
    }

    /**
     * Tell Android's Files app that the folder moved under it.
     *
     * Without this a file the agent produced sits on the phone unseen until the user pulls to
     * refresh — which, for a file they were told had arrived, reads as it not having arrived.
     */
    private fun announce(paths: List<String>) {
        if (paths.isEmpty()) return
        val resolver = appContext.contentResolver
        (paths.map { SharedFolder.parentDocumentId(it) }.toSet() + SharedFolder.ROOT_DOCUMENT_ID)
            .forEach { parent ->
                runCatching {
                    resolver.notifyChange(
                        DocumentsContract.buildChildDocumentsUri(SharedFolder.authority(appContext), parent),
                        null,
                    )
                }
            }
    }

    private fun ask(reason: String) {
        requests.trySend(reason)
    }

    /**
     * inotify over a tree.
     *
     * `FileObserver` watches one directory and does not recurse, so every directory gets its own
     * watch and the set is rebuilt whenever anything appears or disappears. The alternative — a
     * single watch on the root — would miss a file dropped into a subfolder, which is exactly what
     * happens the first time somebody copies a project in.
     */
    private class FolderWatcher(private val root: File, private val onChange: () -> Unit) {
        private val watches = mutableMapOf<String, FileObserver>()
        private var watching = false

        @Synchronized
        fun start() {
            if (watching) return
            watching = true
            rewatch()
        }

        @Synchronized
        fun stop() {
            watching = false
            watches.values.forEach(FileObserver::stopWatching)
            watches.clear()
        }

        @Synchronized
        private fun rewatch() {
            if (!watching) return
            val directories = mutableSetOf<String>()
            fun walk(directory: File) {
                directories += directory.path
                directory.listFiles()?.filter { it.isDirectory }?.forEach(::walk)
            }
            if (root.isDirectory) walk(root)

            (watches.keys - directories).forEach { gone ->
                watches.remove(gone)?.stopWatching()
            }
            (directories - watches.keys).forEach { fresh ->
                val observer = object : FileObserver(File(fresh), MASK) {
                    override fun onEvent(event: Int, path: String?) {
                        // A new subdirectory needs its own watch before anything is written into
                        // it, so the set is rebuilt on every event rather than on a timer.
                        rewatch()
                        onChange()
                    }
                }
                watches[fresh] = observer
                observer.startWatching()
            }
        }

        private companion object {
            /**
             * `CLOSE_WRITE` rather than `MODIFY`: a file written in chunks fires once, when the
             * writer is done with it, instead of once per buffer.
             */
            const val MASK = FileObserver.CREATE or
                FileObserver.CLOSE_WRITE or
                FileObserver.DELETE or
                FileObserver.MOVED_TO or
                FileObserver.MOVED_FROM
        }
    }

    private companion object {
        const val TAG = "BoxSharedFolder"

        /** Long enough for a multi-file paste to land, short enough to feel immediate. */
        const val SETTLE_MILLIS = 750L
    }
}
