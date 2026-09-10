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
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.core.sync.FileSyncFolder
import org.isoron.uhabits.core.sync.SyncEngine
import org.isoron.uhabits.core.sync.SyncReport
import org.isoron.uhabits.core.sync.parseSyncDate
import org.isoron.uhabits.core.utils.DateUtils
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

private const val USAGE = """
uhabits - Loop Habit Tracker on the desktop

  uhabits                        the terminal client (same as `uhabits tui`)
  uhabits list [--days N]        the habit grid, printed once
  uhabits show <habit>           one habit in detail
  uhabits check <habit> [date] [yes|no|skip|clear|<number>]
                                 record a day; with no value, cycles it
  uhabits add <name> [--question Q] [--numerical --unit U --target N]
                                 create a habit
  uhabits delete <name> --yes    delete a habit and its history
  uhabits sync                   reconcile with the shared folder
  uhabits gui                    the window
  uhabits tui                    the full-screen terminal client
  uhabits where                  paths this client is using
  uhabits dump [rows cols]       print one frame of the terminal client

Options:
  --db <file>                    use this database instead of the default
  --sync-folder <dir>            use this shared folder (and remember it)
  --config <file>                use this settings file instead of the default
"""

fun main(args: Array<String>) {
    // Box-drawing and check-marks reach a Windows console as mojibake unless the
    // stream is told otherwise; on Linux this is what it would have been anyway.
    System.setOut(PrintStream(System.out, true, StandardCharsets.UTF_8))

    val argv = args.toMutableList()
    val dbOverride = takeOption(argv, "--db")?.let(::resolvePath)
    val syncOverride = takeOption(argv, "--sync-folder")?.let(::resolvePath)
    val configOverride = takeOption(argv, "--config")?.let(::resolvePath)

    val config = if (configOverride != null) Config(configOverride) else Config()
    if (syncOverride != null) config.syncFolder = syncOverride

    // Bare `uhabits` opens the client. Running it is the common case; the
    // one-shot listing is the thing you ask for by name.
    val command = argv.firstOrNull() ?: "tui"
    val rest = argv.drop(1)

    if (command in listOf("help", "--help", "-h")) {
        println(USAGE.trim())
        return
    }

    if (command == "where") {
        val db = dbOverride ?: DesktopEnv.defaultDatabase
        println("database    $db")
        // Say how many habits are actually in the folder, not just its path. A
        // path that looks plausible but points somewhere empty is the failure
        // that reads as "sync does nothing" with no other symptom.
        val folder = config.syncFolder
        val contents = when {
            folder == null -> "(not set - pass --sync-folder once)"
            !folder.isDirectory -> "$folder  << does not exist"
            else -> {
                val n = folder.listFiles()?.count { it.isDirectory && !it.name.startsWith(".") } ?: 0
                if (n == 0) "$folder  << no habits in it" else "$folder  ($n habits)"
            }
        }
        println("sync folder $contents")
        println("merge base  ${File(db.absoluteFile.parentFile, db.nameWithoutExtension + ".sync-base")}")
        println("config      ${configOverride ?: DesktopEnv.configFile}")
        return
    }

    HabitStore(dbOverride ?: DesktopEnv.defaultDatabase).use { store ->
        store.recomputeAll()
        when (command) {
            "list" -> list(store, rest)
            "show" -> show(store, rest)
            "check" -> check(store, rest)
            "add" -> add(store, rest)
            "delete" -> delete(store, rest)
            "sync" -> sync(store, config)
            "gui" -> if (!Gui(store, config).show()) {
                System.err.println("No display available, so here is the list instead.")
                System.err.println()
                list(store, emptyList())
            }
            "tui" -> if (!Tui(store, config).run()) {
                System.err.println("This terminal can't run the full-screen client, so here is the list.")
                System.err.println("Try Windows Terminal, or use `uhabits list` directly.")
                System.err.println()
                list(store, emptyList())
            }
            "dump" -> {
                val rows = rest.getOrNull(0)?.toIntOrNull() ?: 24
                val cols = rest.getOrNull(1)?.toIntOrNull() ?: 100
                Tui(store, config).dump(rows, cols).forEach(::println)
            }
            else -> {
                System.err.println("unknown command: $command")
                println(USAGE.trim())
                exitProcess(2)
            }
        }
    }
}

