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
import org.isoron.uhabits.core.sync.FileSyncFolder
import org.isoron.uhabits.core.sync.SyncEngine
import org.isoron.uhabits.core.sync.formatSyncDate
import org.isoron.uhabits.core.utils.DateUtils
import org.jline.keymap.BindingReader
import org.jline.keymap.KeyMap
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import org.jline.utils.Display
import org.jline.utils.InfoCmp.Capability

/**
 * The full-screen terminal client.
 *
 * Built on JLine's own full-screen primitives -- raw mode, the alternate
 * screen, and [Display] for incremental redraw -- rather than on a widget
 * toolkit. JLine drives the real terminal on Linux, macOS and Windows alike;
 * the obvious alternative, Lanterna, quietly substitutes a Swing window on any
 * machine that has a graphical environment, which is the wrong thing to be
 * running inside a terminal multiplexer.
 */
class Tui(private val store: HabitStore, private val config: Config) {

    private enum class Op { UP, DOWN, LEFT, RIGHT, TODAY, TOGGLE, DETAIL, SYNC, QUIT, REDRAW }

    private var habitRow = 0
    private var dayCol = 0
    private var scroll = 0
    private var end: Timestamp = DateUtils.getTodayWithOffset()
    private var days = 21
    private var detail = false
    private var status: String = ""

    private val habits: List<Habit>
        get() = store.habitList.filter { !it.isArchived }

    fun run() {
        val terminal = TerminalBuilder.builder().system(true).build()
        try {
            terminal.enterRawMode()
            terminal.puts(Capability.enter_ca_mode)
            terminal.puts(Capability.cursor_invisible)
            terminal.flush()
            loop(terminal)
        } finally {
            terminal.puts(Capability.cursor_visible)
            terminal.puts(Capability.exit_ca_mode)
            terminal.flush()
            terminal.close()
        }
    }

    /**
     * Renders one frame as ANSI text, without a terminal.
     *
     * A full-screen UI cannot be exercised by a test that has no keyboard and
     * no tty, which normally means it is the one part of a program nobody ever
     * checks. This makes the layout inspectable: `uhabits dump` prints exactly
     * what [run] would draw.
     */
    fun dump(height: Int, width: Int): List<String> {
        days = Grid.columnsFor(width)
        dayCol = days - 1
        status = statusFor(habits.getOrNull(habitRow))
        return render(height, width).map { it.toAnsi() }
    }

    private fun loop(terminal: Terminal) {
        val display = Display(terminal, true)
        val reader = BindingReader(terminal.reader())
        val keys = keyMap(terminal)

        while (true) {
            days = Grid.columnsFor(terminal.width)
            dayCol = dayCol.coerceIn(0, days - 1)
            status = statusFor(habits.getOrNull(habitRow))
            display.resize(terminal.height, terminal.width)
            display.update(render(terminal.height, terminal.width), 0)

            when (reader.readBinding(keys)) {
                Op.QUIT -> return
                Op.UP -> move(-1)
                Op.DOWN -> move(1)
                Op.LEFT -> if (dayCol > 0) dayCol-- else end = end.minus(days)
                Op.RIGHT -> {
                    val today = DateUtils.getTodayWithOffset()
                    if (dayCol < days - 1) {
                        dayCol++
                    } else if (!end.isNewerThan(today)) {
                        end = if (end.plus(days).isNewerThan(today)) today else end.plus(days)
                    }
                }
                Op.TODAY -> { end = DateUtils.getTodayWithOffset(); dayCol = days - 1 }
                Op.TOGGLE -> toggle(terminal, display)
                Op.DETAIL -> detail = !detail
                Op.SYNC -> runSync()
                Op.REDRAW -> Unit
                null -> Unit
            }
        }
    }

    private fun move(delta: Int) {
        val list = habits
        if (list.isEmpty()) return
        habitRow = (habitRow + delta).coerceIn(0, list.size - 1)
    }

    private fun keyMap(terminal: Terminal): KeyMap<Op> = KeyMap<Op>().apply {
        bind(Op.DOWN, "j", KeyMap.key(terminal, Capability.key_down))
        bind(Op.UP, "k", KeyMap.key(terminal, Capability.key_up))
        bind(Op.LEFT, "h", KeyMap.key(terminal, Capability.key_left))
        bind(Op.RIGHT, "l", KeyMap.key(terminal, Capability.key_right))
        bind(Op.TODAY, "G", "t")
        bind(Op.TOGGLE, " ", "x")
        bind(Op.DETAIL, "\r", "\n")
        bind(Op.SYNC, "s")
        bind(Op.QUIT, "q", KeyMap.esc())
        bind(Op.REDRAW, KeyMap.ctrl('L'))
    }

    // ------------------------------------------------------------- rendering

