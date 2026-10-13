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

import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.core.sync.FileSyncFolder
import org.isoron.uhabits.core.sync.SyncEngine
import org.isoron.uhabits.core.utils.DateUtils
import org.jline.keymap.BindingReader
import org.jline.keymap.KeyMap
import org.jline.terminal.Attributes
import org.jline.terminal.Size
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import org.jline.utils.InfoCmp.Capability
import org.jline.utils.NonBlockingReader
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * The full-screen terminal client.
 *
 * Keyboard-first in the manner of a calendar app: every action has a single
 * key, a command menu (`ctrl-k` or `:`) finds any of them by name along with
 * any habit, and `?` lists them all. Nothing blocks -- prompts, the command
 * menu and the detail card are modes of one loop, so a resize or a change of
 * day redraws even while one is open.
 *
 * Built on JLine's own full-screen primitives -- raw mode, the alternate
 * screen, and a row-by-row repaint -- rather than on a widget
 * toolkit. JLine drives the real terminal on Linux, macOS and Windows alike;
 * the obvious alternative, Lanterna, quietly substitutes a Swing window on any
 * machine that has a graphical environment.
 *
 * Three rules keep the picture where it belongs, each learnt from it not being
 * there:
 *  - The screen is cleared before the first frame. `smcup` switches to the
 *    alternate screen but xterm and tmux leave the cursor where the shell
 *    prompt was, so anything drawn relative to the cursor lands several rows
 *    down and stays there until something forces a full repaint. Every row is
 *    now written at an absolute position as well.
 *  - No line reaches the last column. Writing there leaves the cursor in a
 *    pending-wrap state that terminals resolve differently, which shifted
 *    every row below it by one.
 *  - No box-drawing characters from the VT100 line set (`─ │ ┌ …`). JLine
 *    translates those into the alternate character set whenever terminfo
 *    advertises one, and under tmux on Linux that came out as rows of `qqqq`.
 */
class Tui(private val store: HabitStore, private val config: Config) {

    private enum class Op {
        UP, DOWN, LEFT, RIGHT, PAGE_UP, PAGE_DOWN, FIRST, LAST,
        PREV_WEEK, NEXT_WEEK, TODAY, GOTO_DATE, WEEK_VIEW, MONTH_VIEW,
        CYCLE, DONE, MISSED, SKIP, CLEAR, VALUE, UNDO, REDO,
        DETAIL, SEARCH, PALETTE, HELP, ADD, RENAME, ARCHIVE, DELETE, SHOW_ARCHIVED,
        SYNC, ESCAPE, REDRAW, QUIT, NEXT_HABIT, PREV_HABIT,
        CHAR, ENTER, BACKSPACE, KILL_LINE, KILL_WORD,
    }

    private enum class View { MONTH, WEEK }

    /** A single-line text prompt on the bottom row. */
    private class Prompt(
        val label: String,
        val hint: String,
        initial: String = "",
        val onChange: ((String) -> Unit)? = null,
        val onCancel: (() -> Unit)? = null,
        val onSubmit: (String) -> Unit,
    ) {
        val text = StringBuilder(initial)
    }

    private class Command(val title: String, val keys: String, val run: () -> Unit)

    private class Palette {
        val query = StringBuilder()
        var selected = 0
    }

    private sealed interface Mode {
        object Normal : Mode
        object Help : Mode
        object Detail : Mode
        class Input(val prompt: Prompt) : Mode
        class Menu(val palette: Palette) : Mode
    }

    private class Change(val habit: Habit, val before: Entry, val after: Entry)

    private class Toast(val text: String, val style: AttributedStyle, val at: Long = System.currentTimeMillis())

    // ----------------------------------------------------------------- state

    private var mode: Mode = Mode.Normal
    private var view = View.MONTH
    private var loading = true
    private var running = true

    private var all: List<Habit> = emptyList()
    private var visible: List<Habit> = emptyList()
    private var filter = ""
    private var showArchived = false
    private var row = 0
    private var scroll = 0
    private var bodyHeight = 10

    private var today: Timestamp = DateUtils.getTodayWithOffset()
    private var cursor: Timestamp = today
    private var end: Timestamp = today

    private val undo = ArrayDeque<Change>()
    private val redo = ArrayDeque<Change>()
    private var toast: Toast? = null

    private var lastSync: Long? = null
    private var syncState = "" // "", "syncing", "failed"

    /** Set while the loop runs, so a long action can show "working" first. */
    private var repaint: () -> Unit = {}

    private val selected: Habit? get() = visible.getOrNull(row)

    // ------------------------------------------------------------ lifecycle

    /**
     * Returns false when this terminal cannot host a full-screen UI.
     *
     * JLine reports a "dumb" terminal when it cannot negotiate cursor control --
     * a plain conhost window without virtual-terminal processing, output piped
     * to a file, or a bare TERM. Drawing to it anyway produces either escape
     * codes printed literally or an apparently empty screen. The caller prints
     * the list instead.
     */
    fun run(): Boolean {
        val terminal = TerminalBuilder.builder().system(true).build()
        if (terminal.type == Terminal.TYPE_DUMB || terminal.type == Terminal.TYPE_DUMB_COLOR) {
            terminal.close()
            return false
        }
        val saved: Attributes = terminal.enterRawMode()
        try {
            // Raw mode in JLine keeps ISIG, so ctrl-c would kill the JVM with
            // the terminal still in raw mode and on the alternate screen. Take
            // it as an ordinary key instead and quit cleanly.
            terminal.attributes = Attributes(terminal.attributes).apply {
                setLocalFlag(Attributes.LocalFlag.ISIG, false)
            }
            terminal.puts(Capability.enter_ca_mode)
            terminal.puts(Capability.keypad_xmit)
            terminal.puts(Capability.cursor_invisible)
            terminal.puts(Capability.clear_screen)
            terminal.flush()
            session(terminal, autoSync = true)
        } finally {
            terminal.puts(Capability.cursor_visible)
            terminal.puts(Capability.keypad_local)
            terminal.puts(Capability.exit_ca_mode)
            terminal.flush()
            terminal.attributes = saved
            terminal.close()
        }
        return true
    }

    /**
     * Renders one frame as ANSI text, without a real terminal.
     *
     * [keys] are fed through the same key maps and handlers the live client
     * uses, on a virtual terminal of the given size, so a sequence of
     * keystrokes can be checked from a script: `uhabits dump 30 100 'jj/rea'`.
     * It never syncs.
     */
    fun dump(height: Int, width: Int, keys: String = ""): List<String> {
        val terminal = TerminalBuilder.builder()
            .system(false)
            .streams(ByteArrayInputStream(keys.toByteArray(Charsets.UTF_8)), OutputStream.nullOutputStream())
            .type("xterm-256color")
            .size(Size(width, height))
            .build()
        terminal.use { session(it, autoSync = false) }
        return render(height, width).map { it.toAnsi() }
    }

