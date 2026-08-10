package dev.localagent.runtime.qemu;

/** Text preview of one guest file. Binary content is rejected before it reaches this callback. */
oneway interface IFileReadCallback {
    void onResult(String path, String name, String content, boolean truncated);
    void onError(String message);
}
