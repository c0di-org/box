package dev.localagent.runtime.qemu.shared

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * How the two sides stood at the end of the last pass, on disk.
 *
 * Kept **outside** the shared folder, which is not a filing preference: everything inside that
 * folder is published to every app on the phone and shows up in the user's Files app, and Box's
 * own bookkeeping does not belong in a place the user is invited to tidy. A record deleted by
 * accident is survivable — it makes the next pass treat every file as new, which resolves as
 * disagreements rather than as loss — but it is still not a file anyone should be shown.
 *
 * A record that cannot be read at all is treated as empty rather than as an error, for the same
 * reason: a corrupt bookkeeping file must never be the thing that stops a user's files moving.
 */
class SharedSyncRecords(private val file: File) {

    fun load(): Map<String, SharedSync.Record> = runCatching {
        if (!file.isFile) return emptyMap()
        val root = JSONObject(file.readText())
        if (root.optInt(VERSION) != FORMAT) return emptyMap()
        val files = root.optJSONObject(FILES) ?: return emptyMap()
        files.keys().asSequence().mapNotNull { path ->
            val entry = files.optJSONObject(path) ?: return@mapNotNull null
            path to SharedSync.Record(
                phone = SharedSync.Stamp(entry.optLong("ps"), entry.optLong("pm")),
                box = SharedSync.Stamp(entry.optLong("bs"), entry.optLong("bm")),
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    /** The last pass, for the UI to say what happened. Null before the first one ever runs. */
    fun lastOutcome(): SharedFolderSync.Outcome? = runCatching {
        if (!file.isFile) return null
        val last = JSONObject(file.readText()).optJSONObject(LAST) ?: return null
        SharedFolderSync.Outcome(
            atMillis = last.optLong("at"),
            pushedIn = last.optJSONArray("in").strings(),
            broughtOut = last.optJSONArray("out").strings(),
            kept = last.optJSONArray("kept").strings(),
            trouble = (last.optJSONArray("trouble") ?: JSONArray()).let { array ->
                List(array.length()) { index ->
                    val entry = array.getJSONObject(index)
                    SharedFolderSync.Trouble(entry.optString("path"), entry.optString("reason"))
                }
            },
        )
    }.getOrNull()

    fun save(records: Map<String, SharedSync.Record>, outcome: SharedFolderSync.Outcome) {
        val files = JSONObject()
        records.forEach { (path, record) ->
            files.put(
                path,
                JSONObject()
                    .put("ps", record.phone.size)
                    .put("pm", record.phone.modifiedMillis)
                    .put("bs", record.box.size)
                    .put("bm", record.box.modifiedMillis),
            )
        }
        val root = JSONObject()
            .put(VERSION, FORMAT)
            .put(FILES, files)
            .put(
                LAST,
                JSONObject()
                    .put("at", outcome.atMillis)
                    .put("in", JSONArray(outcome.pushedIn))
                    .put("out", JSONArray(outcome.broughtOut))
                    .put("kept", JSONArray(outcome.kept))
                    .put(
                        "trouble",
                        JSONArray().apply {
                            outcome.trouble.forEach {
                                put(JSONObject().put("path", it.path).put("reason", it.reason))
                            }
                        },
                    ),
            )

        // Through a temporary file: the process that writes this is the one Android kills, and a
        // half-written record read back as empty would turn every file into a disagreement.
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.writing")
        runCatching {
            temporary.writeText(root.toString())
            if (!temporary.renameTo(file)) file.writeText(root.toString())
        }
        temporary.delete()
    }

    private fun JSONArray?.strings(): List<String> =
        this?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty()

    private companion object {
        const val VERSION = "version"
        const val FILES = "files"
        const val LAST = "last"
        const val FORMAT = 1
    }
}
