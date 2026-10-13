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

import org.isoron.uhabits.core.models.Timestamp
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuiTest {

    // Wednesday 23 September 2026.
    private val today = ts(LocalDate.of(2026, 9, 23))

    private fun ts(d: LocalDate) = Timestamp(d.toEpochDay() * Timestamp.DAY_LENGTH)

    private fun date(text: String) = Tui.parseDate(text, today)

    @Test
    fun readsTheDatesPeopleType() {
        assertEquals(today, date(""))
        assertEquals(ts(LocalDate.of(2026, 9, 22)), date("yesterday"))
        assertEquals(ts(LocalDate.of(2026, 9, 20)), date("-3"))
        assertEquals(ts(LocalDate.of(2026, 9, 1)), date("2026-09-01"))
        assertEquals(ts(LocalDate.of(2026, 9, 1)), date("sep 1"))
        assertEquals(ts(LocalDate.of(2026, 9, 1)), date("1 September"))
        assertEquals(ts(LocalDate.of(2026, 9, 1)), date("9/1"))
        assertEquals(ts(LocalDate.of(2026, 9, 21)), date("mon"))
        assertEquals(today, date("wed"))
    }

    @Test
    fun aDateWithNoYearNeverLandsInTheFuture() {
        // The 30th has not come yet this month, so it means last month's.
        assertEquals(ts(LocalDate.of(2026, 8, 30)), date("30"))
        assertEquals(ts(LocalDate.of(2025, 12, 25)), date("dec 25"))
    }

    @Test
    fun nonsenseIsNotADate() {
        assertNull(date("soon"))
        assertNull(date("2026-13-40"))
    }

    @Test
    fun fuzzyPrefersWholeWordsAndSkipsNonMatches() {
        assertNull(Tui.fuzzy("xyz", "Reading"))
        assertNotNull(Tui.fuzzy("rdg", "Reading"))
        assertTrue(Tui.fuzzy("sync", "Sync now")!! > Tui.fuzzy("sync", "Show archived (no sync)")!!)
    }

    @Test
    fun fitCountsColumnsNotCharacters() {
        assertEquals("Reading   ", Tui.fit("Reading", 10))
        assertEquals("Nerd neck…", Tui.fit("Nerd neck recovery", 10))
    }

    /**
     * The frame is the contract with the terminal: no line may reach the last
     * column, or the row below it moves. Checked on an empty database, in both
     * views and with every overlay open.
     */
    @Test
    fun noLineReachesTheLastColumn() {
        val dir = Files.createTempDirectory("tui").toFile()
        try {
            HabitStore(File(dir, "test.db")).use { store ->
                store.habitList.add(store.modelFactory.buildHabit().apply { name = "A habit with a long enough name" })
                val config = Config(File(dir, "config.properties"))
                for (keys in listOf("", "w", "?", "\r", ":", "/hab", "g")) {
                    for (width in listOf(40, 80, 132)) {
                        val frame = Tui(store, config).dump(24, width, keys)
                        assertEquals(24, frame.size)
                        for (line in frame) {
                            val cols = org.jline.utils.AttributedString.fromAnsi(line).columnLength()
                            assertTrue(cols < width, "keys=\"$keys\" width=$width: $cols columns in \"$line\"")
                        }
                    }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
