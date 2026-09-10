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
package org.isoron.uhabits.sync

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.isoron.uhabits.core.sync.SyncFolder
import java.io.IOException

/**
 * A [SyncFolder] over a Storage Access Framework tree.
 *
 * The shared folder lives on `/sdcard`, which an app on Android 11 or later
 * cannot open as a [java.io.File]. Everything therefore goes through
 * [DocumentFile], which has two properties worth knowing before changing
 * anything here.
 *
 * **`createFile` does not overwrite.** Handed a name that already exists it
 * makes `name (1)`, then `name (2)`, and reports success every time. That is
 * exactly how the DecSync folder on this device ended up holding `tasks (1)`
 * through `tasks (13)` and four copies of `.decsync-info`. So a write always
 * looks the file up first and only creates it when genuinely absent, and the
 * stream is opened `"wt"` -- the `t` truncates, and without it a shorter
 * document leaves the tail of the longer one it replaced.
 *
 * **Every lookup is an IPC round trip.** `DocumentFile.findFile` lists the
 * whole directory and compares names, so the naive thing is quadratic and
 * visibly slow on a device with forty habits. Directory contents are read once
 * per sync and cached; nothing else in the app touches this folder while a sync
 * is running.
 */
class AndroidSyncFolder(
    private val context: Context,
    private val root: DocumentFile,
) : SyncFolder {

    private val dirs: MutableMap<String, DocumentFile> by lazy {
        root.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { doc -> doc.name?.let { it to doc } }
            .toMap()
            .toMutableMap()
    }

    private val fileCache = mutableMapOf<String, MutableMap<String, DocumentFile>>()

    private fun filesIn(dir: String): MutableMap<String, DocumentFile> =
        fileCache.getOrPut(dir) {
            dirs[dir]?.listFiles()
                ?.filter { it.isFile }
                ?.mapNotNull { doc -> doc.name?.let { it to doc } }
                ?.toMap()
                ?.toMutableMap()
                ?: mutableMapOf()
        }

    override fun listHabitDirs(): List<String> = dirs.keys.sorted()

    override fun listFiles(dir: String): List<String> = filesIn(dir).keys.sorted()

    override fun read(dir: String, file: String): String? {
        val doc = filesIn(dir)[file] ?: return null
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: IOException) {
            null
        }
    }

    override fun write(dir: String, file: String, content: String) {
        val parent = dirs[dir] ?: root.createDirectory(dir)?.also { dirs[dir] = it }
            ?: throw IOException("cannot create sync folder: $dir")

        val existing = filesIn(dir)[file]
        val doc = existing ?: parent.createFile(MIME_JSON, file)?.also { created ->
            // A provider may rewrite the name it was given -- appending an
            // extension for the mime type, or making it unique. If it did, the
            // file we think we wrote is not the file that is there, and the
            // next sync would create yet another one. Better to fail loudly
            // than to quietly grow a second copy on every save.
            val actual = created.name
            if (actual != file) {
                created.delete()
                throw IOException("storage renamed '$file' to '$actual'; refusing to write")
            }
            filesIn(dir)[file] = created
        } ?: throw IOException("cannot create $dir/$file")

        // "wt" truncates. Plain "w" on several providers keeps the tail of a
        // longer previous version, which turns valid JSON into trailing junk.
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("cannot write $dir/$file")
    }

    override fun deleteFile(dir: String, file: String) {
        filesIn(dir).remove(file)?.delete()
    }

    /**
     * Sizes and modification times, from the listing already in hand.
     *
     * `DocumentFile.length()` and `lastModified()` each cost a query, so this is
     * dozens of round trips -- but it is still far cheaper than opening and
     * parsing every file, which is what it saves when nothing has changed.
     */
    override fun fingerprint(): String {
        val sb = StringBuilder()
        for (dir in dirs.keys.sorted()) {
            for ((name, doc) in filesIn(dir).toSortedMap()) {
                sb.append(dir).append('/').append(name)
                    .append(':').append(doc.length())
                    .append('@').append(doc.lastModified())
                    .append('\n')
            }
        }
        return sb.toString()
    }

    override fun deleteDir(dir: String) {
        val doc = dirs.remove(dir) ?: return
        // Delete the contents first. DocumentsContract.deleteDocument is
        // documented to remove a directory's children too, but not every
        // provider honours that, and a half-deleted habit folder would be read
        // back as a habit with no entries.
        doc.listFiles().forEach { it.delete() }
        doc.delete()
        fileCache.remove(dir)
    }

    companion object {
        private const val MIME_JSON = "application/json"
    }
}
