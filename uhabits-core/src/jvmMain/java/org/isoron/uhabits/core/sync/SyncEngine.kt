/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.sync

import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.core.models.sqlite.records.HabitRecord

/**
 * Reconciles the local habit database with a folder shared by Syncthing.
 *
 * The merge is three-way, against a locally cached copy of what the folder said
 * at the end of the previous sync (the *base*). That base is what makes an edit
 * distinguishable from a non-edit: without it, "local and folder disagree" has
 * no answer, because there is nothing to say which side moved. It is also what
 * makes deletion work -- a habit missing from the folder but present in the
 * base was deleted by the other device, whereas one missing from both is simply
 * new here and has never been published.
 *
 * Every write is conditional on the content having actually changed, so a sync
 * with nothing to do writes nothing at all and does not wake Syncthing.
 */
class SyncEngine(
    private val habitList: HabitList,
    private val modelFactory: ModelFactory
) {

    /**
     * @param folderId identifies which shared folder [base] was built from.
     * When it does not match what the base was last built against, the base is
     * discarded rather than trusted -- see [checkFolderIdentity].
     */
    fun sync(remote: SyncFolder, base: SyncFolder, folderId: String? = null): SyncReport {
        val report = SyncReport()

        checkFolderIdentity(base, folderId, report)
        healConflicts(remote, report)

        val local = readLocal()
        val remoteSide = readFolder(remote)
        val baseSide = readFolder(base)

        // Safety rail. An empty folder next to a non-empty base means, read
        // literally, "every habit was deleted elsewhere" -- and the literal
        // reading is almost never the true one. Far likelier: the folder was
        // repointed, Syncthing has not populated it yet, external storage was
        // not mounted, or the folder was cleared by hand. Any of those would
        // otherwise cost the owner every habit they have. Refusing costs one
        // sync, so refuse and say why.
        if (remoteSide.isEmpty() && baseSide.isNotEmpty()) {
            report.abortedEmptyFolder = true
            return report
        }

        val uuids = (local.keys + remoteSide.keys + baseSide.keys).toSortedSet()
        for (uuid in uuids) {
            reconcile(uuid, local[uuid], remoteSide[uuid], baseSide[uuid], remote, base, report)
        }

        if (report.createdLocally.isNotEmpty() || report.deletedLocally.isNotEmpty()) {
            habitList.resort()
        }
        return report
    }

    /**
     * Discards the base when it belongs to a different shared folder.
     *
     * The base is only meaningful relative to one folder: it records what
     * *that* folder last said. Point the app at a second folder and the old
     * base describes a past the new folder never had, so every habit in it
     * reads as deleted. Rather than trusting the caller to remember, the base
     * carries the identity of the folder it was built from.
     */
    private fun checkFolderIdentity(base: SyncFolder, folderId: String?, report: SyncReport) {
        if (folderId == null) return
        val recorded = base.read(META_DIR, FOLDER_FILE)
        if (recorded == folderId) return
        for (dir in base.listHabitDirs()) base.deleteDir(dir)
        base.write(META_DIR, FOLDER_FILE, folderId)
        if (recorded != null) report.baseReset = true
    }

    // ---------------------------------------------------------------- reading

    private fun readLocal(): Map<String, HabitSnapshot> {
        val result = mutableMapOf<String, HabitSnapshot>()
        for (habit in habitList) {
            val uuid = habit.uuid ?: continue
            val record = HabitRecord()
            record.copyFrom(habit)
            val syncHabit = record.toSyncHabit()
            val entries = habit.originalEntries.getKnown().associate { entry ->
                val date = formatSyncDate(entry.timestamp.unixTime)
                date to SyncEntry(date, entry.value, entry.notes)
            }
            result[uuid] = HabitSnapshot(dirName(syncHabit), syncHabit, entries)
        }
        return result
    }

    private fun readFolder(folder: SyncFolder): Map<String, HabitSnapshot> {
        val result = mutableMapOf<String, HabitSnapshot>()
        for (dir in folder.listHabitDirs()) {
            val habitText = folder.read(dir, HABIT_FILE) ?: continue
            val habit = runCatching { decodeHabit(habitText) }.getOrNull() ?: continue
            val entries = folder.read(dir, ENTRIES_FILE)
                ?.let { text -> runCatching { decodeEntries(text).entries.associateBy { it.date } }.getOrNull() }
                ?: emptyMap()
            result[habit.uuid] = HabitSnapshot(dir, habit, entries)
        }
        return result
    }

    // -------------------------------------------------------------- conflicts

    /**
     * Folds every `*.sync-conflict-*` copy back into the file it is a copy of.
     *
     * Two copies of one habit's history are all but identical, so the union
     * recovers everything except the individual days the two devices disagree
     * about -- and for those the surviving value is chosen by a fixed
     * precedence and reported, rather than being picked silently.
     */
    private fun healConflicts(remote: SyncFolder, report: SyncReport) {
        for (dir in remote.listHabitDirs()) {
            val conflicts = remote.listFiles(dir).filter { isConflictCopy(it) }
            if (conflicts.isEmpty()) continue

            val name = remote.read(dir, HABIT_FILE)
                ?.let { runCatching { decodeHabit(it).name }.getOrNull() }
                ?: dir

            for (file in conflicts) {
                if (conflictBaseName(file) == ENTRIES_FILE) {
                    val mine = remote.read(dir, ENTRIES_FILE)
                        ?.let { runCatching { decodeEntries(it) }.getOrNull() }
                    val theirs = remote.read(dir, file)
                        ?.let { runCatching { decodeEntries(it) }.getOrNull() }
                    if (mine != null && theirs != null) {
                        val merged = unionEntries(name, mine, theirs, report)
                        remote.write(dir, ENTRIES_FILE, encodeEntries(merged))
                    }
                }
                // A conflicting habit.json keeps whichever copy Syncthing left
                // in place. Habit metadata is small, rarely edited and trivially
                // re-entered; unioning two of them field by field would invent a
                // habit that neither device ever had.
                remote.deleteFile(dir, file)
                report.conflictFilesHealed++
            }
        }
    }

    private fun unionEntries(
        habitName: String,
        mine: SyncEntries,
        theirs: SyncEntries,
        report: SyncReport
    ): SyncEntries {
        val merged = mine.entries.associateBy { it.date }.toMutableMap()
        for (entry in theirs.entries) {
            val existing = merged[entry.date]
            if (existing == null) {
                merged[entry.date] = entry
            } else if (existing != entry) {
                val keepExisting = precedence(existing.value) >= precedence(entry.value)
                val kept = if (keepExisting) existing else entry
                val dropped = if (keepExisting) entry else existing
                merged[entry.date] = kept
                report.conflicts += SyncConflict(habitName, entry.date, kept.value, dropped.value)
            }
        }
        return SyncEntries(mine.uuid, merged.values.sortedBy { it.date })
    }

    /**
     * Which value survives when two devices set the same day differently.
     *
     * Ordered so a recorded observation always beats the absence of one: an
     * explicit yes beats a skip, a skip beats an explicit no, and anything at
     * all beats "no data". A numerical value outranks every flag, because a
     * number is only ever there because someone typed it.
     */
    private fun precedence(value: Int): Int = when (value) {
        Entry.UNKNOWN -> 0
        Entry.NO -> 1
        Entry.YES_AUTO -> 2
        Entry.SKIP -> 3
        Entry.YES_MANUAL -> 4
        else -> 5
    }

    // -------------------------------------------------------------- reconcile

    private fun reconcile(
        uuid: String,
        local: HabitSnapshot?,
        remote: HabitSnapshot?,
        base: HabitSnapshot?,
        remoteFolder: SyncFolder,
        baseFolder: SyncFolder,
        report: SyncReport
    ) {
        when {
            local != null && remote != null -> {
                val merged = mergeHabit(local, remote, base, report)
                applyLocally(uuid, local, merged, report)
                publish(remoteFolder, baseFolder, local.dir, remote.dir, merged, report)
            }

            // Here but not in the folder. If the base knew it, the other device
            // deleted it; otherwise it is simply new here and never published.
            local != null && remote == null -> {
                if (base != null) {
                    deleteLocally(uuid, report)
                    baseFolder.deleteDir(base.dir)
                } else {
                    publish(remoteFolder, baseFolder, local.dir, null, local, report)
                }
            }

            // In the folder but not here. If the base knew it, it was deleted
            // here; otherwise the other device has just created it.
            local == null && remote != null -> {
                if (base != null) {
                    remoteFolder.deleteDir(remote.dir)
                    baseFolder.deleteDir(base.dir)
                    report.deletedRemotely += remote.habit.name
                } else {
                    createLocally(remote, report)
                    writeBase(baseFolder, remote.dir, remote)
                }
            }

            else -> base?.let { baseFolder.deleteDir(it.dir) }
        }
    }

    private fun mergeHabit(
        local: HabitSnapshot,
        remote: HabitSnapshot,
        base: HabitSnapshot?,
        report: SyncReport
    ): HabitSnapshot {
        val l = local.habit
        val r = remote.habit
        val b = base?.habit

        // Merged field by field rather than whole-object, so that reordering
        // habits on one device -- which rewrites `position` on many of them at
        // once -- does not collide with an unrelated rename on the other.
        val habit = SyncHabit(
            uuid = l.uuid,
            name = pick(l.name, r.name, b?.name),
            description = pick(l.description, r.description, b?.description),
            question = pick(l.question, r.question, b?.question),
            frequency = pick(l.frequency, r.frequency, b?.frequency),
            color = pick(l.color, r.color, b?.color),
            position = pick(l.position, r.position, b?.position),
            archived = pick(l.archived, r.archived, b?.archived),
            type = pick(l.type, r.type, b?.type),
            target = pick(l.target, r.target, b?.target),
            reminder = pick(l.reminder, r.reminder, b?.reminder)
        )

        val dates = local.entries.keys + remote.entries.keys + base?.entries?.keys.orEmpty()
        val entries = mutableMapOf<String, SyncEntry>()
        for (date in dates) {
            val le = local.entries[date]
            val re = remote.entries[date]
            val be = base?.entries?.get(date)
            val winner = when {
                le == re -> le
                le == be -> re
                re == be -> le
                else -> {
                    val keepLocal =
                        precedence(le?.value ?: Entry.UNKNOWN) >= precedence(re?.value ?: Entry.UNKNOWN)
                    val kept = if (keepLocal) le else re
                    val dropped = if (keepLocal) re else le
                    report.conflicts += SyncConflict(
                        habit.name,
                        date,
                        kept?.value ?: Entry.UNKNOWN,
                        dropped?.value ?: Entry.UNKNOWN
                    )
                    kept
                }
            }
            if (winner != null) entries[date] = winner
        }

        return HabitSnapshot(dirName(habit), habit, entries)
    }

    /** Three-way pick for one field. When both sides moved and disagree, this device wins. */
    private fun <T> pick(local: T, remote: T, base: T?): T = when {
        local == remote -> local
        local == base -> remote
        remote == base -> local
        else -> local
    }

    // ---------------------------------------------------------------- writing

    private fun applyLocally(
        uuid: String,
        local: HabitSnapshot,
        merged: HabitSnapshot,
        report: SyncReport
    ) {
        val habit = habitList.getByUUID(uuid) ?: return
        var touched = false

        if (merged.habit != local.habit) {
            val modified = modelFactory.buildHabit()
            val record = merged.habit.toHabitRecord()
            record.id = habit.id
            record.copyTo(modified)
            EditHabitCommand(habitList, habit.id!!, modified).run()
            touched = true
        }

        val entries = habit.originalEntries
        for ((date, entry) in merged.entries) {
            if (local.entries[date] != entry) {
                entries.add(Entry(Timestamp(parseSyncDate(date)), entry.value, entry.notes))
                touched = true
            }
        }
        // A day the folder no longer carries is erased the way the app itself
        // erases one: by storing UNKNOWN, which renders and behaves exactly like
        // no row at all.
        for (date in local.entries.keys - merged.entries.keys) {
            entries.add(Entry(Timestamp(parseSyncDate(date)), Entry.UNKNOWN, ""))
            touched = true
        }

        if (touched) {
            habit.recompute()
            report.updatedLocally += merged.habit.name
        }
    }

    private fun createLocally(remote: HabitSnapshot, report: SyncReport) {
        val model = modelFactory.buildHabit()
        val record = remote.habit.toHabitRecord()
        record.id = null
        record.copyTo(model)
        CreateHabitCommand(modelFactory, habitList, model).run()

        val created = habitList.getByUUID(remote.habit.uuid) ?: return

        // SQLiteHabitList.add() assigns `position = size()`, overwriting the
        // one just copied in -- so a habit arriving from the folder lands at
        // the end of this device's list instead of where it belongs. Left
        // alone, the next sync publishes the renumbered position and rewrites
        // every habit's file, and the two devices trade orderings forever.
        if (created.position != remote.habit.position) {
            created.position = remote.habit.position
            habitList.update(created)
        }

        for (entry in remote.entries.values) {
            created.originalEntries.add(
                Entry(Timestamp(parseSyncDate(entry.date)), entry.value, entry.notes)
            )
        }
        created.recompute()
        report.createdLocally += remote.habit.name
    }

    private fun deleteLocally(uuid: String, report: SyncReport) {
        val habit: Habit = habitList.getByUUID(uuid) ?: return
        val name = habit.name
        DeleteHabitsCommand(habitList, listOf(habit)).run()
        report.deletedLocally += name
    }

    private fun publish(
        remoteFolder: SyncFolder,
        baseFolder: SyncFolder,
        localDir: String,
        remoteDir: String?,
        merged: HabitSnapshot,
        report: SyncReport
    ) {
        val dir = merged.dir
        // A rename moves the folder. Renames are rare and the files are small,
        // so the old folder is dropped and the new one written whole.
        if (remoteDir != null && remoteDir != dir) {
            remoteFolder.deleteDir(remoteDir)
            baseFolder.deleteDir(remoteDir)
        }
        if (localDir != dir) baseFolder.deleteDir(localDir)

        val habitText = encodeHabit(merged.habit)
        val entriesText = encodeEntries(
            SyncEntries(merged.habit.uuid, merged.entries.values.sortedBy { it.date })
        )

        if (remoteFolder.read(dir, HABIT_FILE) != habitText) {
            remoteFolder.write(dir, HABIT_FILE, habitText)
            report.filesWritten++
        }
        if (remoteFolder.read(dir, ENTRIES_FILE) != entriesText) {
            remoteFolder.write(dir, ENTRIES_FILE, entriesText)
            report.filesWritten++
        }
        if (baseFolder.read(dir, HABIT_FILE) != habitText) {
            baseFolder.write(dir, HABIT_FILE, habitText)
        }
        if (baseFolder.read(dir, ENTRIES_FILE) != entriesText) {
            baseFolder.write(dir, ENTRIES_FILE, entriesText)
        }
    }

    private fun writeBase(baseFolder: SyncFolder, dir: String, snapshot: HabitSnapshot) {
        baseFolder.write(dir, HABIT_FILE, encodeHabit(snapshot.habit))
        baseFolder.write(
            dir,
            ENTRIES_FILE,
            encodeEntries(
                SyncEntries(snapshot.habit.uuid, snapshot.entries.values.sortedBy { it.date })
            )
        )
    }
}

