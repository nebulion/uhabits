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
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.Timestamp
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import java.util.Calendar
import java.util.Locale

/**
 * One habit's whole history on one screen: the phone's habit page, drawn with
 * block characters.
 *
 * Everything is computed from the full record every time it is drawn, not
 * from a window or a cache -- the charts reach back to the first entry ever
 * made, and the calendar scrolls to any week with the cursor. The work is a
 * few map lookups per day of history, which for years of daily entries is
 * well under a millisecond.
 *
 * Only block elements (`▁ … █`) and plain text are used. The line-drawing set
 * (`─ │ ┤`) is exactly what JLine rewrites into the terminal's alternate
 * character set, which tmux showed as `qqqq`.
 */
class HabitPage(
    private val habit: Habit,
    private val today: Timestamp,
    private val cursor: Timestamp,
    private val firstDayOfWeek: Int,
) {
    private val known: List<Entry> = habit.originalEntries.getKnown() // newest first
    private val first: Timestamp = known.lastOrNull()?.timestamp ?: today
    private val isDone: (Int) -> Boolean = { v ->
        if (habit.isNumerical) v >= 0 && meetsTarget(v) else v == Entry.YES_MANUAL
    }

    fun render(width: Int): List<AttributedString> {
        val out = mutableListOf<AttributedString>()
        out += header(width)
        out += rule(width)
        out += overview(width)
        out += AttributedString.EMPTY
        out += title("Score", "how established the habit is; each day done pulls it up, each miss down")
        out += scoreChart(width)
        out += AttributedString.EMPTY
        out += title(
            "History",
            if (habit.isNumerical) "total ${habit.unit.ifBlank { "recorded" }} per month" else "days done per month",
        )
        out += historyChart(width)
        out += AttributedString.EMPTY
        out += title("Calendar", "h/l moves the cursor; H/L a week at a time, back to the first entry")
        out += calendar(width)
        out += AttributedString.EMPTY

        val streaks = streaks()
        val weekdays = weekdays()
        if (width >= 100) {
            val half = width / 2
            val left = listOf(title("Best streaks")) + streaks
            val right = listOf(title("Days of the week")) + weekdays
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrNull(i) ?: AttributedString.EMPTY
                val r = right.getOrNull(i) ?: AttributedString.EMPTY
                out += AttributedStringBuilder()
                    .append(clip(l, half - 2))
                    .append(" ".repeat((half - l.columnLength()).coerceAtLeast(2)))
                    .append(r)
                    .toAttributedString()
            }
        } else {
            out += title("Best streaks")
            out += streaks
            out += AttributedString.EMPTY
            out += title("Days of the week")
            out += weekdays
        }
        return out
    }

    // ------------------------------------------------------------ sections

    private fun header(width: Int): List<AttributedString> {
        val out = mutableListOf<AttributedString>()
        out += line(" ${habit.name}" to Gruvbox.yellow.bold())
        if (habit.question.isNotBlank()) out += line(" ${habit.question}" to Gruvbox.fg0)
        if (habit.description.isNotBlank()) out += line(" ${habit.description}" to Gruvbox.gray)
        val kind = if (habit.isNumerical) {
            val op = if (habit.targetType == NumericalHabitType.AT_MOST) "at most" else "at least"
            "$op ${Tui.formatNumber(habit.targetValue)} ${habit.unit}".trimEnd()
        } else {
            "yes or no"
        }
        val since = if (known.isEmpty()) "nothing recorded yet" else "tracked since ${longDate(first)} (${span(first, today)})"
        out += line(
            " ${frequencyText()}" to Gruvbox.gray,
            "  ·  " to Gruvbox.darkGray,
            kind to Gruvbox.gray,
            "  ·  " to Gruvbox.darkGray,
            since to Gruvbox.gray,
            (if (habit.isArchived) "  ·  archived" else "") to Gruvbox.orange,
        )
        return out
    }

    private fun overview(width: Int): List<AttributedString> {
        val now = habit.scores[today].value
        val month = now - habit.scores[today.minus(30)].value
        val year = now - habit.scores[today.minus(365)].value
        val done = known.count { isDone(it.value) }
        val last30 = known.count { !it.timestamp.isOlderThan(today.minus(29)) && isDone(it.value) }
        val best = habit.streaks.getBest(1).firstOrNull()?.length ?: 0
        val current = currentStreak()

        val cells = mutableListOf(
            Triple("SCORE", "${pct(now)}", scoreStyle(now)),
            Triple("30 DAYS", signed(month), deltaStyle(month)),
            Triple("1 YEAR", signed(year), deltaStyle(year)),
            Triple("DONE", "$done days", Gruvbox.fg0.bold()),
            Triple("LAST 30", "$last30 / 30", Gruvbox.fg0.bold()),
            Triple("STREAK", "${current}d", if (current > 0) Gruvbox.aqua.bold() else Gruvbox.gray),
            Triple("BEST", "${best}d", Gruvbox.yellow.bold()),
        )
        if (habit.isNumerical) {
            val values = known.filter { it.value >= 0 }.map { it.value / 1000.0 }
            val total = values.sum()
            val average = if (values.isEmpty()) 0.0 else total / values.size
            cells.add(4, Triple("TOTAL", "${Tui.formatNumber(total)}", Gruvbox.fg0.bold()))
            cells.add(5, Triple("AVERAGE", "${Tui.formatNumber(average)}/day", Gruvbox.fg0.bold()))
            cells.removeAt(3)
        }

        val cellWidth = 12
        val perRow = ((width - 1) / cellWidth).coerceAtLeast(1)
        val out = mutableListOf<AttributedString>()
        for (chunk in cells.chunked(perRow)) {
            val labels = AttributedStringBuilder().append(" ")
            val values = AttributedStringBuilder().append(" ")
            for ((label, value, style) in chunk) {
                labels.style(Gruvbox.gray).append(label.padEnd(cellWidth))
                values.style(style).append(value.padEnd(cellWidth))
            }
            out += labels.toAttributedString()
            out += values.toAttributedString()
        }
        return out
    }

    /** One column per week, back to the first entry or as far as fits. */
    private fun scoreChart(width: Int): List<AttributedString> {
        val gutter = 6
        val weeksSince = first.daysUntil(today) / 7 + 1
        val columns = minOf(weeksSince, width - gutter - 1).coerceAtLeast(1)
        // A short history is drawn wider rather than bunched up on the left.
        val col = ((width - gutter - 1) / columns).coerceIn(1, 3)
        val points = (0 until columns).map { i ->
            val t = today.minus(7 * (columns - 1 - i))
            t to habit.scores[t].value
        }
        val rows = 6
        val out = mutableListOf<AttributedString>()
        for (r in rows - 1 downTo 0) {
            val sb = AttributedStringBuilder()
            val label = when (r) {
                rows - 1 -> "100%"
                rows / 2 -> " 50%"
                0 -> "  0%"
                else -> ""
            }
            sb.style(Gruvbox.darkGray).append(label.padStart(gutter - 2)).append("  ")
            for ((_, v) in points) {
                sb.style(scoreStyle(v)).append(block(v * rows - r).repeat(col))
            }
            out += sb.toAttributedString()
        }
        out += axis(points.map { it.first }, gutter, col)
        return out
    }

    /** Done days (or the recorded total) per month. */
    private fun historyChart(width: Int): List<AttributedString> {
        val gutter = 6
        val months = monthsBetween(first, today) + 1
        val col = ((width - gutter - 1) / months).coerceIn(4, 8)
        val count = minOf(months, (width - gutter - 1) / col).coerceAtLeast(1)
        val starts = (0 until count).map { i -> monthStart(today, -(count - 1 - i)) }
        val totals = starts.map { start ->
            val end = monthStart(start, 1)
            known.filter { !it.timestamp.isOlderThan(start) && it.timestamp.isOlderThan(end) }.let { list ->
                if (habit.isNumerical) {
                    list.filter { it.value >= 0 }.sumOf { it.value / 1000.0 }
                } else {
                    list.count { it.value == Entry.YES_MANUAL }.toDouble()
                }
            }
        }
        val max = totals.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val rows = 5
        val out = mutableListOf<AttributedString>()
        for (r in rows - 1 downTo 0) {
            val sb = AttributedStringBuilder().append(" ".repeat(gutter))
            for (v in totals) {
                sb.style(Gruvbox.blue).append(block(v / max * rows - r).repeat(col - 1)).append(" ")
            }
            out += sb.toAttributedString()
        }
        val values = AttributedStringBuilder().append(" ".repeat(gutter))
        val labels = AttributedStringBuilder().append(" ".repeat(gutter))
        var lastYear = -1
        for ((i, start) in starts.withIndex()) {
            val v = totals[i]
            values.style(if (v > 0) Gruvbox.fg0 else Gruvbox.darkGray)
                .append(compact(v, col - 1).padStart(col - 1)).append(" ")
            val cal = Grid.calendarAt(start)
            val year = cal.get(Calendar.YEAR)
            labels.style(if (year != lastYear) Gruvbox.orange else Gruvbox.gray)
                .append((if (year != lastYear && i > 0) "'" + (year % 100) else Grid.monthLabel(start)).padEnd(col))
            lastYear = year
        }
        out += values.toAttributedString()
        out += labels.toAttributedString()
        return out
    }

    /**
     * Weeks across, weekdays down. The window ends at the current week unless
     * the cursor has gone further back than that, in which case it follows it.
     */
    private fun calendar(width: Int): List<AttributedString> {
        val gutter = 6
        // As many weeks as fit, but no screen of empty weeks before a new habit began.
        val weeksSince = startOfWeek(first).daysUntil(startOfWeek(today)) / 7 + 1
        val weeks = minOf(((width - gutter - 1) / 2).coerceIn(4, 200), maxOf(weeksSince, 12))
        val thisWeek = startOfWeek(today)
        var lastWeek = thisWeek
        val cursorWeek = startOfWeek(cursor)
        if (cursorWeek.isOlderThan(thisWeek.minus(7 * (weeks - 1)))) lastWeek = cursorWeek.plus(7 * (weeks / 2))
        val firstWeek = lastWeek.minus(7 * (weeks - 1))
        val values = habit.computedEntries.getByInterval(firstWeek, today).associateBy { it.timestamp }

        val out = mutableListOf<AttributedString>()
        val header = CharArray(gutter + weeks * 2) { ' ' }
        var lastMonth = -1
        var freeFrom = 0
        for (w in 0 until weeks) {
            val start = firstWeek.plus(7 * w)
            val cal = Grid.calendarAt(start)
            val month = cal.get(Calendar.MONTH)
            if (month != lastMonth) {
                val label = if (month == Calendar.JANUARY || w == 0) {
                    "${Grid.monthLabel(start)} ${cal.get(Calendar.YEAR)}"
                } else {
                    Grid.monthLabel(start)
                }
                val at = gutter + w * 2
                if (at >= freeFrom && at + label.length <= header.size) {
                    label.forEachIndexed { k, ch -> header[at + k] = ch }
                    freeFrom = at + label.length + 1
                }
                lastMonth = month
            }
        }
        out += AttributedString(String(header).trimEnd(), Gruvbox.orange)
        for (d in 0 until 7) {
            val sb = AttributedStringBuilder()
            val dayName = Grid.calendarAt(firstWeek.plus(d))
                .getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US) ?: ""
            sb.style(if (d % 2 == 0) Gruvbox.gray else Gruvbox.darkGray).append(" ${dayName.padEnd(gutter - 2)} ")
            for (w in 0 until weeks) {
                val t = firstWeek.plus(7 * w + d)
                if (t.isNewerThan(today) || t.isOlderThan(first)) {
                    sb.style(Gruvbox.darkGray).append(if (t == cursor) "[]" else "  ")
                    continue
                }
                val value = values[t]?.value ?: Entry.UNKNOWN
                var style = Gruvbox.forValue(habit, value)
                val glyph = if (habit.isNumerical) numericGlyph(value) else Grid.cell(habit, value)
                if (t == cursor) style = style.inverse()
                sb.style(style).append(glyph).style(Gruvbox.fg0).append(" ")
            }
            out += sb.toAttributedString()
        }
        if (firstWeek.isNewerThan(first)) {
            val hidden = first.daysUntil(firstWeek) / 7
            out += line(" ".repeat(gutter) + "$hidden earlier weeks; H or g to go back" to Gruvbox.darkGray)
        }
        return out
    }

    private fun streaks(): List<AttributedString> {
        val best = habit.streaks.getBest(6)
        if (best.isEmpty()) return listOf(line("   none yet" to Gruvbox.gray))
        val longest = best.maxOf { it.length }
        return best.sortedByDescending { it.length }.map { s ->
            val bar = ((s.length.toDouble() / longest) * 20).toInt().coerceAtLeast(1)
            val live = s.end == today || s.end == today.minus(1)
            line(
                "  ${s.length.toString().padStart(4)}d " to Gruvbox.fg0.bold(),
                "█".repeat(bar).padEnd(21) to if (live) Gruvbox.aqua else Gruvbox.green,
                "${shortDate(s.start)} to ${shortDate(s.end)}" to Gruvbox.gray,
                (if (live) "  now" else "") to Gruvbox.aqua,
            )
        }
    }

    private fun weekdays(): List<AttributedString> {
        val totals = DoubleArray(7)
        val counts = IntArray(7)
        for (e in known) {
            val dow = Grid.calendarAt(e.timestamp).get(Calendar.DAY_OF_WEEK) - 1
            counts[dow]++
            if (habit.isNumerical) {
                if (e.value >= 0) totals[dow] += e.value / 1000.0
            } else if (e.value == Entry.YES_MANUAL) {
                totals[dow] += 1.0
            }
        }
        val max = totals.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val order = (0 until 7).map { (firstDayOfWeek - 1 + it) % 7 }
        return order.map { dow ->
            val name = Grid.calendarAt(startOfWeek(today).plus((dow - (firstDayOfWeek - 1) + 7) % 7))
                .getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US) ?: ""
            val bar = (totals[dow] / max * 20).toInt()
            val rate = if (!habit.isNumerical && counts[dow] > 0) "  ${Math.round(totals[dow] * 100 / counts[dow])}% of days logged" else ""
            line(
                "  ${name.padEnd(4)}" to Gruvbox.gray,
                ("█".repeat(bar) + "▏").padEnd(22) to Gruvbox.purple,
                Tui.formatNumber(totals[dow]).padStart(4) to Gruvbox.fg0,
                rate to Gruvbox.darkGray,
            )
        }
    }

    // -------------------------------------------------------------- helpers

    private fun meetsTarget(value: Int): Boolean = when (habit.targetType) {
        NumericalHabitType.AT_MOST -> value / 1000.0 <= habit.targetValue
        else -> value / 1000.0 >= habit.targetValue
    }

    private fun numericGlyph(value: Int): String = when {
        value < 0 -> "·"
        meetsTarget(value) -> "✓"
        value > 0 -> "~"
        else -> "×"
    }

    private fun currentStreak(): Int = habit.streaks.getBest(10_000)
        .firstOrNull { it.end == today || it.end == today.minus(1) }?.length ?: 0

    private fun frequencyText(): String = habit.frequency.let {
        when {
            it.numerator == 1 && it.denominator == 1 -> "every day"
            it.denominator == 7 -> "${it.numerator}x a week"
            it.denominator in 28..31 -> "${it.numerator}x a month"
            it.numerator == 1 -> "every ${it.denominator} days"
            else -> "${it.numerator} in ${it.denominator} days"
        }
    }

    /** The eighth-block for how much of one chart row a value fills. */
    private fun block(fill: Double): String = when {
        fill >= 1 -> "█"
        fill <= 0 -> " "
        else -> BLOCKS[(fill * 8).toInt().coerceIn(0, 7)]
    }

    /** Month labels under a chart with one column per week. */
    private fun axis(days: List<Timestamp>, gutter: Int, col: Int): AttributedString {
        val chars = CharArray(gutter + days.size * col) { ' ' }
        var lastMonth = -1
        var freeFrom = 0
        for ((i, t) in days.withIndex()) {
            val cal = Grid.calendarAt(t)
            val month = cal.get(Calendar.MONTH)
            if (month == lastMonth) continue
            lastMonth = month
            val label = if (month == Calendar.JANUARY) "${cal.get(Calendar.YEAR)}" else Grid.monthLabel(t)
            val at = gutter + i * col
            if (at >= freeFrom && at + label.length <= chars.size) {
                label.forEachIndexed { k, ch -> chars[at + k] = ch }
                freeFrom = at + label.length + 1
            }
        }
        return AttributedString(String(chars).trimEnd(), Gruvbox.gray)
    }

    private fun startOfWeek(t: Timestamp): Timestamp {
        val weekday = Grid.calendarAt(t).get(Calendar.DAY_OF_WEEK)
        return t.minus((weekday - firstDayOfWeek + 7) % 7)
    }

    private fun monthStart(t: Timestamp, offset: Int): Timestamp {
        val cal = Grid.calendarAt(t)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.add(Calendar.MONTH, offset)
        return Timestamp(cal.timeInMillis)
    }

    private fun monthsBetween(a: Timestamp, b: Timestamp): Int {
        val x = Grid.calendarAt(a)
        val y = Grid.calendarAt(b)
        return (y.get(Calendar.YEAR) - x.get(Calendar.YEAR)) * 12 + y.get(Calendar.MONTH) - x.get(Calendar.MONTH)
    }

    private fun span(a: Timestamp, b: Timestamp): String {
        val months = monthsBetween(a, b)
        return when {
            months >= 12 && months % 12 != 0 -> "${months / 12} yr ${months % 12} mo"
            months >= 12 -> "${months / 12} yr"
            months > 0 -> "$months mo"
            else -> "${a.daysUntil(b) + 1} days"
        }
    }

    private fun longDate(t: Timestamp): String {
        val cal = Grid.calendarAt(t)
        return "${Grid.monthLabel(t)} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)}"
    }

    private fun shortDate(t: Timestamp): String {
        val cal = Grid.calendarAt(t)
        val year = cal.get(Calendar.YEAR)
        val sameYear = year == Grid.calendarAt(today).get(Calendar.YEAR)
        return "${Grid.monthLabel(t)} ${cal.get(Calendar.DAY_OF_MONTH)}" + if (sameYear) "" else " $year"
    }

    private fun pct(v: Double) = "${Math.round(v * 100)}%"

    private fun signed(v: Double): String {
        val p = Math.round(v * 100)
        return if (p > 0) "+$p%" else "$p%"
    }

    private fun compact(v: Double, room: Int): String {
        val plain = Tui.formatNumber(v)
        if (plain.length <= room) return plain
        val rounded = Math.round(v).toString()
        if (rounded.length <= room) return rounded
        return "${Math.round(v / 1000)}k".take(room)
    }

    private fun scoreStyle(v: Double): AttributedStyle = when {
        v >= 0.75 -> Gruvbox.green
        v >= 0.4 -> Gruvbox.yellow
        else -> Gruvbox.orange
    }

    private fun deltaStyle(v: Double): AttributedStyle = when {
        v > 0.005 -> Gruvbox.green.bold()
        v < -0.005 -> Gruvbox.red.bold()
        else -> Gruvbox.gray
    }

    private fun title(text: String, hint: String = ""): AttributedString =
        line(" $text" to Gruvbox.orange.bold(), (if (hint.isEmpty()) "" else "   $hint") to Gruvbox.darkGray)

    private fun rule(width: Int) = AttributedString("━".repeat(width), Gruvbox.rule)

    private fun line(vararg parts: Pair<String, AttributedStyle>): AttributedString {
        val sb = AttributedStringBuilder()
        for ((text, style) in parts) sb.style(style).append(text)
        return sb.toAttributedString()
    }

    private fun clip(s: AttributedString, cols: Int): AttributedString =
        if (s.columnLength() <= cols) s else s.columnSubSequence(0, cols)

    companion object {
        private val BLOCKS = listOf(" ", "▁", "▂", "▃", "▄", "▅", "▆", "▇")
    }
}
