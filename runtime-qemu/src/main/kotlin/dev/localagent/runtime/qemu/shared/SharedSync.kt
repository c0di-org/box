package dev.localagent.runtime.qemu.shared

/**
 * Deciding what to copy where, and nothing else.
 *
 * No Android types, no I/O, no coroutines — the same reason `Rfb.kt` has none. This is the part of
 * file sharing that can be wrong in ways nobody notices for a week, so it has to be provable on a
 * laptop rather than by copying a file into a phone and squinting three minutes later.
 * [SharedFolderSync] does what the plan says and no thinking of its own.
 *
 * **The rule: the phone's copy is the source of truth and the box gets a copy.** The folder is
 * ordinary Android storage reachable by any app at any time, including while the box is off, and
 * `/workspace/shared` is a projection of it.
 *
 * No continuous two-way sync, on purpose. Two writers and a merge policy is how a file quietly
 * becomes the wrong version; instead there are a handful of cases, each with one answer, and
 * **nothing is ever deleted to resolve a disagreement**. The worst this produces is a redundant
 * file beside the real one, which a person can see and fix. The worst the clever version produces
 * is work that is gone.
 *
 * **Stamps, not hashes.** A change is size plus mtime against what they were at the end of the
 * last sync — rsync's quick check — and each side is compared only against *its own* recorded
 * stamp, so the two clocks never have to agree. Hashing both trees every pass is cheap on the
 * phone and, in an emulated guest reading a qcow2 through a 64 KiB-framed socket, would turn a
 * boot-time sync into a visible stall. The quick check misses exactly one thing, an edit
 * preserving both size and mtime, and the cost is that the file syncs later rather than never.
 */
object SharedSync {

    /**
     * What is kept beside a file whose two versions disagree.
     *
     * It reads as a sentence in a file listing — `notes.md` next to `notes.md.from-box` — which is
     * the only place this ever needs to be understood. A suffix rather than a prefix so the two
     * sort together, and so the original extension is still on the name that the user opens.
     */
    const val BOX_COPY_SUFFIX = ".from-box"

    /** Size and modification time, which together are how a change is noticed. See the class doc. */
    data class Stamp(val size: Long, val modifiedMillis: Long)

    /**
     * One path, as both sides last agreed on it.
     *
     * The pair is the point. Holding one stamp would only answer "did *something* change"; the
     * plan needs to know *which side*, because that is the difference between a push, a pull and a
     * disagreement.
     */
    data class Record(val phone: Stamp, val box: Stamp)

    /**
     * One thing to do to one file. Paths are relative to the shared folder on both sides, so the
     * same string names the file on the phone and in the guest.
     */
    sealed interface SyncAction {
        val path: String

        /** Copy the phone's bytes into the box. */
        data class Push(override val path: String) : SyncAction

        /** Copy the box's bytes out to the phone. */
        data class Pull(override val path: String) : SyncAction

        /**
         * Both sides changed. The phone wins, and the box's version is brought out beside it as
         * [boxCopy] rather than discarded.
         *
         * The copy lands on the *phone* — not in the guest — because the person who has to notice
         * a disagreement is the user, and their Files app is where they will be looking. It syncs
         * into the box on the next pass like any other file the user put there.
         */
        data class Resolve(override val path: String, val boxCopy: String) : SyncAction

        /**
         * Forget the record. Touches no bytes on either side, and is only ever reached once the
         * file is gone from *both* sides — a record for a file that exists nowhere is the one
         * kind of bookkeeping that can be thrown away safely.
         */
        data class Untrack(override val path: String) : SyncAction
    }

    /**
     * Everything that should happen this pass.
     *
     * [phone] and [box] are the two trees as they are right now; [known] is how they stood at the
     * end of the last pass. Files only — a directory with nothing in it carries no information,
     * and every directory that does hold something is created implicitly by writing into it.
     */
    fun plan(
        phone: Map<String, Stamp>,
        box: Map<String, Stamp>,
        known: Map<String, Record>,
    ): List<SyncAction> {
        val taken = phone.keys + box.keys
        return (phone.keys + box.keys + known.keys).sorted().mapNotNull { path ->
            val onPhone = phone[path]
            val inBox = box[path]
            val record = known[path]
            when {
                // Present on the phone and not in the box. Either the user just added it, or the
                // box lost it — and the answer is the same both times, because the phone is the
                // source of truth and a file missing from the copy is a copy that is behind.
                onPhone != null && inBox == null -> SyncAction.Push(path)

                // Only in the box, and never seen before: the agent made it. This is the whole
                // of the return direction — an agent leaves a file in `/workspace/shared` and it
                // appears on the phone.
                onPhone == null && inBox != null && record == null -> SyncAction.Pull(path)

                // Only in the box, and seen before: the user deleted it on the phone. The box
                // keeps its copy, because deleting there would need a method agentd deliberately
                // does not have — but nothing is copied out, because the file the user threw away
                // must not walk back onto their phone.
                //
                // The record is deliberately *kept* rather than dropped, and this is the subtle
                // part: dropping it would make the very next pass see a box-only file it has
                // never heard of, which is the case directly above. The deletion would undo
                // itself, once per sync, forever.
                onPhone == null && inBox != null -> null

                // Gone from both sides; only the record still believes in it.
                onPhone == null -> SyncAction.Untrack(path)

                else -> {
                    // A path with no record has independently appeared on both sides. That is a
                    // disagreement by the same argument as any other: two versions exist and
                    // nothing here knows they match.
                    val phoneMoved = record == null || record.phone != onPhone
                    val boxMoved = record == null || record.box != inBox
                    when {
                        phoneMoved && boxMoved -> SyncAction.Resolve(path, boxCopyName(path, taken))
                        phoneMoved -> SyncAction.Push(path)
                        boxMoved -> SyncAction.Pull(path)
                        else -> null
                    }
                }
            }
        }
    }

    /**
     * A free name for the box's version of [path].
     *
     * The plain `.from-box` name is claimed the first time; a second disagreement about the same
     * file numbers itself rather than overwriting the first one. Overwriting would be a deletion
     * dressed up as a copy, which is the one thing this whole class exists not to do.
     */
    private fun boxCopyName(path: String, taken: Set<String>): String {
        val preferred = path + BOX_COPY_SUFFIX
        if (preferred !in taken) return preferred
        var suffix = 2
        while ("$preferred.$suffix" in taken) suffix++
        return "$preferred.$suffix"
    }
}
