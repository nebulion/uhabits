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
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.core.sync.FileSyncFolder
import org.isoron.uhabits.core.sync.SyncEngine
import org.isoron.uhabits.core.sync.SyncReport
import org.isoron.uhabits.core.utils.DateUtils
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CountDownLatch
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JToolBar
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader

/**
 * The desktop window.
 *
 * Swing rather than a modern toolkit, and deliberately. It is part of the JDK,
 * so this adds no dependency to resolve, no plugin to pin against the Kotlin
 * version, and nothing that can fail to download -- the window either opens or
 * the JVM is broken. What it draws is a table of habits by days, which is
 * exactly the shape `JTable` exists for, so the whole screen is a table model
 * and a cell renderer.
 *
 * Everything underneath is shared with the terminal client: the same
 * [HabitStore], the same [SyncEngine], the same [Grid] for deciding what a cell
 * says. Adding a screen here means adding a panel, not a second copy of the
 * logic.
 */
class Gui(private val store: HabitStore, private val config: Config) {

    private var end: Timestamp = DateUtils.getTodayWithOffset()
    private var days = 21

    private val model = GridModel()
    private val table = JTable(model)
    private val status = JLabel(" ")
    private lateinit var frame: JFrame

    private val habits: List<Habit>
        get() = store.habitList.filter { !it.isArchived }

    private val closed = CountDownLatch(1)

