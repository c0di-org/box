package dev.localagent.runtime.qemu;

/** Result of one guest command. Streams are already truncated to a Binder-safe size. */
oneway interface IExecCallback {
    void onResult(int exitCode, String stdout, String stderr, boolean truncated);
    void onError(String message);
}