    private fun session(terminal: Terminal, autoSync: Boolean) {
        var size = Size(0, 0)
        var onScreen = emptyList<String>()
        repaint = {
            val now = terminal.size
            if (now != size || repaintFull) {
                repaintFull = false
                // A full repaint after a resize: the terminal has reflowed or
                // cropped what was there, so nothing remembered is true.
                size = Size(now.columns, now.rows)
                terminal.puts(Capability.clear_screen)
                onScreen = emptyList()
            }
            onScreen = paint(terminal, render(now.rows, now.columns).map { it.toAnsi(terminal) }, onScreen)
            terminal.flush()
        }
        repaint()

        store.recomputeAll()
        refresh()
        loading = false
        repaint()
        if (autoSync && config.syncFolder != null) runSync()

        val reader = BindingReader(terminal.reader())
        val normalKeys = normalKeys(terminal)
        val textKeys = textKeys(terminal)

        while (running) {
            repaint()
            // Wait in short slices rather than blocking on the read, so that a
            // resize, midnight, or an expiring message still redraws while no
            // key is pressed.
            var next: Int
            while (true) {
                next = reader.peekCharacter(150)
                if (next != NonBlockingReader.READ_EXPIRED) break
                if (tick(terminal, size)) repaint()
            }
            if (next < 0) return
            val keys = if (mode is Mode.Input || mode is Mode.Menu) textKeys else normalKeys
            val op = reader.readBinding(keys) ?: continue
            handle(op, reader.lastBinding ?: "")
        }
    }

    /**
     * Rewrites only the rows that changed, each one whole: cursor to column 0,
     * the row, then clear to end of line.
     *
     * JLine's [org.jline.utils.Display] was used here first. It diffs within a
     * row and patches with insert- and delete-character, and when a label
     * changed length ("today" to "yesterday") it left the top bar reading
     * "mmonth  29abits" under tmux until something forced a full repaint.
     * Whole-row writes with absolute addressing cannot drift, and a changed
     * row is at most a couple of hundred bytes.
     */
    private fun paint(terminal: Terminal, lines: List<String>, before: List<String>): List<String> {
        for ((y, line) in lines.withIndex()) {
            if (before.getOrNull(y) == line) continue
            terminal.puts(Capability.cursor_address, y, 0)
            terminal.writer().write(line)
            terminal.writer().write("\u001b[0m")
            terminal.puts(Capability.clr_eol)
        }
        return lines
    }

    /** True when something on screen has gone stale without a key press. */
    private fun tick(terminal: Terminal, drawn: Size): Boolean {
        var stale = terminal.size != drawn
        val now = DateUtils.getTodayWithOffset()
        if (now != today) {
            // Past midnight: keep following today if that is where we were.
            if (cursor == today) cursor = now
            if (end == today) end = now
            today = now
            stale = true
        }
        val t = toast
        if (t != null && System.currentTimeMillis() - t.at > TOAST_MS) {
            toast = null
            stale = true
        }
        return stale
    }

    // ----------------------------------------------------------------- keys

    private fun normalKeys(terminal: Terminal) = KeyMap<Op>().apply {
        ambiguousTimeout = 120
        // Arrow keys arrive as CSI or SS3 depending on keypad mode, and not
        // every terminal honours keypad_xmit, so both forms are bound.
        bind(Op.DOWN, "j", "\u001b[B", "\u001bOB", cap(terminal, Capability.key_down))
        bind(Op.UP, "k", "\u001b[A", "\u001bOA", cap(terminal, Capability.key_up))
        bind(Op.LEFT, "h", "\u001b[D", "\u001bOD", cap(terminal, Capability.key_left))
        bind(Op.RIGHT, "l", "\u001b[C", "\u001bOC", cap(terminal, Capability.key_right))
        bind(Op.PREV_WEEK, "H", "[", "b")
        bind(Op.NEXT_WEEK, "L", "]", "f")
        bind(Op.PAGE_UP, KeyMap.ctrl('U'), "\u001b[5~", cap(terminal, Capability.key_ppage))
        bind(Op.PAGE_DOWN, KeyMap.ctrl('D'), "\u001b[6~", cap(terminal, Capability.key_npage))
        bind(Op.FIRST, "\u001b[H", "\u001b[1~", "\u001bOH", cap(terminal, Capability.key_home))
        bind(Op.LAST, "G", "\u001b[F", "\u001b[4~", "\u001bOF", cap(terminal, Capability.key_end))
        bind(Op.TODAY, "t")
        bind(Op.GOTO_DATE, "g")
        bind(Op.WEEK_VIEW, "w")
        bind(Op.MONTH_VIEW, "m")
        bind(Op.CYCLE, " ", "x")
        bind(Op.DONE, "y")
        bind(Op.MISSED, "n")
        bind(Op.SKIP, "-")
        bind(Op.CLEAR, "\u007f", "\b", "\u001b[3~")
        bind(Op.VALUE, "e", "=")
        bind(Op.UNDO, "u", KeyMap.ctrl('Z'))
        bind(Op.REDO, "U", KeyMap.ctrl('R'), KeyMap.ctrl('Y'))
        bind(Op.DETAIL, "\r", "\n", "o")
        bind(Op.SEARCH, "/")
        bind(Op.PALETTE, KeyMap.ctrl('K'), ":", KeyMap.ctrl('P'))
        bind(Op.HELP, "?")
        bind(Op.ADD, "a")
        bind(Op.RENAME, "r")
        bind(Op.ARCHIVE, "A")
        bind(Op.DELETE, "D")
        bind(Op.SHOW_ARCHIVED, ".")
        bind(Op.SYNC, "s")
        bind(Op.ESCAPE, KeyMap.esc())
        bind(Op.REDRAW, KeyMap.ctrl('L'))
        bind(Op.QUIT, "q", KeyMap.ctrl('C'))
        bind(Op.NEXT_HABIT, "J", "	")
        bind(Op.PREV_HABIT, "K", "[Z")
    }

    /** A terminfo key, or a sequence nothing sends when this terminal lacks it. */
    private fun cap(terminal: Terminal, c: Capability): String =
        KeyMap.key(terminal, c)?.takeIf { it.isNotEmpty() } ?: "\u0000\u0000"

    private fun textKeys(terminal: Terminal) = KeyMap<Op>().apply {
        ambiguousTimeout = 120
        for (c in 32..126) bind(Op.CHAR, c.toChar().toString())
        unicode = Op.CHAR
        bind(Op.ENTER, "\r", "\n")
        bind(Op.ESCAPE, KeyMap.esc(), KeyMap.ctrl('C'), KeyMap.ctrl('G'))
        bind(Op.BACKSPACE, "\u007f", "\b")
        bind(Op.KILL_LINE, KeyMap.ctrl('U'))
        bind(Op.KILL_WORD, KeyMap.ctrl('W'))
        bind(Op.UP, "\u001b[A", "\u001bOA", KeyMap.ctrl('P'), cap(terminal, Capability.key_up))
        bind(Op.DOWN, "\u001b[B", "\u001bOB", KeyMap.ctrl('N'), "\t", cap(terminal, Capability.key_down))
    }

    // ------------------------------------------------------------- dispatch

    private fun handle(op: Op, typed: String) {
        when (val m = mode) {
            is Mode.Input -> handleInput(m.prompt, op, typed)
            is Mode.Menu -> handleMenu(m.palette, op, typed)
            Mode.Help -> mode = Mode.Normal
            Mode.Detail -> when (op) {
                Op.ESCAPE, Op.DETAIL, Op.QUIT -> mode = Mode.Normal
                Op.DOWN -> pageScroll++
                Op.UP -> pageScroll--
                Op.PAGE_DOWN -> pageScroll += (bodyHeight / 2).coerceAtLeast(1)
                Op.PAGE_UP -> pageScroll -= (bodyHeight / 2).coerceAtLeast(1)
                Op.FIRST -> pageScroll = 0
                Op.LAST -> pageScroll = Int.MAX_VALUE
                Op.NEXT_HABIT -> { moveRow(1); pageScroll = 0 }
                Op.PREV_HABIT -> { moveRow(-1); pageScroll = 0 }
                else -> perform(op)
            }
            Mode.Normal -> perform(op)
        }
    }

