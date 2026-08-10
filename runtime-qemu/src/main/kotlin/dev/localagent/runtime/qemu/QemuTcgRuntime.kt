package dev.localagent.runtime.qemu

import android.content.Context
import android.util.Log
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

    override fun state(): StateFlow<RuntimeState> = runtimeState

    override suspend fun provision(): Result<Unit> = runCatching {
        runtimeState.value = RuntimeState.Provisioning(0.1f)
        storage.ensureDirectories()
        require(storage.hasUefiBootSet() || (storage.kernel.isFile && storage.initrd.isFile && storage.systemOverlay.isFile)) {
            "No verified guest image is installed yet."
        }
        runtimeState.value = RuntimeState.Stopped
    }.onFailure { runtimeState.value = RuntimeState.Failed(RuntimeFailure(it.message ?: "Guest provisioning failed")) }

    override suspend fun start(): Result<Unit> = runCatching {
        storage.ensureDirectories()
        require(storage.hasUefiBootSet() || (storage.kernel.isFile && storage.initrd.isFile && storage.systemOverlay.isFile)) {
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
        runtimeState.value = RuntimeState.Ready
    }.onFailure { runtimeState.value = RuntimeState.Failed(RuntimeFailure(it.message ?: "QEMU start failed")) }

    override suspend fun stop(graceful: Boolean): Result<Unit> {
        NativeQemu.stop()?.let { return Result.failure(IllegalStateException(it)) }
        runtimeState.value = RuntimeState.Stopped
        return Result.success(Unit)
    }

    override suspend fun exec(request: ExecRequest): ExecResult = ExecResult(
        exitCode = 127,
        stdout = "",
        stderr = "Local computer is not installed in this build.",
    )

    override suspend fun createPty(request: PtyRequest): PtySession = unavailable()

    override suspend fun readFile(path: String): ByteArray = byteArrayOf()

    override suspend fun writeFile(path: String, data: ByteArray) = Unit

    override suspend fun listFiles(path: String): List<FileEntry> = emptyList()

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