    /**
     * Opens the window and blocks until it is closed.
     *
     * Blocking matters: Swing builds the window on its own event thread, so
     * without this `main` would return immediately and the `use` block around
     * [HabitStore] would close the database while the window was still using
     * it. Returns false when there is no display to open a window on -- over
     * plain SSH, say -- so the caller can fall back to the terminal.
     */
    fun show(): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        SwingUtilities.invokeLater { build() }
        closed.await()
        return true
    }

    /**
     * Paints the whole toolkit gruvbox dark, before any component is built.
     *
     * The cross-platform look and feel is used rather than the system one, and
     * that is the point: the Windows and GTK look and feels draw many of their
     * components natively and quietly ignore colour keys, so a dark theme comes
     * out half-applied -- dark table, white buttons. The cross-platform one
     * honours these, and honours them the same way on both machines.
     */
    private fun applyDarkTheme() {
        runCatching { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()) }
        val put = { key: String, value: Any -> UIManager.put(key, value) }
        for (key in listOf(
            "Panel.background", "OptionPane.background", "ToolBar.background",
            "ScrollPane.background", "Viewport.background", "MenuBar.background",
        )) put(key, BG0)
        for (key in listOf("Button.background", "TextField.background", "ComboBox.background")) {
            put(key, BG1)
        }
        for (key in listOf(
            "Label.foreground", "Button.foreground", "OptionPane.messageForeground",
            "TextField.foreground", "ComboBox.foreground",
        )) put(key, FG)
        put("TextField.caretForeground", FG)
        put("Table.background", BG0)
        put("Table.foreground", FG)
        put("Table.gridColor", BG2)
        put("TableHeader.background", BG1)
        put("TableHeader.foreground", GRAY)
        put("Button.select", BG2)
        put("ToolBar.borderColor", BG2)
        put("SplitPane.background", BG0)
    }

    private fun build() {
        applyDarkTheme()

        frame = JFrame("Loop Habits — ${store.file.name}")
        frame.contentPane.background = BG0
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        frame.layout = BorderLayout()
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) = closed.countDown()
        })

        table.rowHeight = 26
        table.setShowGrid(true)
        table.gridColor = BG2
        table.background = BG0
        table.foreground = FG
        table.tableHeader.background = BG1
        table.tableHeader.foreground = GRAY
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.tableHeader.reorderingAllowed = false
        table.tableHeader.resizingAllowed = false
        table.setDefaultRenderer(Any::class.java, CellRenderer())
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onCellClicked(e)
        })
        sizeColumns()

        frame.add(toolbar(), BorderLayout.NORTH)
        val scroll = JScrollPane(table)
        scroll.background = BG0
        scroll.viewport.background = BG0
        scroll.border = BorderFactory.createEmptyBorder()
        frame.add(scroll, BorderLayout.CENTER)

        status.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
        status.isOpaque = true
        status.background = BG1
        status.foreground = GRAY
        frame.add(status, BorderLayout.SOUTH)

        frame.preferredSize = Dimension(1000, 640)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true

        setStatus("${habits.size} habits")
        // Sync on open, the way the phone does when it comes to the foreground.
        if (config.syncFolder != null) runSync()
    }

    private fun toolbar(): JToolBar {
        val bar = JToolBar()
        bar.isFloatable = false
        bar.isOpaque = true
        bar.background = BG1
        bar.border = BorderFactory.createEmptyBorder(6, 6, 6, 6)

        bar.add(button("◀") { end = end.minus(days); refresh() })
        bar.add(button("Today") { end = DateUtils.getTodayWithOffset(); refresh() })
        bar.add(button("▶") {
            val today = DateUtils.getTodayWithOffset()
            end = if (end.plus(days).isNewerThan(today)) today else end.plus(days)
            refresh()
        })
        bar.addSeparator()
        bar.add(button("Sync now") { runSync() })
        bar.addSeparator()
        bar.add(button("Add habit") { onAddHabit() })
        bar.add(button("Delete habit") { onDeleteHabit() })
        bar.add(Box.createHorizontalGlue())
        return bar
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).apply {
        isFocusable = false
        background = BG2
        foreground = FG
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        addActionListener { action() }
    }

    // ------------------------------------------------------------ the table

    private inner class GridModel : AbstractTableModel() {
        override fun getRowCount() = habits.size
        override fun getColumnCount() = days + 1

        override fun getColumnName(column: Int): String {
            if (column == 0) return "Habit"
            val t = Grid.dayAt(end, days, column - 1)
            // Two lines in a header cell is the one thing HTML is genuinely
            // worth it for here; JTable headers are single-line otherwise.
            return "<html><center>${Grid.dayLabel(t)}<br>${Grid.weekdayLabel(t)}</center></html>"
        }

        override fun getValueAt(row: Int, column: Int): Any {
            val habit = habits.getOrNull(row) ?: return ""
            if (column == 0) return habit.name
            val t = Grid.dayAt(end, days, column - 1)
            return habit.computedEntries.get(t).value
        }

        override fun isCellEditable(row: Int, column: Int) = false
    }

    private inner class CellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val label = super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column,
            ) as JLabel

            label.background = if (isSelected) BG2 else BG0

            if (column == 0) {
                label.horizontalAlignment = SwingConstants.LEFT
                label.border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
                label.foreground = FG
                return label
            }

            val habit = habits.getOrNull(row)
            val v = (value as? Int) ?: Entry.UNKNOWN
            label.horizontalAlignment = SwingConstants.CENTER
            label.border = null
            label.text = if (habit == null) "" else Grid.cell(habit, v)
            label.font = label.font.deriveFont(Font.PLAIN, 15f)
            label.foreground = foregroundFor(v)
            return label
        }
    }

    /**
     * The same colours the terminal client uses, so the two cannot disagree
     * about what a finished day looks like.
     *
     * Cells carry no fill of their own. On a dark page a tinted background
     * behind every square turns the grid into a patchwork and the marks stop
     * being the thing you read; one colour per glyph on one flat page is
     * quieter and says exactly as much.
     */
    private fun foregroundFor(value: Int) = when (value) {
        Entry.YES_MANUAL -> GREEN
        Entry.YES_AUTO -> AQUA
        Entry.NO -> RED
        Entry.SKIP -> BLUE
        Entry.UNKNOWN -> BG2
        else -> YELLOW
    }

    private fun sizeColumns() {
        val columns = table.columnModel
        if (columns.columnCount == 0) return
        columns.getColumn(0).preferredWidth = 200
        columns.getColumn(0).minWidth = 200
        for (i in 1 until columns.columnCount) {
            columns.getColumn(i).preferredWidth = 34
            columns.getColumn(i).minWidth = 34
        }
        (table.tableHeader as JTableHeader).preferredSize = Dimension(0, 34)
    }

    // ---------------------------------------------------------------- actions

    private fun onCellClicked(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        val column = table.columnAtPoint(e.point)
        if (row < 0 || column <= 0) return
        val habit = habits.getOrNull(row) ?: return
        val timestamp = Grid.dayAt(end, days, column - 1)
        val existing = habit.originalEntries.get(timestamp)

        val value = if (habit.isNumerical) {
            val current = if (existing.value > 0) Grid.cell(habit, existing.value).trim() else ""
            val typed = JOptionPane.showInputDialog(
                frame,
                "${habit.name} — ${Grid.longDate(timestamp)}${if (habit.unit.isBlank()) "" else " (${habit.unit})"}",
                current,
            ) ?: return
            if (typed.isBlank()) {
                Entry.UNKNOWN
            } else {
                val number = typed.replace(',', '.').toDoubleOrNull()
                if (number == null) {
                    setStatus("Not a number: $typed")
                    return
                }
                Math.round(number * 1000).toInt()
            }
        } else {
            Entry.nextToggleValue(
                existing.value,
                isSkipEnabled = true,
                areQuestionMarksEnabled = true,
            )
        }

        habit.originalEntries.add(Entry(timestamp, value, existing.notes))
        habit.recompute()
        refresh()
        setStatus("${habit.name} — ${Grid.longDate(timestamp)} → ${Grid.cell(habit, value)}")
    }

    private fun onAddHabit() {
        val name = JOptionPane.showInputDialog(frame, "Name of the new habit:")?.trim()
        if (name.isNullOrBlank()) return
        val habit = store.modelFactory.buildHabit()
        habit.name = name
        store.habitList.add(habit)
        habit.recompute()
        refresh()
        setStatus("Added \"$name\". Sync to publish it.")
    }

    private fun onDeleteHabit() {
        val row = table.selectedRow
        val habit = habits.getOrNull(row)
        if (habit == null) {
            setStatus("Select a habit first (click its name).")
            return
        }
        val entries = habit.originalEntries.getKnown().size
        val answer = JOptionPane.showConfirmDialog(
            frame,
            "Delete \"${habit.name}\" and its $entries recorded days?\n" +
                "This deletes it on every synced device.",
            "Delete habit",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (answer != JOptionPane.YES_OPTION) return
        DeleteHabitsCommand(store.habitList, listOf(habit)).run()
        refresh()
        setStatus("Deleted \"${habit.name}\". Sync to propagate it.")
    }

    /**
     * Sync off the event thread. It reads and writes dozens of files, and doing
     * that on the UI thread freezes the window for as long as it takes.
     */
    private fun runSync() {
        val folder = config.syncFolder
        if (folder == null) {
            setStatus("No sync folder set. Run: uhabits --sync-folder <dir> sync")
            return
        }
        setStatus("Syncing…")
        object : SwingWorker<SyncReport?, Void>() {
            private var failure: Exception? = null

            override fun doInBackground(): SyncReport? = try {
                folder.mkdirs()
                store.baseDir.mkdirs()
                SyncEngine(store.habitList, store.modelFactory).sync(
                    FileSyncFolder(folder),
                    FileSyncFolder(store.baseDir),
                    folderId = folder.absolutePath,
                )
            } catch (e: Exception) {
                failure = e
                null
            }

            override fun done() {
                val report = get()
                store.recomputeAll()
                refresh()
                setStatus(
                    when {
                        failure != null -> "Sync failed: ${failure?.message}"
                        report == null -> "Sync failed."
                        report.abortedEmptyFolder ->
                            "Sync folder is empty — refusing to delete anything. Check Syncthing."
                        report.isEmpty -> "In sync. ${habits.size} habits."
                        else -> buildString {
                            append("Synced.")
                            if (report.createdLocally.isNotEmpty()) append(" ${report.createdLocally.size} added.")
                            if (report.updatedLocally.isNotEmpty()) append(" ${report.updatedLocally.size} updated.")
                            if (report.deletedLocally.isNotEmpty()) append(" ${report.deletedLocally.size} removed.")
                            if (report.conflicts.isNotEmpty()) append(" ${report.conflicts.size} conflict(s) resolved.")
                        }
                    },
                )
            }
        }.execute()
    }

    private fun refresh() {
        model.fireTableStructureChanged()
        sizeColumns()
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    companion object {
        private fun color(hex: Int) = Color(hex)
        private val BG0 = color(GruvboxPalette.BG0)
        private val BG1 = color(GruvboxPalette.BG1)
        private val BG2 = color(GruvboxPalette.BG2)
        private val FG = color(GruvboxPalette.FG)
        private val GRAY = color(GruvboxPalette.GRAY)
        private val RED = color(GruvboxPalette.RED)
        private val GREEN = color(GruvboxPalette.GREEN)
        private val YELLOW = color(GruvboxPalette.YELLOW)
        private val BLUE = color(GruvboxPalette.BLUE)
        private val AQUA = color(GruvboxPalette.AQUA)
    }
}