/**
 * Expands a leading `~`, and makes the result absolute.
 *
 * A shell normally expands `~` before the program ever sees it -- but only its
 * own. PowerShell hands the literal character straight to a native executable,
 * so `--sync-folder ~/Sync/loop` arrived here as the three-segment relative path
 * `~/Sync/loop` and quietly created a directory actually named `~` next to the
 * working directory. Sync then ran against that empty folder and truthfully
 * reported nothing to do, which is a miserable thing to debug.
 */
private fun resolvePath(path: String): File {
    val trimmed = path.trim().trim('"')
    val expanded = when {
        trimmed == "~" -> File(System.getProperty("user.home"))
        trimmed.startsWith("~/") || trimmed.startsWith("~\\") ->
            File(System.getProperty("user.home"), trimmed.drop(2))
        else -> File(trimmed)
    }
    return expanded.absoluteFile
}

private fun takeOption(argv: MutableList<String>, name: String): String? {
    val i = argv.indexOf(name)
    if (i < 0) return null
    require(i + 1 < argv.size) { "$name needs a value" }
    val value = argv[i + 1]
    argv.removeAt(i + 1)
    argv.removeAt(i)
    return value
}

private fun list(store: HabitStore, args: List<String>) {
    val days = args.indexOf("--days").let { if (it >= 0) args[it + 1].toInt() else 21 }
    val habits = store.habitList.filter { !it.isArchived }
    if (habits.isEmpty()) {
        // Say which file, how big it is and what is in it. "No habits yet" on
        // its own cannot tell an empty database from a full one whose habits
        // were all filtered out, nor from a path that resolved somewhere other
        // than where the reader expects -- and all three have been suspected.
        val total = store.habitList.size()
        println("No habits to show.")
        println("  database   ${store.file.absolutePath}")
        println("  exists     ${store.file.isFile}, ${store.file.length()} bytes")
        println("  habits     $total in the file, ${total - habits.size} of them archived")
        println("  user.home  ${System.getProperty("user.home")}")
        println("  APPDATA    ${System.getenv("APPDATA")}")
        println("  java       ${System.getProperty("java.version")} at ${System.getProperty("java.home")}")
        if (total == 0) {
            println()
            println("The file really is empty. Run `uhabits sync` to pull them from the shared folder.")
        }
        return
    }
    val end = DateUtils.getTodayWithOffset()
    for (line in Grid.headerLines(end, days)) println(line)
    for (habit in habits) println(Grid.row(habit, end, days))
    println()
    println("  ● done   ○ credited by frequency   × missed   – skipped   · nothing recorded")
}

private fun show(store: HabitStore, args: List<String>) {
    val habit = requireHabit(store, args)
    println(Grid.summary(habit))
    if (habit.question.isNotBlank()) println("  \"${habit.question}\"")
    if (habit.description.isNotBlank()) println("  ${habit.description}")
    println()
    val end = DateUtils.getTodayWithOffset()
    for (line in Grid.headerLines(end, 28)) println(line)
    println(Grid.row(habit, end, 28))
    println()
    val best = habit.streaks.getBest(3)
    if (best.isNotEmpty()) {
        println("Best streaks")
        for (streak in best) println("  ${streak.length} days, ending ${streak.end}")
    }
}