    private fun perform(op: Op) {
        when (op) {
            Op.UP, Op.PREV_HABIT -> moveRow(-1)
            Op.DOWN, Op.NEXT_HABIT -> moveRow(1)
            Op.PAGE_UP -> moveRow(-(bodyHeight / 2).coerceAtLeast(1))
            Op.PAGE_DOWN -> moveRow((bodyHeight / 2).coerceAtLeast(1))
            Op.FIRST -> moveRow(-visible.size)
            Op.LAST -> moveRow(visible.size)
            Op.LEFT -> moveDay(-1)
            Op.RIGHT -> moveDay(1)
            Op.PREV_WEEK -> moveDay(-7)
            Op.NEXT_WEEK -> moveDay(7)
            Op.TODAY -> goTo(today)
            Op.GOTO_DATE -> promptDate()
            Op.WEEK_VIEW -> view = View.WEEK
            Op.MONTH_VIEW -> { view = View.MONTH; end = today }
            Op.CYCLE -> selected?.let { h ->
                if (h.isNumerical) promptValue(h) else record(h, nextValue(h))
            }
            Op.DONE -> selected?.let { h ->
                if (h.isNumerical) promptValue(h) else record(h, Entry.YES_MANUAL)
            }
            Op.MISSED -> selected?.let { h -> record(h, if (h.isNumerical) 0 else Entry.NO) }
            Op.SKIP -> selected?.let { h -> record(h, Entry.SKIP) }
            Op.CLEAR -> selected?.let { h -> record(h, Entry.UNKNOWN) }
            Op.VALUE -> selected?.let { h ->
                if (h.isNumerical) promptValue(h) else say("${h.name} is yes-or-no; space cycles it", Gruvbox.gray)
            }
            Op.UNDO -> undoLast()
            Op.REDO -> redoLast()
            Op.DETAIL -> if (selected != null) { mode = Mode.Detail; pageScroll = 0 }
            Op.SEARCH -> promptSearch()
            Op.PALETTE -> mode = Mode.Menu(Palette())
            Op.HELP -> mode = Mode.Help
            Op.ADD -> promptAdd()
            Op.RENAME -> selected?.let(::promptRename)
            Op.ARCHIVE -> selected?.let(::toggleArchive)
            Op.DELETE -> selected?.let(::promptDelete)
            Op.SHOW_ARCHIVED -> {
                showArchived = !showArchived
                refresh()
                say(if (showArchived) "Showing archived habits" else "Hiding archived habits")
            }
            Op.SYNC -> runSync()
            Op.ESCAPE -> when {
                mode == Mode.Detail -> mode = Mode.Normal
                filter.isNotEmpty() -> { filter = ""; refresh() }
                else -> toast = null
            }
            Op.REDRAW -> repaintFull = true
            Op.QUIT -> running = false
            else -> Unit
        }
    }

    private var repaintFull = false
    private var pageScroll = 0

    private fun handleInput(prompt: Prompt, op: Op, typed: String) {
        val text = prompt.text
        when (op) {
            Op.CHAR -> text.append(typed)
            Op.BACKSPACE -> if (text.isNotEmpty()) text.setLength(text.length - 1)
            Op.KILL_LINE -> text.setLength(0)
            Op.KILL_WORD -> text.setLength(text.trimEnd().lastIndexOf(' ').let { if (it < 0) 0 else it + 1 })
            Op.ESCAPE -> { mode = Mode.Normal; prompt.onCancel?.invoke(); return }
            Op.ENTER -> { mode = Mode.Normal; prompt.onSubmit(text.toString().trim()); return }
            else -> return
        }
        prompt.onChange?.invoke(text.toString())
    }

    private fun handleMenu(palette: Palette, op: Op, typed: String) {
        val items = menuItems(palette.query.toString())
        when (op) {
            Op.CHAR -> { palette.query.append(typed); palette.selected = 0 }
            Op.BACKSPACE -> {
                if (palette.query.isEmpty()) mode = Mode.Normal
                else palette.query.setLength(palette.query.length - 1)
                palette.selected = 0
            }
            Op.KILL_LINE, Op.KILL_WORD -> { palette.query.setLength(0); palette.selected = 0 }
            Op.UP -> palette.selected = (palette.selected - 1).coerceAtLeast(0)
            Op.DOWN -> palette.selected = (palette.selected + 1).coerceAtMost((items.size - 1).coerceAtLeast(0))
            Op.ESCAPE -> mode = Mode.Normal
            Op.ENTER -> {
                mode = Mode.Normal
                items.getOrNull(palette.selected)?.run?.invoke()
            }
            else -> Unit
        }
    }

    // ----------------------------------------------------------- navigation

    private fun moveRow(delta: Int) {
        if (visible.isEmpty()) return
        row = (row + delta).coerceIn(0, visible.size - 1)
    }

    private fun moveDay(delta: Int) {
        val target = cursor.plus(delta)
        goTo(if (target.isNewerThan(today)) today else target)
    }

    private fun goTo(t: Timestamp) {
        cursor = if (t.isNewerThan(today)) today else t
        if (view == View.MONTH && cursor == today) end = today
    }

    // ---------------------------------------------------------------- model

    /**
     * Rebuilds the list on screen, keeping the same habit under the cursor
     * where it survives -- after a sync or a filter the row index alone would
     * point at a different habit.
     */
    private fun refresh() {
        val keep = selected?.id
        all = store.habitList.filter { showArchived || !it.isArchived }
        visible = if (filter.isBlank()) all else all.filter { fuzzy(filter, it.name) != null }
        val i = visible.indexOfFirst { it.id == keep }
        row = if (i >= 0) i else row.coerceIn(0, (visible.size - 1).coerceAtLeast(0))
    }

    private fun nextValue(habit: Habit): Int = Entry.nextToggleValue(
        habit.originalEntries.get(cursor).value,
        isSkipEnabled = true,
        areQuestionMarksEnabled = true,
    )

    private fun record(habit: Habit, value: Int) {
        if (cursor.isNewerThan(today)) {
            say("That day hasn't happened yet", Gruvbox.orange)
            return
        }
        val before = habit.originalEntries.get(cursor)
        if (before.value == value) return
        val after = Entry(cursor, value, before.notes)
        apply(habit, after)
        undo.addLast(Change(habit, before, after))
        if (undo.size > 200) undo.removeFirst()
        redo.clear()
        say("${habit.name}  ${describe(habit, value)}  ${shortDate(cursor)}", Gruvbox.fg0, undoHint = true)
    }

    private fun apply(habit: Habit, entry: Entry) {
        habit.originalEntries.add(entry)
        habit.recompute()
    }

