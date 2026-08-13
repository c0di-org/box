package dev.localagent.runtime.qemu;

oneway interface IPortForwardCallback {
    /** A loopback URL on the phone that reaches the guest port. */
    void onForwarded(int guestPort, String url);
    void onError(String message);
}
