package dev.localagent.workstation.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Which sessions exist, so a restarted app can list them again.
 *
 * Deliberately only the index — the transcripts live in the per-session logs that `:computer`
 * writes, and those are the source of truth for what happened. This file answers a different
 * question: what should the session list show before any of those logs are opened.
 *
 * Small and rewritten whole. A session list is tens of rows, not thousands, and a torn write here
 * would cost the user their history — so the file is written to a temporary sibling and moved into
 * place, which is atomic on the same filesystem.
 */
internal class SessionStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun load(): List<SessionSummary> {
        if (!file.isFile) return emptyList()
        val json = runCatching { JSONArray(file.readText()) }.getOrElse {
            Log.w(TAG, "session index unreadable; starting with an empty list")
            return emptyList()
        }
        return (0 until json.length()).mapNotNull { index ->
            val row = json.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").ifBlank { return@mapNotNull null }
            SessionSummary(
                id = id,
                harnessId = row.optString("harnessId"),
                title = row.optString("title"),
                status = status(row.optString("status"), row.optString("statusDetail")),
                updatedAt = row.optLong("updatedAt"),
                workingDirectory = row.optString("workingDirectory").ifBlank { "/workspace" },
                preview = row.optString("preview").ifBlank { null },
            )
        }.sortedByDescending { it.updatedAt }
    }

    fun save(sessions: List<SessionSummary>) {
        val json = JSONArray()
        sessions.forEach { session ->
            json.put(
                JSONObject()
                    .put("id", session.id)
                    .put("harnessId", session.harnessId)
                    .put("title", session.title)
                    .put("status", name(session.status))
                    .put("statusDetail", detail(session.status))
                    .put("updatedAt", session.updatedAt)
                    .put("workingDirectory", session.workingDirectory)
                    .put("preview", session.preview ?: ""),
            )
        }
        runCatching {
            val scratch = File(file.parentFile, "$FILE_NAME.tmp")
            scratch.writeText(json.toString())
            if (!scratch.renameTo(file)) {
                file.writeText(json.toString())
                scratch.delete()
            }
        }.onFailure { Log.w(TAG, "could not save the session index", it) }
    }

    /**
     * Anything that was running when the process died is reported as interrupted, not as still
     * running. The truth is re-established the moment the session is attached to — but until then
     * a spinner that never resolves is a worse lie than an honest "stopped".
     */
    private fun status(name: String, detail: String): SessionStatus = when (name) {
        "needs_you" -> SessionStatus.NeedsYou(detail.ifBlank { "Waiting for you" })
        "finished" -> SessionStatus.Finished
        "failed" -> SessionStatus.Failed(detail.ifBlank { "The agent stopped" })
        else -> SessionStatus.Idle
    }

    private fun name(status: SessionStatus): String = when (status) {
        is SessionStatus.Active -> "idle"
        is SessionStatus.NeedsYou -> "needs_you"
        is SessionStatus.Idle -> "idle"
        is SessionStatus.Finished -> "finished"
        is SessionStatus.Failed -> "failed"
    }

    private fun detail(status: SessionStatus): String = when (status) {
        is SessionStatus.NeedsYou -> status.reason
        is SessionStatus.Failed -> status.message
        else -> ""
    }

    private companion object {
        const val TAG = "BoxSessionStore"
        const val FILE_NAME = "sessions.json"
    }
}
