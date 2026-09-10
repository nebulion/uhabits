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
import org.isoron.uhabits.core.utils.DateFormats
import org.junit.Test
import java.util.Date

/**
 * The date conversion is arithmetic now rather than `SimpleDateFormat`, because
 * it runs once per entry per sync and building a formatter five thousand times
 * dominated the whole operation.
 *
 * Replacing a library routine with hand-written maths is only worth doing if it
 * is demonstrably the same routine, so these check it against the thing it
 * replaced -- across leap years, century boundaries and every day of a decade.
 */
class SyncDateTest {

    private val reference = DateFormats.getCSVDateFormat()
    private val dayMillis = 86_400_000L

    @Test
    fun `matches SimpleDateFormat for every day across a decade`() {
        // 2020-01-01 through 2029-12-31, UTC midnight, one day at a time.
        var t = 1577836800000L
        var checked = 0
        repeat(3653) {
            assertThat(formatSyncDate(t), equalTo(reference.format(Date(t))))
            t += dayMillis
            checked++
        }
        assertThat(checked, equalTo(3653))
    }

    @Test
    fun `round-trips every day across a decade`() {
        var t = 1577836800000L
        repeat(3653) {
            assertThat(parseSyncDate(formatSyncDate(t)), equalTo(t))
            t += dayMillis
        }
    }

    @Test
    fun `handles the awkward dates`() {
        // A leap day, the century that is not a leap year, the one that is,
        // and the far ends of anything this app could hold.
        for (date in listOf(
            "2024-02-29", "1900-03-01", "2000-02-29", "2100-03-01",
            "1970-01-01", "1969-12-31", "1900-01-01", "2999-12-31",
        )) {
            val millis = parseSyncDate(date)
            assertThat(date, formatSyncDate(millis), equalTo(date))
            assertThat(date, reference.format(Date(millis)), equalTo(date))
        }
    }

    @Test
    fun `parses what the old formatter produced`() {
        var t = 1577836800000L
        repeat(400) {
            assertThat(parseSyncDate(reference.format(Date(t))), equalTo(t))
            t += dayMillis
        }
    }
}