    private fun undoLast() {
        val change = undo.removeLastOrNull() ?: return say("Nothing to undo", Gruvbox.gray)
        apply(change.habit, change.before)
        redo.addLast(change)
        reveal(change)
        say("Undid ${change.habit.name} ${shortDate(change.before.timestamp)}", Gruvbox.fg0)
    }

    private fun redoLast() {
        val change = redo.removeLastOrNull() ?: return say("Nothing to redo", Gruvbox.gray)
        apply(change.habit, change.after)
        undo.addLast(change)
        reveal(change)
        say("Redid ${change.habit.name} ${shortDate(change.after.timestamp)}", Gruvbox.fg0)
    }

    /** Puts the cursor back on the cell an undo touched, so it can be seen. */
    private fun reveal(change: Change) {
        val i = visible.indexOfFirst { it.id == change.habit.id }
        if (i >= 0) row = i
        goTo(change.before.timestamp)
    }

    private fun toggleArchive(habit: Habit) {
        habit.isArchived = !habit.isArchived
        store.habitList.update(habit)
        refresh()
        if (habit.isArchived) {
            say("Archived ${habit.name}", Gruvbox.fg0, extra = "  A again or . to show archived")
        } else {
            say("Restored ${habit.name}", Gruvbox.fg0)
        }
    }

    // -------------------------------------------------------------- prompts

    private fun ask(prompt: Prompt) {
        // A prompt opened from the habit page answers over it, and returns there.
        val fromPage = mode == Mode.Detail
        detailUnder = fromPage
        val submit = prompt.onSubmit
        val cancel = prompt.onCancel
        mode = Mode.Input(
            Prompt(
                prompt.label, prompt.hint, prompt.text.toString(), prompt.onChange,
                onCancel = { cancel?.invoke(); if (fromPage) mode = Mode.Detail },
                onSubmit = { submit(it); if (fromPage && mode == Mode.Normal) mode = Mode.Detail },
            ),
        )
    }

    private fun promptSearch() {
        ask(
            Prompt(
                "/",
                "enter keep  esc clear",
                initial = filter,
                onChange = { filter = it; refresh() },
                onCancel = { filter = ""; refresh() },
                onSubmit = {
                    filter = it
                    refresh()
                    if (visible.isEmpty()) say("No habit matches \"$it\"", Gruvbox.orange)
                },
            ),
        )
    }

    private fun promptDate() = ask(
        Prompt("Go to", "2026-09-01  sep 1  12  mon  yesterday  -3") { text ->
            val date = parseDate(text, today)
            if (date == null) say("Couldn't read \"$text\" as a date", Gruvbox.orange) else goTo(date)
        },
    )

    private fun promptValue(habit: Habit) {
        if (cursor.isNewerThan(today)) return say("That day hasn't happened yet", Gruvbox.orange)
        val current = habit.originalEntries.get(cursor).value
        val initial = if (current >= 0) formatNumber(current / 1000.0) else ""
        val unit = habit.unit.ifBlank { "value" }
        ask(
            Prompt("${habit.name}, ${shortDate(cursor)}", "$unit  enter save  empty clears", initial) { text ->
                if (text.isBlank()) {
                    record(habit, Entry.UNKNOWN)
                } else {
                    val number = text.replace(',', '.').toDoubleOrNull()
                    if (number == null || number < 0) {
                        say("\"$text\" isn't a number", Gruvbox.orange)
                    } else {
                        record(habit, Math.round(number * 1000).toInt())
                    }
                }
            },
        )
    }

    private fun promptAdd() = ask(
        Prompt("New habit", "a name  enter create") { name ->
            if (name.isBlank()) return@Prompt
            val habit = store.modelFactory.buildHabit()
            habit.name = name
            store.habitList.add(habit)
            habit.recompute()
            filter = ""
            refresh()
            row = visible.indexOfFirst { it.id == habit.id }.coerceAtLeast(0)
            say("Added $name", Gruvbox.green, extra = "  s to sync it")
        },
    )

    private fun promptRename(habit: Habit) = ask(
        Prompt("Rename", "enter save", habit.name) { name ->
            if (name.isBlank() || name == habit.name) return@Prompt
            val old = habit.name
            habit.name = name
            store.habitList.update(habit)
            refresh()
            say("Renamed $old to $name", Gruvbox.fg0)
        },
    )

    private fun promptDelete(habit: Habit) {
        val days = habit.originalEntries.getKnown().size
        ask(
            Prompt("Delete ${habit.name} and its $days days, on every device?", "type yes") { answer ->
                if (!answer.equals("yes", ignoreCase = true)) return@Prompt say("Kept ${habit.name}", Gruvbox.gray)
                DeleteHabitsCommand(store.habitList, listOf(habit)).run()
                undo.removeAll { it.habit.id == habit.id }
                redo.removeAll { it.habit.id == habit.id }
                refresh()
                say("Deleted ${habit.name}", Gruvbox.red, extra = "  s to sync it")
            },
        )
    }

    // -------------------------------------------------------------- sync

    private fun runSync() {
        val folder = config.syncFolder
        if (folder == null) {
            say("No sync folder yet. Run once: uhabits --sync-folder <dir> sync", Gruvbox.orange)
            return
        }
        syncState = "syncing"
        repaint()
        try {
            folder.mkdirs()
            store.baseDir.mkdirs()
            val report = SyncEngine(store.habitList, store.modelFactory)
                .sync(
                    FileSyncFolder(folder),
                    FileSyncFolder(store.baseDir),
                    folderId = folder.absolutePath,
                    localStamp = store::stamp,
                )
            store.recomputeAll()
            refresh()
            lastSync = System.currentTimeMillis()
            syncState = ""
            when {
                report.abortedEmptyFolder -> {
                    syncState = "failed"
                    say("Sync folder is empty, so nothing was deleted. Check Syncthing.", Gruvbox.red)
                }
                report.isEmpty -> Unit
                else -> say(
                    buildString {
                        append("Synced")
                        if (report.createdLocally.isNotEmpty()) append("  +${report.createdLocally.size} new")
                        if (report.updatedLocally.isNotEmpty()) append("  ${report.updatedLocally.size} updated")
                        if (report.deletedLocally.isNotEmpty()) append("  ${report.deletedLocally.size} removed")
                        if (report.conflicts.isNotEmpty()) append("  ${report.conflicts.size} conflicts kept newest")
                    },
                    Gruvbox.green,
                )
            }
        } catch (e: Exception) {
            syncState = "failed"
            say("Sync failed: ${e.message}", Gruvbox.red)
        }
    }

    // --------------------------------------------------------- command menu

