package dev.localagent.runtime.qemu

/**
 * Whether a payload gets written, for one role, with no filesystem in sight.
 *
 * This is four lines of code and it is the point of the whole manifest. The rule it replaces —
 * "install the disks only when absent" — was correct about the thing it was protecting and wrong
 * about everything else: it kept the user's Linux machine safe across app updates, and in the same
 * breath made a rebuilt guest image a no-op on any device that had ever provisioned one. There was
 * no way to tell those two cases apart, because "the file is already there" was all the system
 * knew. An identity makes them different questions, and this answers both.
 */
object GuestImageInstall {

    enum class Decision {
        /** Write the payload from the APK, replacing whatever is there. */
        INSTALL,

        /** Leave the file alone. */
        KEEP,
    }

    /**
     * @param installed the identity recorded by the last completed install, or null if this device
     *   has never finished installing this image. Null is also what a device migrated from the old
     *   flat layout looks like, which is right: its files are of unknown provenance.
     * @param present whether the destination file exists.
     * @param intact for a [GuestImageRole.rewritable] payload, whether its bytes still match the
     *   manifest. Ignored for the disks, whose bytes the guest changes on its first boot — a
     *   caller has nothing useful to pass there and should leave the default.
     * @param replaceImage forces every image-owned payload, which is what "give me a clean system
     *   disk" means. It cannot reach the workspace; see below.
     */
    fun decide(
        role: GuestImageRole,
        bundled: GuestImageIdentity,
        installed: GuestImageIdentity?,
        present: Boolean,
        intact: Boolean = true,
        replaceImage: Boolean = false,
    ): Decision {
        // The user's disk, and the one invariant that outranks everything else here. Not even an
        // explicit reinstall may touch it: a request to re-provision is a request about the
        // *image*, and someone asking for a fresh system disk has never meant "and delete my work".
        // Absent is the only case that writes, and then it is creating a machine, not replacing one.
        if (role.owner == GuestImageOwner.USER) {
            return if (present) Decision.KEEP else Decision.INSTALL
        }

        if (replaceImage || !present) return Decision.INSTALL

        // The question the old code could not ask. A device holding box-minimal-claude@a1b2 and an
        // APK carrying box-minimal-claude@c3d4 has an out-of-date machine, not a finished one.
        if (installed != bundled) return Decision.INSTALL

        // Same image, already here. Only the files QEMU never writes can be meaningfully checked,
        // and repairing a truncated kernel is free next to the alternative of a VM that will not
        // boot for a reason nothing reports.
        if (role.rewritable && !intact) return Decision.INSTALL

        return Decision.KEEP
    }

    /**
     * True when this device is running exactly the bundled image and needs nothing written.
     *
     * The workspace counts as present-or-not rather than as part of the identity: a box whose
     * workspace disk has gone missing still needs one made, even though its image is current.
     */
    fun isUpToDate(
        bundled: GuestImageIdentity,
        installed: GuestImageIdentity?,
        present: Set<GuestImageRole>,
    ): Boolean = installed == bundled && present.containsAll(GuestImageRole.entries)
}