private fun check(store: HabitStore, args: List<String>) {
    require(args.isNotEmpty()) { "check needs a habit" }
    val habit = requireHabit(store, args)
    val tail = args.drop(1)
    val dateArg = tail.firstOrNull { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
    val valueArg = tail.firstOrNull { it != dateArg }

    val timestamp = if (dateArg != null) Timestamp(parseSyncDate(dateArg)) else DateUtils.getTodayWithOffset()
    val current = habit.originalEntries.get(timestamp).value
    val value = parseValue(habit, valueArg, current)

    habit.originalEntries.add(Entry(timestamp, value, habit.originalEntries.get(timestamp).notes))
    habit.recompute()
    println("${habit.name} ${org.isoron.uhabits.core.sync.formatSyncDate(timestamp.unixTime)} -> ${Grid.cell(habit, value)}")
}

private fun parseValue(habit: Habit, arg: String?, current: Int): Int = when {
    arg == null -> Entry.nextToggleValue(current, isSkipEnabled = true, areQuestionMarksEnabled = true)
    arg.equals("yes", true) -> Entry.YES_MANUAL
    arg.equals("no", true) -> Entry.NO
    arg.equals("skip", true) -> Entry.SKIP
    arg.equals("clear", true) || arg.equals("unknown", true) -> Entry.UNKNOWN
    else -> {
        val number = arg.replace(',', '.').toDoubleOrNull()
            ?: throw IllegalArgumentException("not a value: $arg")
        Math.round(number * 1000).toInt()
    }
}

private fun add(store: HabitStore, args: List<String>) {
    val rest = args.toMutableList()
    val question = takeOption(rest, "--question")
    val unit = takeOption(rest, "--unit")
    val target = takeOption(rest, "--target")?.toDoubleOrNull()
    val numerical = rest.remove("--numerical") || unit != null || target != null
    val name = rest.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("add needs a name")

    val habit = store.modelFactory.buildHabit()
    habit.name = name
    if (question != null) habit.question = question
    if (numerical) {
        habit.type = HabitType.NUMERICAL
        habit.unit = unit ?: ""
        habit.targetValue = target ?: 1.0
    }
    store.habitList.add(habit)
    habit.recompute()
    println("Added \"${habit.name}\". Run `uhabits sync` to publish it.")
}

private fun delete(store: HabitStore, args: List<String>) {
    val rest = args.toMutableList()
    val confirmed = rest.remove("--yes")
    val query = rest.firstOrNull() ?: throw IllegalArgumentException("delete needs a name")

    // Exact match only, unlike every other command here. `show` and `check`
    // fall back to a substring so a long name can be typed once; deleting the
    // wrong habit takes its whole history with it, so the name has to be right.
    val habit = store.habitList.firstOrNull { it.name.equals(query, ignoreCase = true) }
        ?: throw IllegalArgumentException("no habit named exactly \"$query\"")

    val entries = habit.originalEntries.getKnown().size
    if (!confirmed) {
        println("\"${habit.name}\" has $entries recorded days.")
        println("Re-run with --yes to delete it. This also deletes it on every synced device.")
        return
    }
    DeleteHabitsCommand(store.habitList, listOf(habit)).run()
    println("Deleted \"${habit.name}\" ($entries days). Run `uhabits sync` to propagate it.")
}

private fun requireHabit(store: HabitStore, args: List<String>): Habit {
    val query = args.firstOrNull() ?: throw IllegalArgumentException("needs a habit name")
    return store.findHabit(query) ?: throw IllegalArgumentException("no such habit: $query")
}

fun sync(store: HabitStore, config: Config): SyncReport {
    val folder = config.syncFolder
    if (folder == null) {
        System.err.println("No sync folder set. Run once with --sync-folder <dir> pointing")
        System.err.println("at a directory inside your Syncthing folder, e.g.")
        System.err.println("  uhabits --sync-folder ~/Sync/loop sync")
        exitProcess(2)
    }
    folder.mkdirs()
    store.baseDir.mkdirs()

    val engine = SyncEngine(store.habitList, store.modelFactory)
    val report = engine.sync(
        FileSyncFolder(folder),
        FileSyncFolder(store.baseDir),
        folderId = folder.absolutePath,
        localStamp = store::stamp,
    )
    printReport(report)
    return report
}

fun printReport(report: SyncReport) {
    if (report.abortedEmptyFolder) {
        println("The sync folder is empty, but this machine remembers habits in it.")
        println("Refusing to delete them. If the folder really should be empty, remove")
        println("the merge base and sync again.")
        return
    }
    if (report.baseReset) println("Sync folder changed; starting fresh against it.")
    if (report.isEmpty) {
        println("Already in sync; nothing written.")
        return
    }
    for (name in report.createdLocally) println("  + $name")
    for (name in report.updatedLocally) println("  ~ $name")
    for (name in report.deletedLocally) println("  - $name (deleted elsewhere)")
    for (name in report.deletedRemotely) println("  - $name (removed from the folder)")
    if (report.conflictFilesHealed > 0) {
        println("  merged ${report.conflictFilesHealed} Syncthing conflict file(s)")
    }
    for (conflict in report.conflicts) {
        println("  ! ${conflict.habitName} ${conflict.date}: kept ${conflict.kept}, discarded ${conflict.discarded}")
    }
    println("${report.filesWritten} file(s) written.")
}
