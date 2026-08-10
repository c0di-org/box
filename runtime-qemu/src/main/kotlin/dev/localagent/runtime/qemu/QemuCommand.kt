package dev.localagent.runtime.qemu

/**
 * The complete headless stock-runtime launch contract. QEMU receives only private app paths;
 * guest management uses Unix sockets rather than LAN TCP listeners.
 */
object QemuCommand {
    fun boot(storage: RuntimeStorage): List<String> =
        if (storage.kernel.isFile && storage.initrd.isFile && storage.baseSystem.isFile) headless(storage) else uefi(storage)

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
        "-drive", "if=none,id=system,format=qcow2,file=${storage.baseSystem.absolutePath}",
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
        "-m", "1024",
        "-nographic",
        "-kernel", storage.kernel.absolutePath,
        "-initrd", storage.initrd.absolutePath,
        "-append", "root=/dev/vda rw console=ttyAMA0",
        "-drive", "if=none,id=system,format=qcow2,file=${storage.baseSystem.absolutePath}",
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