    private fun commands(): List<Command> = listOf(
        Command("Check / cycle the selected day", "space", { perform(Op.CYCLE) }),
        Command("Mark done", "y", { perform(Op.DONE) }),
        Command("Mark missed", "n", { perform(Op.MISSED) }),
        Command("Skip day", "-", { perform(Op.SKIP) }),
        Command("Clear day", "bksp", { perform(Op.CLEAR) }),
        Command("Enter a value", "e", { perform(Op.VALUE) }),
        Command("Undo", "u", { perform(Op.UNDO) }),
        Command("Redo", "U", { perform(Op.REDO) }),
        Command("Go to today", "t", { perform(Op.TODAY) }),
        Command("Go to date...", "g", { perform(Op.GOTO_DATE) }),
        Command("Previous week", "H", { perform(Op.PREV_WEEK) }),
        Command("Next week", "L", { perform(Op.NEXT_WEEK) }),
        Command("Week view", "w", { perform(Op.WEEK_VIEW) }),
        Command("Month view", "m", { perform(Op.MONTH_VIEW) }),
        Command("Open habit page (stats, charts, calendar)", "enter", { perform(Op.DETAIL) }),
        Command("Search habits", "/", { perform(Op.SEARCH) }),
        Command("New habit...", "a", { perform(Op.ADD) }),
        Command("Rename habit...", "r", { perform(Op.RENAME) }),
        Command(if (selected?.isArchived == true) "Unarchive habit" else "Archive habit", "A", { perform(Op.ARCHIVE) }),
        Command("Delete habit...", "D", { perform(Op.DELETE) }),
        Command(if (showArchived) "Hide archived habits" else "Show archived habits", ".", { perform(Op.SHOW_ARCHIVED) }),
        Command("Sync now", "s", { perform(Op.SYNC) }),
        Command("Keyboard shortcuts", "?", { perform(Op.HELP) }),
        Command("Quit", "q", { perform(Op.QUIT) }),
    )

    /** Commands, then habits to jump to -- both ranked by how well they match. */
    private fun menuItems(query: String): List<Command> {
        val habits = store.habitList.filter { showArchived || !it.isArchived }.map { habit ->
            Command(habit.name, "habit") {
                filter = ""
                refresh()
                row = visible.indexOfFirst { it.id == habit.id }.coerceAtLeast(0)
            }
        }
        if (query.isBlank()) return commands() + habits
        return (commands() + habits)
            .mapNotNull { c -> fuzzy(query, c.title)?.let { c to it } }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    // ------------------------------------------------------------ rendering

    private fun render(height: Int, fullWidth: Int): List<AttributedString> {
        // One column short of the edge, always. See the class comment.
        val width = (fullWidth - 1).coerceAtLeast(10)
        val lines = mutableListOf<AttributedString>()

        if (loading) {
            lines += topBar(width)
            while (lines.size < height / 2) lines += AttributedString.EMPTY
            lines += centered("Loading habits...", width, Gruvbox.gray)
            while (lines.size < height) lines += AttributedString.EMPTY
            return lines.take(height)
        }

        val page = selected?.takeIf { mode == Mode.Detail || (mode as? Mode.Input)?.let { detailUnder } == true }
        if (page != null) return pageFrame(page, height, width)
        detailUnder = false

        val layout = layout(width)
        lines += topBar(width)
        lines += rule(width)
        lines += header(layout)
        lines += rule(width)

        val footer = 2
        bodyHeight = (height - lines.size - footer).coerceAtLeast(1)
        if (row < scroll) scroll = row
        if (row >= scroll + bodyHeight) scroll = row - bodyHeight + 1
        scroll = scroll.coerceIn(0, (visible.size - bodyHeight).coerceAtLeast(0))

        if (visible.isEmpty()) {
            lines += AttributedString.EMPTY
            lines += when {
                filter.isNotEmpty() -> centered("No habit matches \"$filter\"   esc to clear", width, Gruvbox.gray)
                else -> centered("No habits yet.  a to add one, s to sync", width, Gruvbox.gray)
            }
        } else {
            for (i in scroll until minOf(visible.size, scroll + bodyHeight)) {
                lines += habitLine(visible[i], i == row, layout)
            }
        }
        while (lines.size < height - footer) lines += AttributedString.EMPTY
        lines += statusLine(width)
        lines += bottomLine(width)

        when (val m = mode) {
            Mode.Help -> overlayCentered(lines, helpCard(width), width, height)
            is Mode.Menu -> overlay(lines, menuCard(m.palette, width, height), top = 3, width = width)
            else -> Unit
        }
        return lines.take(height).map { clip(it, width) }
    }

    /** Set while a prompt (a numeric value, say) is open over the habit page. */
    private var detailUnder = false

    private fun pageFrame(habit: Habit, height: Int, width: Int): List<AttributedString> {
        val lines = mutableListOf<AttributedString>()
        lines += topBar(width)
        val body = HabitPage(habit, today, cursor, Calendar.getInstance().firstDayOfWeek).render(width)
        val footer = 2
        bodyHeight = (height - 1 - footer).coerceAtLeast(1)
        pageScroll = pageScroll.coerceIn(0, (body.size - bodyHeight).coerceAtLeast(0))
        lines += body.drop(pageScroll).take(bodyHeight)
        while (lines.size < height - footer) lines += AttributedString.EMPTY
        lines += statusLine(width)
        lines += bottomLine(width)
        return lines.take(height).map { clip(it, width) }
    }

    private class Layout(val nameWidth: Int, val col: Int, val days: Int, val end: Timestamp) {
        fun dayAt(i: Int): Timestamp = end.minus(days - 1 - i)
    }

    private fun layout(width: Int): Layout {
        val longest = all.maxOfOrNull { columns(it.name) } ?: 10
        val nameWidth = (longest + 3).coerceIn(14, 28).coerceAtMost((width / 3).coerceAtLeast(12))
        val room = width - nameWidth - SCORE_WIDTH
        return when (view) {
            View.WEEK -> {
                val col = (room / 7).coerceIn(3, 10)
                val first = startOfWeek(cursor)
                Layout(nameWidth, col, 7, first.plus(6))
            }
            View.MONTH -> {
                val days = (room / 3).coerceIn(1, 120)
                // Slide the window only as far as it takes to keep the cursor
                // on screen, the way a text editor scrolls.
                if (end.isNewerThan(today)) end = today
                if (cursor.isNewerThan(end)) end = cursor
                if (cursor.isOlderThan(end.minus(days - 1))) end = cursor.plus(days - 1)
                if (end.isNewerThan(today)) end = today
                Layout(nameWidth, 3, days, end)
            }
        }
    }

    private fun topBar(width: Int): AttributedString {
        val left = AttributedStringBuilder()
        left.style(Gruvbox.yellow.bold()).append(" loop")
        left.style(Gruvbox.darkGray).append("  ")
        left.style(Gruvbox.fg0.bold()).append(Grid.longDate(cursor))
        val offset = cursor.daysUntil(today)
        val relative = when (offset) {
            0 -> "today"
            1 -> "yesterday"
            else -> "$offset days ago"
        }
        left.style(if (offset == 0) Gruvbox.aqua else Gruvbox.gray).append("  $relative")

        val right = AttributedStringBuilder()
        val sync = AttributedStringBuilder()
        if (filter.isNotEmpty()) right.style(Gruvbox.purple).append("/$filter  ")
        if (showArchived) right.style(Gruvbox.gray).append("+archived  ")
        right.style(Gruvbox.gray).append(if (view == View.WEEK) "week" else "month")
        right.style(Gruvbox.darkGray).append("  ")
        right.style(Gruvbox.gray).append("${visible.size} habits")
        right.style(Gruvbox.darkGray).append("  ")
        sync.run { when {
            config.syncFolder == null -> style(Gruvbox.orange).append("sync off")
            syncState == "syncing" -> style(Gruvbox.yellow).append("syncing...")
            syncState == "failed" -> style(Gruvbox.red).append("sync failed")
            lastSync == null -> style(Gruvbox.gray).append("not synced")
            else -> style(Gruvbox.green).append("synced ${ago(lastSync!!)}")
        } }
        sync.append(" ")
        // On a narrow terminal the sync state is the one worth keeping.
        val full = right.append(sync.toAttributedString()).toAttributedString()
        val fits = left.columnLength() + full.columnLength() < width
        return spread(left.toAttributedString(), if (fits) full else sync.toAttributedString(), width)
    }

    private fun header(l: Layout): List<AttributedString> {
        val months = AttributedStringBuilder().append(" ".repeat(l.nameWidth + SCORE_WIDTH))
        val numbers = AttributedStringBuilder().append(" ".repeat(l.nameWidth + SCORE_WIDTH))
        val weekdays = AttributedStringBuilder().append(" ".repeat(l.nameWidth + SCORE_WIDTH))
        numbers.style(Gruvbox.darkGray)
        var lastMonth = ""
        for (i in 0 until l.days) {
            val t = l.dayAt(i)
            val cal = Grid.calendarAt(t)
            val month = Grid.monthLabel(t)
            val showMonth = month != lastMonth
            lastMonth = month
            months.style(Gruvbox.orange.bold()).append(cellText(if (showMonth) month else "", l.col, left = true))

            val future = t.isNewerThan(today)
            val numberStyle = when {
                t == cursor -> Gruvbox.yellow.bold().inverse()
                t == today -> Gruvbox.yellow.bold()
                future -> Gruvbox.darkGray
                else -> Gruvbox.fg0
            }
            numbers.style(numberStyle).append(cellText(cal.get(Calendar.DAY_OF_MONTH).toString(), l.col))

            val weekday = if (l.col >= 5) {
                cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US) ?: ""
            } else {
                Grid.weekdayLabel(t)
            }
            val weekdayStyle = when {
                t == today -> Gruvbox.yellow
                Grid.isWeekend(t) -> Gruvbox.purple
                else -> Gruvbox.gray
            }
            weekdays.style(weekdayStyle).append(cellText(weekday, l.col))
        }
        return listOf(months.toAttributedString(), numbers.toAttributedString(), weekdays.toAttributedString())
    }

