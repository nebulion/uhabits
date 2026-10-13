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
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.documentfile.provider.DocumentFile
import org.isoron.uhabits.core.sync.SyncFolder
import java.io.IOException

/**
 * A [SyncFolder] over a Storage Access Framework tree.
 *
 * Used when the folder cannot be reached as a plain directory -- see
 * `AndroidSync.directPath`, which is the fast path and the usual one. Two
 * properties of the Storage Access Framework are worth knowing before changing
 * anything here.
 *
 * **Creating a document does not overwrite.** Handed a name that already
 * exists the provider makes `name (1)`, then `name (2)`, and reports success
 * every time. That is exactly how the DecSync folder on this device ended up
 * holding `tasks (1)` through `tasks (13)` and four copies of `.decsync-info`.
 * So a write always looks the file up first and only creates it when genuinely
 * absent, and the stream is opened `"wt"` -- the `t` truncates, and without it
 * a shorter document leaves the tail of the longer one it replaced.
 *
 * **Every question is an IPC round trip.** `DocumentFile` asks one per
 * attribute: `listFiles`, then `isDirectory`, `name`, `length` and
 * `lastModified` separately for each child. For forty habits that was some
 * four hundred and fifty binder calls per sync, over two seconds on the
 * Kompakt. A children query returns every one of those columns in one go, so
 * this lists each directory once, with everything, and caches it for the
 * sync. A directory's listing is dropped whenever this writes into it, so a
 * fingerprint taken after writing sees the new sizes and times.
 */
class AndroidSyncFolder(
    private val context: Context,
    root: DocumentFile,
) : SyncFolder {

    private class Doc(val uri: Uri, val isDir: Boolean, val size: Long, val modified: Long)

    private val resolver get() = context.contentResolver
    private val treeUri = root.uri
    private val rootUri = root.uri

    private val dirs: MutableMap<String, Doc> by lazy {
        children(rootUri).filterValues { it.isDir }.toMutableMap()
    }

    private val fileCache = mutableMapOf<String, MutableMap<String, Doc>>()

    private fun filesIn(dir: String): MutableMap<String, Doc> =
        fileCache.getOrPut(dir) {
            dirs[dir]?.let { d -> children(d.uri).filterValues { !it.isDir }.toMutableMap() }
                ?: mutableMapOf()
        }

    /** Every child of [parent], by name, from a single query. */
    private fun children(parent: Uri): Map<String, Doc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parent),
        )
        val result = mutableMapOf<String, Doc>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                result[name] = Doc(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }
        return result
    }

    override fun listHabitDirs(): List<String> = dirs.keys.sorted()

    override fun listFiles(dir: String): List<String> = filesIn(dir).keys.sorted()

    override fun read(dir: String, file: String): String? {
        val doc = filesIn(dir)[file] ?: return null
        return try {
            resolver.openInputStream(doc.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: IOException) {
            null
        }
    }

    override fun write(dir: String, file: String, content: String) {
        val parent = dirs[dir] ?: createDir(dir)
        val uri = filesIn(dir)[file]?.uri ?: create(parent.uri, MIME_JSON, file)
        fileCache.remove(dir)

        // "wt" truncates. Plain "w" on several providers keeps the tail of a
        // longer previous version, which turns valid JSON into trailing junk.
        resolver.openOutputStream(uri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("cannot write $dir/$file")
    }

    private fun createDir(dir: String): Doc {
        val uri = create(rootUri, Document.MIME_TYPE_DIR, dir)
        return Doc(uri, isDir = true, size = 0, modified = 0).also { dirs[dir] = it }
    }

    /**
     * Creates [name] under [parent], and refuses if the provider renamed it.
     *
     * A provider may append an extension for the mime type, or make the name
     * unique. If it did, the file we think we wrote is not the file that is
     * there, and the next sync would create yet another one. Better to fail
     * loudly than to quietly grow a second copy on every save.
     */
    private fun create(parent: Uri, mime: String, name: String): Uri {
        val uri = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("cannot create $name")
        val actual = resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        if (actual != name) {
            DocumentsContract.deleteDocument(resolver, uri)
            throw IOException("storage renamed '$name' to '$actual'; refusing to write")
        }
        return uri
    }

    override fun deleteFile(dir: String, file: String) {
        val doc = filesIn(dir).remove(file) ?: return
        DocumentsContract.deleteDocument(resolver, doc.uri)
        fileCache.remove(dir)
    }

    /** Sizes and modification times, from the listings already in hand. */
    override fun fingerprint(): String {
        val sb = StringBuilder()
        for (dir in dirs.keys.sorted()) {
            for ((name, doc) in filesIn(dir).toSortedMap()) {
                sb.append(dir).append('/').append(name)
                    .append(':').append(doc.size)
                    .append('@').append(doc.modified)
                    .append('\n')
            }
        }
        return sb.toString()
    }

    override fun deleteDir(dir: String) {
        val doc = dirs[dir] ?: return
        val files = filesIn(dir).values.toList()
        dirs.remove(dir)
        // Delete the contents first. DocumentsContract.deleteDocument is
        // documented to remove a directory's children too, but not every
        // provider honours that, and a half-deleted habit folder would be read
        // back as a habit with no entries.
        files.forEach { DocumentsContract.deleteDocument(resolver, it.uri) }
        DocumentsContract.deleteDocument(resolver, doc.uri)
        fileCache.remove(dir)
    }

    companion object {
        private const val MIME_JSON = "application/json"

        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
