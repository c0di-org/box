package dev.localagent.workstation

import dev.localagent.runtime.qemu.GuestImageIdentity

/**
 * Whether Box should boot and save the new guest before anyone asks for it.
 *
 * Separated from the ViewModel because every clause here is a judgement rather than a mechanism,
 * and judgements are the part worth being able to read back and test. What it decides costs about
 * a hundred seconds of full-tilt ARM64 emulation on a phone that has just taken an app update, so
 * the bar for saying yes is deliberately a list of reasons rather than one.
 */
internal object SeedDecision {

    /**
     * @param openFaster the "Open faster" setting. A seeded snapshot *is* the storage that setting
     *   is about, so off means there is nothing here worth a boot.
     * @param installed the image on the device, or null if this device has never had one.
     * @param bundled the image this APK carries.
     * @param lastAttempted the image a seed was last started for, from [BoxViewModel.SEEDED_IMAGE_KEY].
     * @param batterySaver whether the user has put the phone in battery saver.
     * @param batteryPercent 0 or less when the device declined to say.
     */
    fun shouldSeed(
        openFaster: Boolean,
        installed: GuestImageIdentity?,
        bundled: GuestImageIdentity?,
        lastAttempted: String?,
        batterySaver: Boolean,
        batteryPercent: Int,
        minimumBatteryPercent: Int,
    ): Boolean {
        // A seeded snapshot is ~1.27 GB on the phone this was measured on, and that is the whole
        // of what this setting is about. Off means the user has already answered this question.
        if (!openFaster) return false
        if (bundled == null) return false

        // An update, not a first install. A device with no image has no box the user has ever
        // opened and no habit to serve, and its first open runs the setup path in front of them
        // anyway — spending a boot on a guess about somebody who has not opened Box once is the
        // version of this that deserves the battery objection.
        if (installed == null) return false
        if (installed == bundled) return false

        // Once per image. A seed that works makes its own condition false, because the image it
        // installed becomes the installed one — so this only bites when one fails, and there it is
        // what stops a guest that cannot boot from costing a boot on every launch.
        if (lastAttempted == bundled.toString()) return false

        // The two cases where the user has already said something about power that this should not
        // talk over. Deliberately not a charging check: "charging and idle" sounds careful and is
        // not, because on a phone that is rarely plugged in it means the seeding never runs and
        // the cold path stays the normal one — the feature would be paid for and never delivered.
        if (batterySaver) return false
        // A device that will not answer is not a device that said no.
        if (batteryPercent in 1 until minimumBatteryPercent) return false

        return true
    }
}
