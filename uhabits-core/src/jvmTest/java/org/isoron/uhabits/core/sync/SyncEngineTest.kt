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

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.core.models.memory.MemoryModelFactory
import org.isoron.uhabits.core.utils.DateUtils.Companion.setFixedLocalTime
import org.isoron.uhabits.core.utils.DateUtils.Companion.setStartDayOffset
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Two devices, one shared folder.
 *
 * Every test here drives the real engine through the same shape the phone and
 * the desktop will: change something locally, sync, let the other side sync,
 * and check what it ended up with. The folder is in memory rather than on disk,
 * which is what makes it cheap enough to test the cases that are expensive to
 * reproduce by hand -- a deletion racing a peer that has not seen it, a
 * Syncthing conflict copy, a value being cleared rather than set.
 */
class SyncEngineTest {

    private lateinit var remote: MemorySyncFolder
    private lateinit var phone: Device
    private lateinit var desktop: Device

    @Before
    fun setUp() {
        setFixedLocalTime(1422172800000L)
        setStartDayOffset(0, 0)
        remote = MemorySyncFolder()
        phone = Device()
        desktop = Device()
    }

    @After
    fun tearDown() {
        setFixedLocalTime(null)
        setStartDayOffset(0, 0)
    }

    private class Device {
        val factory: ModelFactory = MemoryModelFactory()
        val habits: HabitList = factory.buildHabitList()
        val base = MemorySyncFolder()
        private val engine = SyncEngine(habits, factory)

        fun sync(remote: SyncFolder): SyncReport = engine.sync(remote, base)

        fun add(name: String, uuid: String): Habit {
            val habit = factory.buildHabit()
            habit.name = name
            habit.uuid = uuid
            habits.add(habit)
            return habit
        }

        fun byName(name: String): Habit? = habits.firstOrNull { it.name == name }

        fun set(name: String, date: String, value: Int, notes: String = "") {
            val habit = byName(name)!!
            habit.originalEntries.add(Entry(Timestamp(parseSyncDate(date)), value, notes))
            habit.recompute()
        }

        fun valueOf(name: String, date: String): Int =
            byName(name)!!.originalEntries.get(Timestamp(parseSyncDate(date))).value

        fun names(): List<String> = habits.map { it.name }.sorted()
    }

    /** Carries a change from one device to the other, the way Syncthing would. */
    private fun roundTrip() {
        phone.sync(remote)
        desktop.sync(remote)
        phone.sync(remote)
    }

    @Test
    fun `a habit created on one device appears on the other`() {
        phone.add("Meditate", "uuid-meditate")
        phone.set("Meditate", "2015-01-20", Entry.YES_MANUAL)

        roundTrip()

        assertThat(desktop.names(), equalTo(listOf("Meditate")))
        assertThat(desktop.valueOf("Meditate", "2015-01-20"), equalTo(Entry.YES_MANUAL))
    }

    @Test
    fun `an entry set on one device reaches the other`() {
        phone.add("Read", "uuid-read")
        roundTrip()

        desktop.set("Read", "2015-01-21", Entry.YES_MANUAL)
        desktop.sync(remote)
        phone.sync(remote)

        assertThat(phone.valueOf("Read", "2015-01-21"), equalTo(Entry.YES_MANUAL))
    }

    /**
     * The case that decides whether a base is needed at all.
     *
     * Clearing a check-mark leaves the peer holding a value the local device no
     * longer has. Without a base to say which side moved, the peer's stale
     * value looks exactly like a new edit and gets applied back -- the tick
     * reappears a few seconds after you remove it. This test fails on any
     * design that merges by union or by precedence alone.
     */
    @Test
    fun `clearing an entry does not come back from the peer`() {
        phone.add("Read", "uuid-read")
        phone.set("Read", "2015-01-21", Entry.YES_MANUAL)
        roundTrip()
        assertThat(desktop.valueOf("Read", "2015-01-21"), equalTo(Entry.YES_MANUAL))

        phone.set("Read", "2015-01-21", Entry.UNKNOWN)
        roundTrip()

        assertThat(phone.valueOf("Read", "2015-01-21"), equalTo(Entry.UNKNOWN))
        assertThat(desktop.valueOf("Read", "2015-01-21"), equalTo(Entry.UNKNOWN))
    }

    @Test
    fun `changing a yes to a no propagates rather than losing to precedence`() {
        phone.add("Read", "uuid-read")
        phone.set("Read", "2015-01-21", Entry.YES_MANUAL)
        roundTrip()

        phone.set("Read", "2015-01-21", Entry.NO)
        roundTrip()

        assertThat(desktop.valueOf("Read", "2015-01-21"), equalTo(Entry.NO))
    }

    @Test
    fun `a rename propagates and moves the folder`() {
        phone.add("Meditate", "uuid-meditate")
        roundTrip()

        phone.byName("Meditate")!!.name = "Sit quietly"
        phone.habits.update(phone.byName("Sit quietly")!!)
        roundTrip()

        assertThat(desktop.names(), equalTo(listOf("Sit quietly")))
        assertThat(remote.listHabitDirs(), equalTo(listOf("sit-quietly__uuid-meditate")))
    }

    @Test
    fun `a habit deleted on one device stays deleted on the other`() {
        phone.add("Meditate", "uuid-meditate")
        phone.add("Read", "uuid-read")
        roundTrip()
        assertThat(desktop.names(), equalTo(listOf("Meditate", "Read")))

        phone.habits.remove(phone.byName("Meditate")!!)
        roundTrip()
        assertThat(desktop.names(), equalTo(listOf("Read")))

        // The habit must not be re-created by the next round from a peer that
        // still had it in its own base a moment ago.
        roundTrip()
        assertThat(desktop.names(), equalTo(listOf("Read")))
        assertThat(phone.names(), equalTo(listOf("Read")))
    }