    private fun selectedTimestamp(): Timestamp = Grid.dayAt(end, days, dayCol)

    private fun statusFor(habit: Habit?): String {
        val date = Grid.longDate(selectedTimestamp())
        if (habit == null) return date
        return "$date   ·   ${Grid.summary(habit)}"
    }

    private fun render(height: Int, width: Int): List<AttributedString> {
        val list = habits
        val lines = mutableListOf<AttributedString>()
        val rule = AttributedString("─".repeat(width), Gruvbox.darkGray)

        lines += title(width)
        lines += rule
        if (detail && list.isNotEmpty()) {
            lines += detailLines(list[habitRow])
        } else {
            lines += headerLines()
            lines += rule
            val bodyHeight = (height - lines.size - 3).coerceAtLeast(1)
            if (habitRow < scroll) scroll = habitRow
            if (habitRow >= scroll + bodyHeight) scroll = habitRow - bodyHeight + 1
            for (i in scroll until minOf(list.size, scroll + bodyHeight)) {
                lines += habitLine(list[i], i == habitRow)
            }
        }

        while (lines.size < height - 2) lines += AttributedString("")
        lines += AttributedString(status.take(width), Gruvbox.aqua)
        lines += keyHints()
        return lines.take(height)
    }

    private fun title(width: Int): AttributedString {
        val sb = AttributedStringBuilder()
        sb.style(Gruvbox.yellow.bold()).append("  loop")
        sb.style(Gruvbox.gray).append("   habits on ")
        sb.style(Gruvbox.fg0).append(store.file.name)
        val folder = config.syncFolder
        sb.style(Gruvbox.gray).append("   sync ")
        if (folder == null) {
            sb.style(Gruvbox.orange).append("not configured")
        } else {
            sb.style(Gruvbox.green).append(folder.name)
        }
        return sb.toAttributedString()
    }

    private fun keyHints(): AttributedString {
        val sb = AttributedStringBuilder()
        val pairs = if (detail) {
            listOf("⏎" to "back", "j/k" to "habit", "s" to "sync", "q" to "quit")
        } else {
            listOf(
                "j/k" to "habit", "h/l" to "day", "␣" to "toggle",
                "⏎" to "detail", "t" to "today", "s" to "sync", "q" to "quit",
            )
        }
        sb.append("  ")
        for ((key, label) in pairs) {
            sb.style(Gruvbox.yellow).append(key)
            sb.style(Gruvbox.gray).append(" $label   ")
        }
        return sb.toAttributedString()
    }

    /**
     * Three header rows: month, day of month, weekday initial.
     *
     * Today's column and the column under the cursor are both called out, so
     * that "which day is this square?" never needs counting -- the answer is
     * also spelled out in full on the status line.
     */
    private fun headerLines(): List<AttributedString> {
        val today = DateUtils.getTodayWithOffset()
        val months = AttributedStringBuilder().append(" ".repeat(Grid.NAME_WIDTH))
        val numbers = AttributedStringBuilder().append(" ".repeat(Grid.NAME_WIDTH))
        val weekdays = AttributedStringBuilder().append(" ".repeat(Grid.NAME_WIDTH))
        var lastMonth = ""

        for (i in 0 until days) {
            val t = Grid.dayAt(end, days, i)
            val isToday = t == today
            val isCursor = i == dayCol
            val month = Grid.monthLabel(t)

            months.style(Gruvbox.orange.bold())
                .append(Grid.pad(if (month != lastMonth) month else "", Grid.COL))
            lastMonth = month

            val numberStyle = when {
                isCursor -> Gruvbox.yellow.bold().inverse()
                isToday -> Gruvbox.yellow.bold()
                else -> Gruvbox.fg0
            }
            numbers.style(numberStyle).append(Grid.pad(Grid.dayLabel(t), Grid.COL))

            val weekdayStyle = if (Grid.isWeekend(t)) Gruvbox.purple else Gruvbox.gray
            weekdays.style(weekdayStyle).append(Grid.pad(Grid.weekdayLabel(t), Grid.COL))
        }
        return listOf(
            months.toAttributedString(),
            numbers.toAttributedString(),
            weekdays.toAttributedString(),
        )
    }

    private fun habitLine(habit: Habit, selected: Boolean): AttributedString {
        val sb = AttributedStringBuilder()
        val width = Grid.NAME_WIDTH
        val name = habit.name.let { if (it.length > width - 3) it.take(width - 4) + "…" else it }
        sb.style(if (selected) Gruvbox.yellow.bold() else Gruvbox.gray)
            .append(if (selected) "▸ " else "  ")
            .append(name.padEnd(width - 2))

        val values = Grid.valuesFor(habit, end, days)
        for (i in 0 until days) {
            val value = values.getOrElse(i) { Entry.UNKNOWN }
            var style = Gruvbox.forValue(habit, value)
            if (selected && i == dayCol) style = style.inverse()
            sb.style(style).append(Grid.pad(Grid.cell(habit, value), Grid.COL))
        }
        return sb.toAttributedString()
    }

