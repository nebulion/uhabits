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

import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.Timestamp
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * The habits-by-days grid, shared by `uhabits list` and by the terminal UI.
 *
 * The phone fits three day columns on a 320dp panel; the same layout in a
 * terminal fits twenty or more, so the column count is derived from the width
 * rather than fixed. Oldest day on the left, today on the right, which is the
 * order a calendar reads in -- note this is the reverse of the core's own
 * `getByInterval`, which returns newest first.
 */
object Grid {

    const val COL = 3
    const val NAME_WIDTH = 20

    fun columnsFor(width: Int, nameWidth: Int = NAME_WIDTH): Int =
        ((width - nameWidth - 1) / COL).coerceIn(1, 60)

    fun cell(habit: Habit, value: Int): String = when {
        habit.isNumerical -> when {
            value < 0 -> "·"
            else -> formatNumber(value / 1000.0)
        }
        value == Entry.YES_MANUAL -> "✓"
        // Kept distinct from a manual tick on purpose. A non-daily habit
        // credits the days around one you did perform, and the phone's grid
        // draws those the same as the real thing -- so a 3-times-a-week habit
        // reads as a wall of ticks and you cannot see what you actually did.
        value == Entry.YES_AUTO -> "+"
        value == Entry.NO -> "✗"
        value == Entry.SKIP -> "→"
        else -> "·"
    }

    private fun formatNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    /** Values for one habit over the window, oldest first. */
    fun valuesFor(habit: Habit, end: Timestamp, days: Int): List<Int> {
        val start = end.minus(days - 1)
        return habit.computedEntries.getByInterval(start, end).map { it.value }.reversed()
    }

    fun calendarAt(timestamp: Timestamp): Calendar =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timestamp.unixTime }

    fun pad(s: String, width: Int = COL): String =
        if (s.length >= width) s.substring(0, width) else " ".repeat(width - s.length) + s

    /** The timestamp under column [i] of a window of [days] ending at [end]. */
    fun dayAt(end: Timestamp, days: Int, i: Int): Timestamp = end.minus(days - 1 - i)

    fun monthLabel(t: Timestamp): String =
        calendarAt(t).getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.US) ?: ""

    fun dayLabel(t: Timestamp): String = calendarAt(t).get(Calendar.DAY_OF_MONTH).toString()

    fun weekdayLabel(t: Timestamp): String = WEEKDAYS[calendarAt(t).get(Calendar.DAY_OF_WEEK) - 1]

    fun isWeekend(t: Timestamp): Boolean =
        calendarAt(t).get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }

    /** The full date under the cursor, spelled out for the status line. */
    fun longDate(t: Timestamp): String = calendarAt(t).let { cal ->
        val weekday = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US)
        "$weekday ${cal.get(Calendar.DAY_OF_MONTH)} ${monthLabel(t)} ${cal.get(Calendar.YEAR)}"
    }

    fun headerLines(end: Timestamp, days: Int, nameWidth: Int = NAME_WIDTH): List<String> {
        val months = StringBuilder(" ".repeat(nameWidth))
        val numbers = StringBuilder(" ".repeat(nameWidth))
        val weekdays = StringBuilder(" ".repeat(nameWidth))
        var lastMonth = -1
        for (i in 0 until days) {
            val cal = calendarAt(end.minus(days - 1 - i))
            val month = cal.get(Calendar.MONTH)
            months.append(
                if (month != lastMonth) {
                    pad(cal.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.US) ?: "", COL)
                } else {
                    " ".repeat(COL)
                },
            )
            lastMonth = month
            numbers.append(pad(cal.get(Calendar.DAY_OF_MONTH).toString(), COL))
            weekdays.append(pad(WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1], COL))
        }
        return listOf(months.toString(), numbers.toString(), weekdays.toString())
    }

    val WEEKDAYS = listOf("S", "M", "T", "W", "T", "F", "S")

    /**
     * One habit's row. [cursorCol] is the column to bracket, or -1 for none;
     * the brackets sit in the padding of the cell rather than widening it, so
     * the grid does not shift as the cursor moves.
     */
    fun row(
        habit: Habit,
        end: Timestamp,
        days: Int,
        nameWidth: Int = NAME_WIDTH,
        cursorCol: Int = -1,
    ): String {
        val sb = StringBuilder()
        val name = habit.name.let { if (it.length > nameWidth - 3) it.substring(0, nameWidth - 4) + "…" else it }
        sb.append(" ").append(name.padEnd(nameWidth - 1))
        val values = valuesFor(habit, end, days)
        for (i in 0 until days) {
            val text = cell(habit, values.getOrElse(i) { Entry.UNKNOWN })
            if (i == cursorCol) {
                sb.append(pad("[$text]", COL))
            } else {
                sb.append(pad(text, COL))
            }
        }
        return sb.toString()
    }

    fun summary(habit: Habit): String {
        val score = habit.scores[org.isoron.uhabits.core.utils.DateUtils.getTodayWithOffset()].value
        val best = habit.streaks.getBest(1).firstOrNull()?.length ?: 0
        val frequency = habit.frequency.let {
            when {
                it.numerator == 1 && it.denominator == 1 -> "every day"
                it.denominator == 7 -> "${it.numerator}× per week"
                it.numerator == 1 -> "every ${it.denominator} days"
                else -> "${it.numerator} in ${it.denominator} days"
            }
        }
        return "${habit.name} · $frequency · ${Math.round(score * 100)}% · best streak ${best}d"
    }
}
