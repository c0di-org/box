package dev.localagent.runtime.qemu;

import dev.localagent.runtime.qemu.IExecCallback;
import dev.localagent.runtime.qemu.IFileListCallback;
import dev.localagent.runtime.qemu.IFileReadCallback;

/**
 * Guest operations exposed to the UI process. The VM itself stays inside `:computer`; only these
 * results cross the process boundary.
 */
interface IRuntimeControl {
    oneway void exec(in String[] command, String workingDirectory, int timeoutSeconds, IExecCallback callback);
    oneway void listFiles(String path, IFileListCallback callback);
    oneway void readFile(String path, IFileReadCallback callback);
}