    private fun habitLine(habit: Habit, isSelected: Boolean, l: Layout): AttributedString {
        val sb = AttributedStringBuilder()
        val nameStyle = when {
            isSelected -> Gruvbox.yellow.bold()
            habit.isArchived -> Gruvbox.darkGray
            else -> Gruvbox.fg0
        }
        sb.style(Gruvbox.yellow).append(if (isSelected) " ▌" else "  ")
        sb.style(nameStyle).append(fit(habit.name, l.nameWidth - 3)).append(" ")

        val score = Math.round(habit.scores[today].value * 100).toInt()
        val scoreStyle = when {
            score >= 75 -> Gruvbox.green
            score >= 40 -> Gruvbox.yellow
            else -> Gruvbox.gray
        }
        sb.style(scoreStyle).append("$score%".padStart(SCORE_WIDTH - 1)).append(" ")

        val values = Grid.valuesFor(habit, l.end, l.days)
        for (i in 0 until l.days) {
            val t = l.dayAt(i)
            if (t.isNewerThan(today)) {
                sb.style(Gruvbox.darkGray).append(cellText("", l.col))
                continue
            }
            val value = values.getOrElse(i) { Entry.UNKNOWN }
            var style = Gruvbox.forValue(habit, value)
            val text = if (habit.isNumerical && value >= 0) compactNumber(value / 1000.0, l.col - 1) else Grid.cell(habit, value)
            if (isSelected && t == cursor) style = style.inverse()
            sb.style(style).append(cellText(text, l.col))
        }
        return sb.toAttributedString()
    }

    private fun statusLine(width: Int): AttributedString {
        val t = toast
        if (t != null) {
            val sb = AttributedStringBuilder()
            sb.style(t.style).append(" ${t.text}")
            return sb.toAttributedString()
        }
        val habit = selected ?: return AttributedString.EMPTY
        val sb = AttributedStringBuilder()
        val value = habit.computedEntries.get(cursor).value
        sb.style(Gruvbox.fg0.bold()).append(" ${habit.name}")
        sb.style(Gruvbox.gray).append("  ${shortDate(cursor)} ")
        sb.style(Gruvbox.forValue(habit, value)).append(describe(habit, value))
        sb.style(Gruvbox.darkGray).append("   ")
        sb.style(Gruvbox.gray).append(frequencyText(habit))
        val current = currentStreak(habit)
        if (current > 0) {
            sb.style(Gruvbox.darkGray).append("  ")
            sb.style(Gruvbox.aqua).append("streak ${current}d")
        }
        if (habit.question.isNotBlank() && width > 100) {
            sb.style(Gruvbox.darkGray).append("  ")
            sb.style(Gruvbox.gray).append(habit.question)
        }
        return sb.toAttributedString()
    }

    private fun bottomLine(width: Int): AttributedString {
        val m = mode
        if (m is Mode.Input) {
            val p = m.prompt
            val sb = AttributedStringBuilder()
            sb.style(Gruvbox.yellow.bold()).append(" ${p.label} ")
            sb.style(Gruvbox.fg0).append(p.text.toString())
            sb.style(Gruvbox.yellow).append("▏")
            val hint = AttributedString("${p.hint} ", Gruvbox.darkGray)
            return spread(sb.toAttributedString(), hint, width)
        }
        val pairs = when (m) {
            Mode.Detail -> listOf(
                "J/K" to "next/prev habit", "j/k" to "scroll", "h/l H/L" to "day/week",
                "space" to "check", "g" to "go to date", "esc" to "back",
            )
            else -> listOf(
                "space" to "check", "hjkl" to "move", "t" to "today", "u" to "undo",
                "/" to "search", "enter" to "stats", "^K" to "commands", "?" to "keys", "q" to "quit",
            )
        }
        val sb = AttributedStringBuilder().append(" ")
        for ((key, label) in pairs) {
            val piece = columns(key) + columns(label) + 3
            if (sb.columnLength() + piece > width) break
            sb.style(Gruvbox.yellow).append(key)
            sb.style(Gruvbox.gray).append(" $label  ")
        }
        return sb.toAttributedString()
    }

    // --------------------------------------------------------------- cards