    @Test
    fun `two habits with the same name created independently stay two habits`() {
        phone.add("Walk", "uuid-walk-phone")
        desktop.add("Walk", "uuid-walk-desktop")

        roundTrip()
        desktop.sync(remote)

        assertThat(phone.names(), equalTo(listOf("Walk", "Walk")))
        assertThat(desktop.names(), equalTo(listOf("Walk", "Walk")))
    }

    @Test
    fun `simultaneous edits to different days both survive`() {
        phone.add("Read", "uuid-read")
        roundTrip()

        phone.set("Read", "2015-01-20", Entry.YES_MANUAL)
        desktop.set("Read", "2015-01-21", Entry.SKIP)

        phone.sync(remote)
        desktop.sync(remote)
        phone.sync(remote)

        for (device in listOf(phone, desktop)) {
            assertThat(device.valueOf("Read", "2015-01-20"), equalTo(Entry.YES_MANUAL))
            assertThat(device.valueOf("Read", "2015-01-21"), equalTo(Entry.SKIP))
        }
    }

    @Test
    fun `simultaneous edits to the same day resolve by precedence and are reported`() {
        phone.add("Read", "uuid-read")
        roundTrip()

        phone.set("Read", "2015-01-20", Entry.NO)
        desktop.set("Read", "2015-01-20", Entry.YES_MANUAL)

        phone.sync(remote)
        val report = desktop.sync(remote)
        phone.sync(remote)

        assertThat(report.conflicts.size, equalTo(1))
        assertThat(report.conflicts[0].date, equalTo("2015-01-20"))
        assertThat(report.conflicts[0].kept, equalTo(Entry.YES_MANUAL))
        assertThat(report.conflicts[0].discarded, equalTo(Entry.NO))
        assertThat(desktop.valueOf("Read", "2015-01-20"), equalTo(Entry.YES_MANUAL))
        assertThat(phone.valueOf("Read", "2015-01-20"), equalTo(Entry.YES_MANUAL))
    }

    /**
     * What Syncthing actually leaves behind when both devices wrote the same
     * file while disconnected: the loser renamed alongside the winner.
     */
    @Test
    fun `a syncthing conflict copy is unioned in and deleted`() {
        phone.add("Read", "uuid-read")
        phone.set("Read", "2015-01-20", Entry.YES_MANUAL)
        roundTrip()

        val dir = remote.listHabitDirs().single()
        val losing = SyncEntries(
            "uuid-read",
            listOf(
                SyncEntry("2015-01-20", Entry.NO),
                SyncEntry("2015-01-22", Entry.YES_MANUAL)
            )
        )
        remote.write(dir, "entries.sync-conflict-20150125-080000-ABCDEFG.json", encodeEntries(losing))

        val report = phone.sync(remote)

        assertThat(report.conflictFilesHealed, equalTo(1))
        assertThat(remote.listFiles(dir).none { isConflictCopy(it) }, equalTo(true))
        // The day only the losing copy had is recovered...
        assertThat(phone.valueOf("Read", "2015-01-22"), equalTo(Entry.YES_MANUAL))
        // ...and the day they disagreed about resolves by precedence, reported.
        assertThat(phone.valueOf("Read", "2015-01-20"), equalTo(Entry.YES_MANUAL))
        assertThat(report.conflicts.map { it.date }, equalTo(listOf("2015-01-20")))
    }

    @Test
    fun `a sync with nothing to do writes nothing`() {
        phone.add("Meditate", "uuid-meditate")
        phone.set("Meditate", "2015-01-20", Entry.YES_MANUAL)
        roundTrip()
        desktop.sync(remote)

        val quiet = listOf(phone.sync(remote), desktop.sync(remote), phone.sync(remote))
        for (report in quiet) {
            assertThat(report.filesWritten, equalTo(0))
            assertThat(report.isEmpty, equalTo(true))
        }
    }

    @Test
    fun `habit metadata merges field by field`() {
        phone.add("Read", "uuid-read")
        roundTrip()

        // Two unrelated fields, changed on two devices, between the same pair
        // of syncs. Neither should overwrite the other.
        phone.byName("Read")!!.let {
            it.question = "Did you read today?"
            phone.habits.update(it)
        }
        desktop.byName("Read")!!.let {
            it.position = 7
            desktop.habits.update(it)
        }

        phone.sync(remote)
        desktop.sync(remote)
        phone.sync(remote)

        assertThat(phone.byName("Read")!!.question, equalTo("Did you read today?"))
        assertThat(phone.byName("Read")!!.position, equalTo(7))
        assertThat(desktop.byName("Read")!!.question, equalTo("Did you read today?"))
        assertThat(desktop.byName("Read")!!.position, equalTo(7))
    }

    @Test
    fun `notes travel with the entry`() {
        phone.add("Read", "uuid-read")
        phone.set("Read", "2015-01-20", Entry.SKIP, "travelling")
        roundTrip()

        val entry = desktop.byName("Read")!!.originalEntries
            .get(Timestamp(parseSyncDate("2015-01-20")))
        assertThat(entry.value, equalTo(Entry.SKIP))
        assertThat(entry.notes, equalTo("travelling"))
    }
}
