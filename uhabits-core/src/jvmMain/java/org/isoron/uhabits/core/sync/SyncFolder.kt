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

import org.isoron.uhabits.core.database.Database
import java.io.File

const val HABIT_FILE = "habit.json"
const val ENTRIES_FILE = "entries.json"

/**
 * A cheap value that changes whenever the habit data could have changed.
 *
 * Two aggregate queries, a few milliseconds against five thousand entries, and
 * the counterpart to [SyncFolder.fingerprint] on the local side.
 *
 * The obvious stamp is the database file's size and modification time, and this
 * is used alongside it rather than instead -- but not on its own. Filesystem
 * timestamps are not always finer than a second, so two writes landing in the
 * same tick, with a sync recording its stamp between them, would leave the
 * second one looking unchanged and silently unpublished. Row counts, the sum of
 * every value and the highest id move whenever an entry is added, removed or
 * altered, which closes that window.
 */
fun databaseContentStamp(db: Database): String {
    fun scalar(query: String): String {
        val cursor = db.query(query)
        try {
            return if (cursor.moveToNext()) cursor.getString(0) ?: "" else ""
        } finally {
            cursor.close()
        }
    }
    return scalar(
        "select count(*)||':'||ifnull(total(value),0)||':'||ifnull(max(id),0) from Repetitions",
    ) + "|" + scalar("select count(*)||':'||ifnull(max(id),0) from Habits")
}

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

    /**
     * A cheap value that changes whenever any file here could have changed.
     *
     * Reading and parsing the whole folder to discover that nothing moved is
     * the dominant cost of a sync, and on a phone almost every sync is exactly
     * that. Sizes and modification times answer the same question without
     * opening anything.
     */
    fun fingerprint(): String
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

    override fun fingerprint(): String {
        val sb = StringBuilder()
        for (dir in root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()) {
            for (file in dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()) {
                sb.append(dir.name).append('/').append(file.name)
                    .append(':').append(file.length())
                    .append('@').append(file.lastModified())
                    .append('\n')
            }
        }
        return sb.toString()
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

    // No timestamps in memory, so the content stands in for them.
    override fun fingerprint(): String =
        files.entries.joinToString("\n") { "${it.key}:${it.value.hashCode()}" }
}