    private fun helpCard(width: Int): List<AttributedString> {
        val groups = listOf(
            "Move" to listOf(
                "j k  ↑ ↓" to "habit", "h l  ← →" to "day", "H L  [ ]" to "week",
                "t" to "today", "g" to "go to date", "^U ^D" to "half page", "G" to "last habit",
            ),
            "Record" to listOf(
                "space x" to "cycle", "y" to "done", "n" to "missed", "-" to "skip",
                "bksp" to "clear", "e" to "type a value", "u  U" to "undo  redo",
            ),
            "View" to listOf(
                "w" to "week", "m" to "month", "enter" to "habit page", "J K" to "next/prev habit",
                "/" to "search", "." to "archived",
            ),
            "Habits" to listOf(
                "a" to "new", "r" to "rename", "A" to "archive", "D" to "delete",
            ),
            "App" to listOf(
                "^K  :" to "commands", "s" to "sync", "^L" to "redraw", "q" to "quit",
            ),
        )
        val cardWidth = minOf(width - 4, 76)
        val card = Card(cardWidth)
        card.title("Keyboard shortcuts")
        card.blank()
        val twoColumns = cardWidth >= 64
        if (twoColumns) {
            val left = groups.take(2).flatMap(::groupLines)
            val right = groups.drop(2).flatMap(::groupLines)
            val half = (cardWidth - 4) / 2
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrNull(i) ?: emptyList()
                val r = right.getOrNull(i) ?: emptyList()
                card.line(l.padTo(half) + r)
            }
        } else {
            for (line in groups.flatMap(::groupLines)) card.line(line)
        }
        card.blank()
        card.line(listOf("any key to close" to Gruvbox.darkGray))
        return card.lines
    }

    private fun groupLines(group: Pair<String, List<Pair<String, String>>>): List<List<Pair<String, AttributedStyle>>> {
        val out = mutableListOf<List<Pair<String, AttributedStyle>>>()
        out += listOf(group.first to Gruvbox.orange.bold())
        for ((key, label) in group.second) out += listOf("  " + key.padEnd(10) to Gruvbox.yellow, label to Gruvbox.fg0)
        out += emptyList<Pair<String, AttributedStyle>>()
        return out
    }

    private fun List<Pair<String, AttributedStyle>>.padTo(cols: Int): List<Pair<String, AttributedStyle>> {
        val used = sumOf { columns(it.first) }
        return this + (" ".repeat((cols - used).coerceAtLeast(0)) to Gruvbox.fg0)
    }

    private fun menuCard(palette: Palette, width: Int, height: Int): List<AttributedString> {
        val cardWidth = minOf(width - 4, 64)
        val items = menuItems(palette.query.toString())
        palette.selected = palette.selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val card = Card(cardWidth)
        card.line(
            listOf(
                "> " to Gruvbox.yellow.bold(),
                palette.query.toString() to Gruvbox.fg0,
                "▏" to Gruvbox.yellow,
            ),
        )
        card.blank()
        val room = (height - 10).coerceIn(3, 14)
        val first = (palette.selected - room + 1).coerceAtLeast(0)
        val shown = items.drop(first).take(room)
        if (shown.isEmpty()) card.line(listOf("nothing matches" to Gruvbox.gray))
        for ((i, item) in shown.withIndex()) {
            val active = first + i == palette.selected
            val keyStyle = if (item.keys == "habit") Gruvbox.purple else Gruvbox.yellow
            card.row(
                left = listOf((if (active) "▌" else " ") + item.title to if (active) Gruvbox.yellow.bold() else Gruvbox.fg0),
                right = listOf(item.keys to keyStyle),
                highlight = active,
            )
        }
        card.blank()
        card.line(listOf("↑↓ choose   enter run   esc close" to Gruvbox.darkGray))
        return card.lines
    }

    /**
     * A floating panel drawn with a background colour rather than a border:
     * borders need exactly the line-drawing characters that come out as `qqqq`
     * under tmux, and a filled panel reads as a card at any size anyway.
     */
    private class Card(val width: Int) {
        val lines = mutableListOf<AttributedString>()

        init {
            blank()
        }

        fun blank() = line(emptyList())

        fun title(text: String) = line(listOf(text to Gruvbox.yellow.bold()))

        fun line(parts: List<Pair<String, AttributedStyle>>) = row(parts, emptyList(), highlight = false)

        fun row(left: List<Pair<String, AttributedStyle>>, right: List<Pair<String, AttributedStyle>>, highlight: Boolean) {
            val bg = if (highlight) SELECTION_BG else PANEL_BG
            val sb = AttributedStringBuilder()
            sb.style(AttributedStyle.DEFAULT.background(bg)).append("  ")
            val inner = width - 4
            val rightWidth = right.sumOf { columns(it.first) }
            val leftRoom = (inner - rightWidth - if (rightWidth > 0) 1 else 0).coerceAtLeast(0)
            var used = 0
            for ((text, style) in left) {
                if (used >= leftRoom) break
                val clipped = fit(text, leftRoom - used, pad = false)
                sb.style(style.background(bg)).append(clipped)
                used += columns(clipped)
            }
            sb.style(AttributedStyle.DEFAULT.background(bg)).append(" ".repeat((inner - used - rightWidth).coerceAtLeast(0)))
            for ((text, style) in right) sb.style(style.background(bg)).append(text)
            sb.style(AttributedStyle.DEFAULT.background(bg)).append("  ")
            lines += sb.toAttributedString()
        }
    }

    private fun overlayCentered(base: MutableList<AttributedString>, card: List<AttributedString>, width: Int, height: Int) {
        val top = ((height - card.size) / 2).coerceAtLeast(1)
        overlay(base, card, top, width)
    }

    private fun overlay(base: MutableList<AttributedString>, card: List<AttributedString>, top: Int, width: Int) {
        val cardWidth = card.maxOfOrNull { it.columnLength() } ?: return
        val left = ((width - cardWidth) / 2).coerceAtLeast(0)
        for ((i, line) in card.withIndex()) {
            val y = top + i
            if (y !in base.indices) continue
            val under = base[y]
            val sb = AttributedStringBuilder()
            val before = under.columnSubSequence(0, minOf(left, under.columnLength()))
            sb.append(before)
            sb.append(" ".repeat(left - before.columnLength()))
            sb.append(line)
            if (under.columnLength() > left + cardWidth) {
                sb.append(under.columnSubSequence(left + cardWidth, under.columnLength()))
            }
            base[y] = sb.toAttributedString()
        }
    }

    // -------------------------------------------------------------- helpers

    private fun rule(width: Int) = AttributedString("━".repeat(width), Gruvbox.rule)

    private fun centered(text: String, width: Int, style: AttributedStyle) =
        AttributedString(" ".repeat(((width - columns(text)) / 2).coerceAtLeast(0)) + text, style)

    /** Left and right pieces on one line, the right one flush with the edge. */
    private fun spread(left: AttributedString, right: AttributedString, width: Int): AttributedString {
        val gap = width - left.columnLength() - right.columnLength()
        if (gap < 1) return left
        return AttributedStringBuilder().append(left).append(" ".repeat(gap)).append(right).toAttributedString()
    }

    private fun clip(line: AttributedString, width: Int): AttributedString =
        if (line.columnLength() <= width) line else line.columnSubSequence(0, width)

    private fun cellText(text: String, col: Int, left: Boolean = false): String {
        val clipped = fit(text, col, pad = false)
        val room = col - columns(clipped)
        return when {
            left -> clipped + " ".repeat(room)
            col <= 3 -> " ".repeat(room) + clipped
            else -> " ".repeat(room - room / 2) + clipped + " ".repeat(room / 2)
        }
    }

    private fun compactNumber(v: Double, room: Int): String {
        val plain = formatNumber(v)
        if (plain.length <= room) return plain
        val rounded = Math.round(v).toString()
        if (rounded.length <= room) return rounded
        return if (v >= 1000) "${Math.round(v / 1000)}k".takeLast(room) else "9".repeat(room)
    }

    private fun numericGlyph(habit: Habit, value: Int): String = when {
        value < 0 -> "·"
        habit.targetType == NumericalHabitType.AT_MOST ->
            if (value / 1000.0 <= habit.targetValue) "✓" else "×"
        value / 1000.0 >= habit.targetValue -> "✓"
        value > 0 -> "~"
        else -> "×"
    }

    private fun describe(habit: Habit, value: Int): String = when {
        habit.isNumerical && value >= 0 -> "${formatNumber(value / 1000.0)} ${habit.unit}".trim()
        value == Entry.YES_MANUAL -> "done"
        value == Entry.YES_AUTO -> "credited"
        value == Entry.NO -> "missed"
        value == Entry.SKIP -> "skipped"
        else -> "nothing recorded"
    }

    private fun frequencyText(habit: Habit): String = habit.frequency.let {
        when {
            it.numerator == 1 && it.denominator == 1 -> "every day"
            it.denominator == 7 -> "${it.numerator}x a week"
            it.denominator in 28..31 -> "${it.numerator}x a month"
            it.numerator == 1 -> "every ${it.denominator} days"
            else -> "${it.numerator} in ${it.denominator} days"
        }
    }

    /** The streak still running: one that reaches today, or yesterday if today is still open. */
    private fun currentStreak(habit: Habit): Int {
        val yesterday = today.minus(1)
        return habit.streaks.getBest(10_000)
            .firstOrNull { it.end == today || it.end == yesterday }
            ?.length ?: 0
    }

    private fun startOfWeek(t: Timestamp): Timestamp {
        val firstDay = Calendar.getInstance().firstDayOfWeek
        val weekday = Grid.calendarAt(t).get(Calendar.DAY_OF_WEEK)
        return t.minus((weekday - firstDay + 7) % 7)
    }

    private fun shortDate(t: Timestamp): String {
        val cal = Grid.calendarAt(t)
        val weekday = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US)
        val sameYear = cal.get(Calendar.YEAR) == Grid.calendarAt(today).get(Calendar.YEAR)
        val year = if (sameYear) "" else " ${cal.get(Calendar.YEAR)}"
        return "$weekday ${Grid.monthLabel(t)} ${cal.get(Calendar.DAY_OF_MONTH)}$year"
    }

    private fun say(
        text: String,
        style: AttributedStyle = Gruvbox.fg0,
        undoHint: Boolean = false,
        extra: String = "",
    ) {
        toast = Toast(text + (if (undoHint) "   u to undo" else "") + extra, style)
    }

    companion object {
        private const val SCORE_WIDTH = 5
        private const val TOAST_MS = 4_000L
        private const val PANEL_BG = 237
        private const val SELECTION_BG = 239

        fun columns(text: String): Int = AttributedString(text).columnLength()

        /** Truncates to [cols] terminal columns with an ellipsis, optionally padding. */
        fun fit(text: String, cols: Int, pad: Boolean = true): String {
            if (cols <= 0) return ""
            val s = AttributedString(text)
            val out = if (s.columnLength() <= cols) text else s.columnSubSequence(0, cols - 1).toString() + "…"
            return if (pad) out + " ".repeat((cols - columns(out)).coerceAtLeast(0)) else out
        }

        fun formatNumber(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

        fun ago(millis: Long): String {
            val seconds = (System.currentTimeMillis() - millis) / 1000
            return when {
                seconds < 60 -> "just now"
                seconds < 3600 -> "${seconds / 60}m ago"
                seconds < 86_400 -> "${seconds / 3600}h ago"
                else -> "${seconds / 86_400}d ago"
            }
        }

        /** Subsequence match; higher is better, null is no match. */
        fun fuzzy(query: String, text: String): Int? {
            val q = query.lowercase().trim()
            if (q.isEmpty()) return 0
            val t = text.lowercase()
            val at = t.indexOf(q)
            if (at >= 0) return 1000 - at * 4 - t.length + (if (at == 0 || t[at - 1] == ' ') 50 else 0)
            var from = 0
            var last = -2
            var score = 0
            for (c in q) {
                if (c == ' ') continue
                val found = t.indexOf(c, from)
                if (found < 0) return null
                score += when {
                    found == last + 1 -> 6
                    found == 0 || t[found - 1] == ' ' -> 4
                    else -> 0
                }
                last = found
                from = found + 1
            }
            return score - t.length / 4
        }

        /**
         * Reads the kinds of dates a person types: `2026-09-01`, `9/1`, `sep 1`,
         * `1 september`, `12` (that day of this month, or last month if it has
         * not come yet), `mon` (the most recent Monday), `yesterday`, `-3`.
         */
        fun parseDate(input: String, today: Timestamp): Timestamp? {
            val now = LocalDate.ofEpochDay(today.unixTime / Timestamp.DAY_LENGTH)
            val text = input.trim().lowercase()
            fun ts(d: LocalDate) = Timestamp(d.toEpochDay() * Timestamp.DAY_LENGTH)
            fun notFuture(d: LocalDate, back: (LocalDate) -> LocalDate) = if (d.isAfter(now)) back(d) else d

            if (text.isEmpty() || text == "today" || text == "t") return ts(now)
            if (text == "yesterday" || text == "y") return ts(now.minusDays(1))
            Regex("^([+-])\\s*(\\d+)d?$").find(text)?.let {
                val n = it.groupValues[2].toLong()
                return ts(if (it.groupValues[1] == "-") now.minusDays(n) else now.plusDays(n))
            }
            Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(text)?.let {
                val (y, m, d) = it.destructured
                return runCatching { ts(LocalDate.of(y.toInt(), m.toInt(), d.toInt())) }.getOrNull()
            }
            Regex("^(\\d{1,2})[/.-](\\d{1,2})$").find(text)?.let {
                val (m, d) = it.destructured
                return runCatching {
                    ts(notFuture(LocalDate.of(now.year, m.toInt(), d.toInt())) { it.minusYears(1) })
                }.getOrNull()
            }
            Regex("^(\\d{1,2})$").find(text)?.let {
                val d = it.groupValues[1].toInt()
                return runCatching {
                    ts(notFuture(now.withDayOfMonth(d)) { it.minusMonths(1).withDayOfMonth(d) })
                }.getOrNull()
            }
            DayOfWeek.entries.firstOrNull { day ->
                val name = day.getDisplayName(TextStyle.FULL, Locale.US).lowercase()
                text.length >= 2 && name.startsWith(text)
            }?.let { day ->
                var d = now
                while (d.dayOfWeek != day) d = d.minusDays(1)
                return ts(d)
            }
            val months = (1..12).associateBy { m ->
                java.time.Month.of(m).getDisplayName(TextStyle.FULL, Locale.US).lowercase()
            }
            fun month(word: String) = months.entries.firstOrNull { word.length >= 3 && it.key.startsWith(word) }?.value
            Regex("^([a-z]+)\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?$").find(text)?.let {
                val m = month(it.groupValues[1]) ?: return null
                return dateOf(it.groupValues[3], m, it.groupValues[2].toInt(), now)?.let(::ts)
            }
            Regex("^(\\d{1,2})\\s+([a-z]+)(?:\\s+(\\d{4}))?$").find(text)?.let {
                val m = month(it.groupValues[2]) ?: return null
                return dateOf(it.groupValues[3], m, it.groupValues[1].toInt(), now)?.let(::ts)
            }
            return null
        }

        private fun dateOf(year: String, month: Int, day: Int, now: LocalDate): LocalDate? = runCatching {
            if (year.isNotEmpty()) return@runCatching LocalDate.of(year.toInt(), month, day)
            val d = LocalDate.of(now.year, month, day)
            if (d.isAfter(now)) d.minusYears(1) else d
        }.getOrNull()
    }
}
