package dev.localagent.runtime.qemu

import android.content.Context
import android.util.Log
import android.util.Base64
import dev.localagent.runtime.api.ComputerRuntime
import dev.localagent.runtime.api.ExecRequest
import dev.localagent.runtime.api.ExecResult
import dev.localagent.runtime.api.FileEntry
import dev.localagent.runtime.api.DesktopSession
import dev.localagent.runtime.api.PortForward
import dev.localagent.runtime.api.PortForwardRequest
import dev.localagent.runtime.api.PtyRequest
import dev.localagent.runtime.api.PtySession
import dev.localagent.runtime.api.RuntimeState
import dev.localagent.runtime.api.RuntimeFailure
import dev.localagent.runtime.api.SnapshotId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stock-device runtime. QEMU stays in the dedicated service process and no guest operation is
 * ever substituted with Android host-shell execution.
 */
class QemuTcgRuntime(context: Context) : ComputerRuntime {
    private companion object {
        const val TAG = "LocalAgentRuntime"
    }
    private val runtimeState = MutableStateFlow<RuntimeState>(RuntimeState.NotProvisioned)
    private val storage = RuntimeStorage(context.applicationContext)
    private val agentd = AgentdClient(storage.agentSocket)

    override fun state(): StateFlow<RuntimeState> = runtimeState

    override suspend fun provision(): Result<Unit> = runCatching {
        runtimeState.value = RuntimeState.Provisioning(0.1f)
        storage.ensureDirectories()
        require(storage.hasUefiBootSet() || (storage.kernel.isFile && storage.initrd.isFile && storage.baseSystem.isFile)) {
            "No verified guest image is installed yet."
        }
        runtimeState.value = RuntimeState.Stopped
    }.onFailure { runtimeState.value = RuntimeState.Failed(RuntimeFailure(it.message ?: "Guest provisioning failed")) }

    override suspend fun start(): Result<Unit> = runCatching {
        storage.ensureDirectories()
        require(storage.hasUefiBootSet() || (storage.kernel.isFile && storage.initrd.isFile && storage.baseSystem.isFile)) {
            "No verified guest image is installed yet."
        }
        runtimeState.value = RuntimeState.Starting
        NativeQemu.start(QemuCommand.boot(storage).toTypedArray(), storage.privateRoot.absolutePath)?.let { error(it) }
        runtimeState.value = RuntimeState.Connecting
        val qmpStatus = withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            repeat(80) {
                try {
                    return@withContext QmpClient(storage.qmpSocket).queryStatus()
                } catch (error: Throwable) {
                    lastError = error
                    delay(250)
                }
            }
            throw IllegalStateException("QEMU did not become reachable through QMP", lastError)
        }
        check(NativeQemu.isRunning() && Regex("\\\"running\\\"\\s*:\\s*true").containsMatchIn(qmpStatus)) {
            "QEMU is not running: $qmpStatus"
        }
        Log.i(TAG, "QMP confirmed running guest")
        SerialConsoleLogger.start(storage.serialSocket)
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            repeat(120) { attempt ->
                try {
                    val health = agentd.call("health")
                    check(health.optBoolean("ready")) { "agentd is not ready" }
                    return@withContext
                } catch (error: Throwable) {
                    lastError = error
                    if (attempt == 0 || (attempt + 1) % 20 == 0) {
                        Log.i(TAG, "Waiting for guest agentd (attempt ${attempt + 1})", error)
                    }
                    delay(1_000)
                }
            }
            throw IllegalStateException("Guest agentd did not become ready", lastError)
        }
        Log.i(TAG, "Guest agentd confirmed ready")
        runtimeState.value = RuntimeState.Ready
    }.onFailure { runtimeState.value = RuntimeState.Failed(RuntimeFailure(it.message ?: "QEMU start failed")) }

    override suspend fun stop(graceful: Boolean): Result<Unit> {
        NativeQemu.stop()?.let { return Result.failure(IllegalStateException(it)) }
        agentd.close()
        runtimeState.value = RuntimeState.Stopped
        return Result.success(Unit)
    }

    override suspend fun exec(request: ExecRequest): ExecResult {
        val command = JSONArray().apply { request.command.forEach(::put) }
        val result = agentd.call("exec", JSONObject().put("command", command).put("cwd", request.workingDirectory))
        return ExecResult(result.getInt("exitCode"), result.getString("stdout"), result.getString("stderr"))
    }

    override suspend fun createPty(request: PtyRequest): PtySession = unavailable()

    override suspend fun readFile(path: String): ByteArray = Base64.decode(
        agentd.call("read_file", JSONObject().put("path", path)).getString("dataBase64"),
        Base64.DEFAULT,
    )

    override suspend fun writeFile(path: String, data: ByteArray) {
        agentd.call("write_file", JSONObject()
            .put("path", path)
            .put("dataBase64", Base64.encodeToString(data, Base64.NO_WRAP)))
    }

    override suspend fun listFiles(path: String): List<FileEntry> {
        val entries = agentd.call("list_files", JSONObject().put("path", path)).getJSONArray("items")
        return List(entries.length()) { index ->
            entries.getJSONObject(index).let {
                FileEntry(it.getString("path"), it.getString("name"), it.getBoolean("directory"), it.getLong("size"))
            }
        }
    }

    override suspend fun desktopStart(): DesktopSession = unavailable()
    override suspend fun desktopStop() = Unit

    override suspend fun snapshot(): SnapshotId = unavailable()
    override suspend fun restore(snapshot: SnapshotId) = Unit
    override suspend fun forwardPort(request: PortForwardRequest): PortForward = unavailable()
    override suspend fun suspendRuntime() = Unit
    override suspend fun resumeRuntime() = Unit

    private fun <T> unavailable(): T = throw UnsupportedOperationException(
        "The guest agentd transport is not connected yet.",
    )
}