data class HabitSnapshot(
    val dir: String,
    val habit: SyncHabit,
    val entries: Map<String, SyncEntry>
)

data class SyncConflict(
    val habitName: String,
    val date: String,
    val kept: Int,
    val discarded: Int
)

class SyncReport {
    val createdLocally = mutableListOf<String>()
    val updatedLocally = mutableListOf<String>()
    val deletedLocally = mutableListOf<String>()
    val deletedRemotely = mutableListOf<String>()
    val conflicts = mutableListOf<SyncConflict>()
    var filesWritten = 0
    var conflictFilesHealed = 0

    /** The shared folder was empty while this device still remembered habits in it. */
    var abortedEmptyFolder = false

    /** The base was thrown away because it belonged to a different folder. */
    var baseReset = false

    val isEmpty: Boolean
        get() = createdLocally.isEmpty() && updatedLocally.isEmpty() &&
            deletedLocally.isEmpty() && deletedRemotely.isEmpty() &&
            filesWritten == 0 && conflictFilesHealed == 0 && !abortedEmptyFolder
}

/**
 * A reserved subfolder for the base's own bookkeeping. It holds no habit.json,
 * so every reader skips it as a matter of course.
 */
const val META_DIR = ".meta"
const val FOLDER_FILE = "folder"

/**
 * `read-30-min__a3f19c02...`
 *
 * The slug is there so the folder can be read by a person; the UUID is there so
 * the folder can be found by the program. They are separated by a double
 * underscore, which a slug never contains, so the UUID can be recovered from the
 * name without opening any file -- which matters on Android, where every lookup
 * is a Storage Access Framework round trip.
 */
fun dirName(habit: SyncHabit): String {
    val slug = habit.name.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")
        .take(40)
        .trim('-')
    return if (slug.isEmpty()) habit.uuid else slug + "__" + habit.uuid
}