    private fun detailLines(habit: Habit): List<AttributedString> {
        val out = mutableListOf<AttributedString>()
        out += AttributedString("  ${habit.name}", Gruvbox.yellow.bold())
        out += AttributedString("  ${Grid.summary(habit)}", Gruvbox.gray)
        if (habit.question.isNotBlank()) {
            out += AttributedString("  \"${habit.question}\"", Gruvbox.fg0)
        }
        if (habit.description.isNotBlank()) {
            out += AttributedString("  ${habit.description}", Gruvbox.gray)
        }
        out += AttributedString("")
        out += headerLines()
        out += habitLine(habit, selected = true)
        out += AttributedString("")
        val best = habit.streaks.getBest(6)
        if (best.isNotEmpty()) {
            out += AttributedString("  Best streaks", Gruvbox.orange)
            for (streak in best) {
                val sb = AttributedStringBuilder()
                sb.style(Gruvbox.green).append("    ${streak.length.toString().padStart(3)} days")
                sb.style(Gruvbox.gray).append("   ending ${formatSyncDate(streak.end.unixTime)}")
                out += sb.toAttributedString()
            }
        }
        return out
    }

    // --------------------------------------------------------------- actions

    private fun toggle(terminal: Terminal, display: Display) {
        val list = habits
        if (list.isEmpty()) return
        val habit = list[habitRow]
        val timestamp = selectedTimestamp()
        val existing = habit.originalEntries.get(timestamp)

        val value = if (habit.isNumerical) {
            promptNumber(terminal, display, habit, timestamp) ?: return
        } else {
            Entry.nextToggleValue(
                existing.value,
                isSkipEnabled = true,
                areQuestionMarksEnabled = true,
            )
        }

        habit.originalEntries.add(Entry(timestamp, value, existing.notes))
        habit.recompute()
    }

    /**
     * A numerical habit has a value, not a state, so there is nothing to cycle
     * through -- it has to be typed. Read straight from the terminal rather
     * than through the key map, since here every character means itself.
     */
    private fun promptNumber(
        terminal: Terminal,
        display: Display,
        habit: Habit,
        timestamp: Timestamp,
    ): Int? {
        val current = habit.originalEntries.get(timestamp).value
        val buffer = StringBuilder(if (current > 0) Grid.cell(habit, current).trim() else "")
        while (true) {
            val unit = habit.unit.ifBlank { "" }
            status = "${habit.name}  ${Grid.longDate(timestamp)}   value: $buffer▏ $unit   (⏎ save, esc cancel)"
            display.resize(terminal.height, terminal.width)
            display.update(render(terminal.height, terminal.width), 0)

            when (val c = terminal.reader().read()) {
                13, 10 -> {
                    val text = buffer.toString().replace(',', '.')
                    if (text.isBlank()) return Entry.UNKNOWN
                    val number = text.toDoubleOrNull() ?: return null
                    return Math.round(number * 1000).toInt()
                }
                27 -> return null
                127, 8 -> if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
                else -> {
                    val ch = c.toChar()
                    if (ch.isDigit() || ch == '.' || ch == ',') buffer.append(ch)
                }
            }
        }
    }

    private fun runSync() {
        val folder = config.syncFolder
        if (folder == null) {
            status = "no sync folder set - run once with: uhabits --sync-folder <dir>"
            return
        }
        try {
            folder.mkdirs()
            store.baseDir.mkdirs()
            val report = SyncEngine(store.habitList, store.modelFactory)
                .sync(
                    FileSyncFolder(folder),
                    FileSyncFolder(store.baseDir),
                    folderId = folder.absolutePath,
                )
            store.recomputeAll()
            habitRow = habitRow.coerceIn(0, (habits.size - 1).coerceAtLeast(0))
            status = when {
                report.abortedEmptyFolder ->
                    "sync folder is empty - refusing to delete; check Syncthing"
                report.isEmpty -> "in sync"
                else -> buildString {
                    append("synced")
                    if (report.createdLocally.isNotEmpty()) append("  +${report.createdLocally.size}")
                    if (report.updatedLocally.isNotEmpty()) append("  ~${report.updatedLocally.size}")
                    if (report.deletedLocally.isNotEmpty()) append("  -${report.deletedLocally.size}")
                    if (report.conflicts.isNotEmpty()) append("  ⚠ ${report.conflicts.size} conflict(s)")
                }
            }
        } catch (e: Exception) {
            status = "sync failed: ${e.message}"
        }
    }
}
