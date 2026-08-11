package dev.localagent.runtime.qemu

/**
 * The complete headless stock-runtime launch contract. QEMU receives only private app paths;
 * guest management uses Unix sockets rather than LAN TCP listeners.
 */
object QemuCommand {
    fun boot(storage: RuntimeStorage): List<String> =
        if (storage.hasHeadlessBootSet()) headless(storage) else uefi(storage)

    /** Temporary compatibility boot path used by the device proof image. Production images use
     * the direct-kernel headless path below, which is smaller and boots faster. */
    private fun uefi(storage: RuntimeStorage): List<String> = listOf(
        "qemu-system-aarch64",
        "-machine", "virt,accel=tcg,highmem=off",
        "-cpu", "cortex-a53",
        "-smp", "2",
        "-m", "1024",
        "-nographic",
        "-drive", "if=pflash,format=raw,readonly=on,file=${storage.uefiCode.absolutePath}",
        "-drive", "if=pflash,format=raw,file=${storage.uefiVars.absolutePath}",
        "-drive", "if=none,id=system,format=qcow2,file=${storage.systemDisk.absolutePath}",
        "-device", "virtio-blk-pci,drive=system,romfile=",
        "-netdev", "user,id=net0",
        "-device", "virtio-net-pci,netdev=net0,romfile=",
        "-device", "virtio-serial-pci",
        "-chardev", "socket,id=agentd,path=${storage.agentSocket.absolutePath},server=on,wait=off",
        "-device", "virtserialport,chardev=agentd,name=dev.localagent.agentd",
        "-qmp", "unix:${storage.qmpSocket.absolutePath},server=on,wait=off",
        "-no-reboot",
    )

    private fun headless(storage: RuntimeStorage): List<String> = listOf(
        "qemu-system-aarch64",
        "-machine", "virt,accel=tcg,highmem=off",
        "-cpu", "cortex-a53",
        "-smp", "2",
        // 1024 was arbitrary, and a desktop does not fit in it. 2048 is bounded by two real
        // ceilings rather than by taste: `highmem=off` keeps the whole address map under 4 GB, so
        // the board itself cannot go much past 3 GB, and the phone reports ~2.7 GB *available* out
        // of 11 GB — the rest is Android's. Guest RAM is one anonymous mapping in `:computer`, and
        // the fatter that process is, the sooner the low-memory killer picks it.
        "-m", "2048",
        // A screen. `-vnc` is itself the display backend, which is why `-nographic` is gone: with
        // neither, QEMU picks a default, and this build is linked against SDL2 — it would try to
        // open a window from inside a foreground service. The serial console is routed explicitly
        // below, which is the other thing `-nographic` used to do.
        //
        // The socket lives in app-private storage, so the filesystem is the whole access control:
        // only this UID can open it, there is no port, and nothing is reachable from the network.
        // That is the same rule the agentd channel follows.
        // `-display none` is not redundant next to `-vnc`. This build is linked against SDL2, and
        // with `-nographic` gone QEMU is free to resolve the default display to SDL and try to open
        // a window from a foreground service with no Activity — which dies without reaching stderr,
        // so it presents as the VM silently never starting. VNC is a separate option group and is
        // set up regardless of the display type.
        // Where QEMU looks for its own data files. Without this the VNC server cannot load a
        // keymap and exits — see RuntimeStorage.qemuData.
        "-L", storage.qemuData.absolutePath,
        "-display", "none",
        "-device", "virtio-gpu-pci,xres=1280,yres=800,romfile=",
        "-vnc", "unix:${storage.vncSocket.absolutePath}",
        "-kernel", storage.kernel.absolutePath,
        "-initrd", storage.initrd.absolutePath,
        // tty0 puts the kernel's own console on that screen, so a boot is watchable and a guest
        // that never reaches userspace still shows why. ttyAMA0 stays last and so stays
        // /dev/console, which is what the existing serial logging reads.
        "-append", "root=/dev/vda rw console=tty0 console=ttyAMA0",
        "-drive", "if=none,id=system,format=qcow2,file=${storage.systemDisk.absolutePath}",
        "-device", "virtio-blk-pci,drive=system,romfile=",
        "-drive", "if=none,id=workspace,format=qcow2,file=${storage.workspace.absolutePath}",
        "-device", "virtio-blk-pci,drive=workspace,romfile=",
        "-netdev", "user,id=net0",
        "-device", "virtio-net-pci,netdev=net0,romfile=",
        "-device", "virtio-serial-pci",
        "-chardev", "socket,id=serial,path=${storage.serialSocket.absolutePath},server=on,wait=off",
        "-serial", "chardev:serial",
        "-chardev", "socket,id=agentd,path=${storage.agentSocket.absolutePath},server=on,wait=off",
        "-device", "virtserialport,chardev=agentd,name=dev.localagent.agentd",
        "-qmp", "unix:${storage.qmpSocket.absolutePath},server=on,wait=off",
        "-no-reboot",
    )
}
