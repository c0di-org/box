package dev.localagent.runtime.qemu

/**
 * The complete headless stock-runtime launch contract. QEMU receives only private app paths;
 * guest management uses Unix sockets rather than LAN TCP listeners.
 */
object QemuCommand {
    /**
     * The image's four files are resolved by [RuntimeStorage] from the manifest that describes
     * them, rather than read from fixed fields here. Nothing below knows what an image is called
     * or where it is keyed — it is handed paths.
     */
    fun boot(storage: RuntimeStorage, resumeFrom: String? = null): List<String> {
        val command = storage.headlessBootFiles()?.let { headless(storage, it) } ?: uefi(storage)
        // `-loadvm` is what makes reopening a box cheap: QEMU builds the same machine and then,
        // instead of running a bootloader, reads the guest's memory back out of the snapshot in
        // its qcow2. The device set either side of this has to be identical, which is why it is
        // appended to an ordinary launch rather than composed as a separate one.
        return resumeFrom?.let { command + listOf("-loadvm", it) } ?: command
    }

    /**
     * A fingerprint of the machine this build constructs, so a saved guest is only handed back to
     * one shaped like the machine it left.
     *
     * `-loadvm` restores memory into a machine that must match device for device, and QEMU does not
     * decline politely: the load fails *after* the disks have been rolled back toward the snapshot.
     * [QemuTcgRuntime.pendingResume]'s image check catches a new Debian but not a new device on the
     * same Debian — and giving the VNC server a tablet and keyboard to deliver into is exactly
     * that. Without this, every box paused by the previous build would have failed to reopen.
     *
     * Taken from the launch command rather than a constant somebody must remember to bump, because
     * the failure it prevents is silent. Deliberately over-sensitive — an argument that could not
     * really invalidate a snapshot still changes the fingerprint — since being wrong that way costs
     * one cold boot, and booting cold is always safe.
     */
    fun machine(storage: RuntimeStorage): String {
        val command = boot(storage, resumeFrom = null).joinToString(" ")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(command.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }

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
        "-drive", "if=none,id=system,format=qcow2,file=${storage.uefiSystemDisk.absolutePath}",
        "-device", "virtio-blk-pci,drive=system,romfile=",
        "-netdev", "user,id=net0",
        "-device", "virtio-net-pci,netdev=net0,romfile=",
        "-device", "virtio-serial-pci",
        "-chardev", "socket,id=agentd,path=${storage.agentSocket.absolutePath},server=on,wait=off",
        "-device", "virtserialport,chardev=agentd,name=dev.localagent.agentd",
        "-qmp", "unix:${storage.qmpSocket.absolutePath},server=on,wait=off",
        "-no-reboot",
    )

    private fun headless(storage: RuntimeStorage, image: GuestImageFiles): List<String> = listOf(
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
        // Where QEMU looks for its own data files. Without this the VNC server cannot load a
        // keymap and exits — see RuntimeStorage.qemuData.
        "-L", storage.qemuData.absolutePath,
        // Not redundant beside `-vnc`, and not optional. `-nographic` is gone because `-vnc` is
        // itself the display backend; with neither, this SDL2-linked build resolves the default
        // display to SDL and tries to open a window from a foreground service with no Activity,
        // which dies without reaching stderr and presents as the VM silently never starting.
        // (The serial console below is the other thing `-nographic` used to do.)
        "-display", "none",
        "-device", "virtio-gpu-pci,xres=1280,yres=800,romfile=",
        // Something for the VNC server to deliver input *to*. `-M virt` registers no input device
        // of its own — no PS/2 outside x86, no USB unless asked for — so without these QEMU routes
        // RFB pointer and key events into an input subsystem holding nothing. Nothing errors and
        // nothing logs: every click is dropped, which presents as "Take over" switching to
        // "You're driving" over a desktop that then ignores the user completely.
        //
        // Tablet rather than mouse because RFB is absolute. A virtio-mouse takes relative deltas,
        // which would put an acceleration model down here instead of in GuestPointer — the one
        // place that knows how big a fingertip is and how much of the guest a pane is showing.
        //
        // romfile= as on every PCI device here: there is no QEMU data directory in an APK, so a
        // device that tries to load an option ROM cannot find one.
        "-device", "virtio-tablet-pci,romfile=",
        "-device", "virtio-keyboard-pci,romfile=",
        // App-private socket, so the filesystem is the whole access control: only this UID can
        // open it, no port, nothing on the network. Same rule the agentd channel follows.
        "-vnc", "unix:${storage.vncSocket.absolutePath}",
        "-kernel", image.kernel.absolutePath,
        "-initrd", image.initrd.absolutePath,
        // tty0 puts the kernel's own console on that screen, so a boot is watchable and a guest
        // that never reaches userspace still shows why. ttyAMA0 stays last and so stays
        // /dev/console, which is what the existing serial logging reads.
        "-append", "root=/dev/vda rw console=tty0 console=ttyAMA0",
        "-drive", "if=none,id=system,format=qcow2,file=${image.system.absolutePath}",
        "-device", "virtio-blk-pci,drive=system,romfile=",
        "-drive", "if=none,id=workspace,format=qcow2,file=${image.workspace.absolutePath}",
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
