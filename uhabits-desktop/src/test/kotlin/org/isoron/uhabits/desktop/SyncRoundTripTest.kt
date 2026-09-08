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
package org.isoron.uhabits.desktop

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.core.sync.FileSyncFolder
import org.isoron.uhabits.core.sync.SyncEngine
import org.isoron.uhabits.core.sync.SyncReport
import org.isoron.uhabits.core.sync.parseSyncDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The same round trip the core tests cover, but over real SQLite.
 *
 * This exists because the core tests run on [MemoryHabitList], and the two
 * implementations differ in a way that matters: `SQLiteHabitList.add()`
 * reassigns `position`, and `MemoryHabitList.add()` does not. A whole class of
 * sync bug -- one device silently renumbering the other's habits, then
 * publishing the renumbering -- is invisible in memory and obvious here.
 */
class SyncRoundTripTest {

    private lateinit var root: File
    private lateinit var folder: File
    private lateinit var alice: HabitStore
    private lateinit var bob: HabitStore

    @Before
    fun setUp() {
        root = File.createTempFile("uhabits-sync", "").apply { delete(); mkdirs() }
        folder = File(root, "folder").apply { mkdirs() }
        alice = HabitStore(File(root, "alice.db"))
        bob = HabitStore(File(root, "bob.db"))
    }

    @After
    fun tearDown() {
        alice.close()
        bob.close()
        root.deleteRecursively()
    }

    private fun HabitStore.sync(into: File = folder): SyncReport {
        baseDir.mkdirs()
        return SyncEngine(habitList, modelFactory).sync(
            FileSyncFolder(into),
            FileSyncFolder(baseDir),
            folderId = into.absolutePath,
        )
    }

    private fun HabitStore.add(name: String): Habit {
        val habit = modelFactory.buildHabit()
        habit.name = name
        habitList.add(habit)
        return habit
    }

    private fun HabitStore.byName(name: String): Habit =
        habitList.first { it.name == name }

    private fun HabitStore.set(name: String, date: String, value: Int) {
        val habit = byName(name)
        habit.originalEntries.add(Entry(Timestamp(parseSyncDate(date)), value, ""))
        habit.recompute()
    }

    private fun HabitStore.valueOf(name: String, date: String): Int =
        byName(name).originalEntries.get(Timestamp(parseSyncDate(date))).value

    @Test
    fun `habits arrive in the order they were created, not in uuid order`() {
        // Names chosen so that alphabetical, uuid and positional orders differ.
        listOf("Zebra", "Apple", "Mango").forEach { alice.add(it) }
        val expected = alice.habitList.map { it.name to it.position }

        alice.sync()
        bob.sync()

        val actual = bob.habitList.map { it.name to it.position }.sortedBy { it.second }
        assertThat(actual, equalTo(expected.sortedBy { it.second }))
    }

    @Test
    fun `a single tick rewrites only that habit's entries`() {
        listOf("Zebra", "Apple", "Mango").forEach { alice.add(it) }
        alice.sync()
        bob.sync()
        // Settle: both sides have now seen everything.
        assertThat(alice.sync().filesWritten, equalTo(0))
        assertThat(bob.sync().filesWritten, equalTo(0))

        bob.set("Apple", "2026-09-06", Entry.YES_MANUAL)
        val report = bob.sync()

        // Exactly one file: Apple's entries.json. Its habit.json is unchanged,
        // and the other two habits are untouched.
        assertThat(report.filesWritten, equalTo(1))
    }

    @Test
    fun `a tick made on one device reaches the other`() {
        alice.add("Apple")
        alice.sync()
        bob.sync()

        bob.set("Apple", "2026-09-06", Entry.YES_MANUAL)
        bob.sync()
        alice.sync()

        assertThat(alice.valueOf("Apple", "2026-09-06"), equalTo(Entry.YES_MANUAL))
    }

    @Test
    fun `clearing a tick does not come back from the peer`() {
        alice.add("Apple")
        alice.set("Apple", "2026-09-06", Entry.YES_MANUAL)
        alice.sync()
        bob.sync()
        assertThat(bob.valueOf("Apple", "2026-09-06"), equalTo(Entry.YES_MANUAL))

        alice.set("Apple", "2026-09-06", Entry.UNKNOWN)
        alice.sync()
        bob.sync()
        alice.sync()

        assertThat(bob.valueOf("Apple", "2026-09-06"), equalTo(Entry.UNKNOWN))
        assertThat(alice.valueOf("Apple", "2026-09-06"), equalTo(Entry.UNKNOWN))
    }

    @Test
    fun `repeated syncs settle and stop writing`() {
        listOf("Zebra", "Apple", "Mango").forEach { alice.add(it) }
        alice.set("Apple", "2026-09-05", Entry.YES_MANUAL)

        alice.sync()
        bob.sync()
        alice.sync()
        bob.sync()

        assertThat(alice.sync().isEmpty, equalTo(true))
        assertThat(bob.sync().isEmpty, equalTo(true))
        assertThat(alice.sync().isEmpty, equalTo(true))
    }

    /**
     * The folder went empty; the habits must not.
     *
     * Read literally, an empty folder beside a populated base says every habit
     * was deleted elsewhere -- but the honest causes are all mundane: Syncthing
     * has not populated it yet, the SD card was not mounted, the folder was
     * cleared by hand. Obeying the literal reading costs the owner everything
     * they have, so the sync refuses and says so.
     */
    @Test
    fun `an empty folder does not delete every habit`() {
        listOf("Zebra", "Apple", "Mango").forEach { alice.add(it) }
        alice.sync()
        assertThat(alice.habitList.size(), equalTo(3))

        folder.deleteRecursively()
        folder.mkdirs()
        val report = alice.sync()

        assertThat(report.abortedEmptyFolder, equalTo(true))
        assertThat(alice.habitList.size(), equalTo(3))
        assertThat(report.deletedLocally.size, equalTo(0))
    }

    /**
     * Pointing at a second folder must not read the first folder's history as
     * a set of deletions.
     */
    @Test
    fun `switching to a different folder starts fresh instead of deleting`() {
        listOf("Zebra", "Apple").forEach { alice.add(it) }
        alice.sync()

        val other = File(root, "other").apply { mkdirs() }
        val report = alice.sync(into = other)

        assertThat(report.baseReset, equalTo(true))
        assertThat(alice.habitList.size(), equalTo(2))
        assertThat(report.deletedLocally.size, equalTo(0))
        // ...and the habits are published into the new folder.
        assertThat(other.listFiles()!!.count { it.isDirectory && !it.name.startsWith(".") }, equalTo(2))
    }
}
