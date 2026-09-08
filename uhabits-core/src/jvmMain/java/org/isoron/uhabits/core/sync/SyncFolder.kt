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

import java.io.File

const val HABIT_FILE = "habit.json"
const val ENTRIES_FILE = "entries.json"

/**
 * A flat folder-of-folders, one subfolder per habit.
 *
 * Android has to reach the shared folder through the Storage Access Framework,
 * which cannot be addressed as a [File] at all, so every path in this interface
 * is a plain name rather than a path and there is exactly one level of nesting.
 * That keeps the SAF implementation to a single `findFile` per lookup.
 */
interface SyncFolder {
    /** Subfolder names, one per habit. */
    fun listHabitDirs(): List<String>

    /** File names inside one habit's subfolder, conflict copies included. */
    fun listFiles(dir: String): List<String>

    /** Returns null when the file is absent. */
    fun read(dir: String, file: String): String?

    /** Creates [dir] if needed, and *overwrites* [file] rather than adding a copy beside it. */
    fun write(dir: String, file: String, content: String)

    fun deleteFile(dir: String, file: String)

    /** Removes a habit's subfolder and everything in it. */
    fun deleteDir(dir: String)
}

/**
 * Syncthing renames the losing copy of a conflicting file to
 * `entries.sync-conflict-20260906-140205-ABCDEFG.json` rather than overwriting
 * it, so a conflict is always discoverable by name.
 */
fun isConflictCopy(file: String) = file.contains(".sync-conflict-")

fun conflictBaseName(file: String) =
    file.substringBefore(".sync-conflict-") + "." + file.substringAfterLast('.')

/** A [SyncFolder] over a real directory. Used by the desktop, and for the local base cache. */
class FileSyncFolder(private val root: File) : SyncFolder {

    override fun listHabitDirs(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    override fun listFiles(dir: String): List<String> =
        File(root, dir).listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    override fun read(dir: String, file: String): String? {
        val f = File(File(root, dir), file)
        return if (f.isFile) f.readText() else null
    }

    override fun write(dir: String, file: String, content: String) {
        val d = File(root, dir)
        d.mkdirs()
        File(d, file).writeText(content)
    }

    override fun deleteFile(dir: String, file: String) {
        File(File(root, dir), file).delete()
    }

    override fun deleteDir(dir: String) {
        File(root, dir).deleteRecursively()
    }
}

/** A [SyncFolder] held entirely in memory. Used by the tests. */
class MemorySyncFolder : SyncFolder {
    val files = sortedMapOf<String, String>()

    private fun key(dir: String, file: String) = "$dir/$file"

    override fun listHabitDirs(): List<String> =
        files.keys.map { it.substringBefore('/') }.distinct().sorted()

    override fun listFiles(dir: String): List<String> =
        files.keys.filter { it.substringBefore('/') == dir }
            .map { it.substringAfter('/') }
            .sorted()

    override fun read(dir: String, file: String): String? = files[key(dir, file)]

    override fun write(dir: String, file: String, content: String) {
        files[key(dir, file)] = content
    }

    override fun deleteFile(dir: String, file: String) {
        files.remove(key(dir, file))
    }

    override fun deleteDir(dir: String) {
        files.keys.filter { it.substringBefore('/') == dir }.forEach { files.remove(it) }
    }
}
