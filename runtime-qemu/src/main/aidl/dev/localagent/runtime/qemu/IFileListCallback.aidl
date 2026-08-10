package dev.localagent.runtime.qemu;

/** Parallel arrays keep the transaction free of custom Parcelables. */
oneway interface IFileListCallback {
    void onResult(in String[] paths, in String[] names, in boolean[] directories, in long[] sizes);
    void onError(String message);
}
