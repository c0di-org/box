package dev.localagent.runtime.qemu;

oneway interface IWriteCallback {
    void onResult(long bytesWritten);
    void onError(String message);
}
