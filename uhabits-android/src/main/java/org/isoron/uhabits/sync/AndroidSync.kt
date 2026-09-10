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
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.sync.FileSyncFolder
import org.isoron.uhabits.core.sync.SyncEngine
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.sync.SyncReport
import org.isoron.uhabits.core.sync.databaseContentStamp
import org.isoron.uhabits.utils.DatabaseUtils
import java.io.File

/**
 * Everything the app needs to reconcile itself with the shared folder.
 *
 * The database is untouched by all of this: it stays where it has always been,
 * and sync is a step that runs beside it, in the same place the automatic
 * backup already runs. With no folder chosen, [run] does nothing at all.
 */
object AndroidSync {

    const val PREF_SYNC_FOLDER = "syncFolder"

    /**
     * Where the shared folder lives by default.
     *
     * `/sdcard/Sync` is the Syncthing folder on this device; `loop` is ours
     * inside it. Offering this directly matters because the alternative is
     * navigating a file picker on a panel that repaints in half-seconds.
     */
    val defaultFolder: File get() = File("/sdcard/Sync/loop")

    /**
     * True when the app may read and write shared storage directly.
     *
     * Below Android 11 there is no such concept and ordinary file access
     * applies, so this reports true and the plain file path is used.
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** The system screen where the user grants that access. */
    fun allFilesAccessIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )

    fun useDefaultFolder(context: Context) {
        defaultFolder.mkdirs()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_SYNC_FOLDER, Uri.fromFile(defaultFolder).toString())
            .apply()
    }

    fun folderUri(context: Context): Uri? =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_SYNC_FOLDER, null)
            ?.let(Uri::parse)

    fun folderLabel(context: Context): String? =
        folderUri(context)?.let { uri ->
            if (uri.scheme == "file") {
                uri.path
            } else {
                DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment
            }
        }

    /**
     * Records the folder the user picked, and asks to keep the grant.
     *
     * Without [android.content.ContentResolver.takePersistableUriPermission]
     * the permission is scoped to this process and is gone after a reboot, at
     * which point sync fails silently. There is no such call anywhere else in
     * this app, which is why the half-built `publicBackupFolder` preference
     * never worked.
     */
    fun setFolder(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_SYNC_FOLDER, uri.toString())
            .apply()
    }

    fun clearFolder(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(PREF_SYNC_FOLDER)
            .apply()
    }

    /**
     * This device's private record of what the folder said last time, kept in
     * internal storage. It must not live in the shared folder: it is how this
     * device tells its own edits from the other device's, and sharing it would
     * make both devices agree about a past neither of them had.
     */
    fun baseDir(context: Context): File =
        File(context.filesDir, "sync-base").apply { mkdirs() }

    /**
     * A cheap value that changes whenever the habit database could have changed.
     *
     * Size and modification time of the database file. It errs towards saying
     * "changed": SQLite touches the file for reasons that are not edits, and a
     * spurious yes costs one full sync rather than a wrong answer.
     */
    private fun databaseStamp(context: Context, modelFactory: ModelFactory): String {
        val file = DatabaseUtils.getDatabaseFile(context)
        val content = (modelFactory as? SQLModelFactory)
            ?.let { databaseContentStamp(it.database) }
            ?: ""
        return "${file.length()}@${file.lastModified()}|$content"
    }

    /**
     * A one-line record of what the last sync did, or why it did nothing.
     *
     * `Log.d` and `Log.e` do not reach logcat on the Kompakt -- see
     * `docs/PROJECT-STATUS.md`, where this cost a whole debugging session twice
     * -- so the only way to find out why a background sync stayed silent is to
     * have it write down what happened somewhere readable.
     */
    fun statusFile(context: Context): File = File(context.filesDir, "sync-status.txt")

    private fun note(context: Context, text: String) {
        runCatching {
            statusFile(context).writeText(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + "  " + text + "\n",
            )
        }
    }

    /** Returns null when no folder has been chosen, or it cannot be opened. */
    fun run(context: Context, habitList: HabitList, modelFactory: ModelFactory): SyncReport? {
        val remote = try {
            openFolder(context)
        } catch (e: Exception) {
            note(context, "could not open folder: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (remote == null) {
            note(context, "no usable folder. pref=${folderUri(context)} allFilesAccess=${hasAllFilesAccess()}")
            return null
        }
        val startedAt = System.currentTimeMillis()
        return try {
            SyncEngine(habitList, modelFactory).sync(
                remote = remote,
                base = FileSyncFolder(baseDir(context)),
                folderId = folderUri(context).toString(),
                localStamp = { databaseStamp(context, modelFactory) },
            ).also {
                note(
                    context,
                    "ok in ${System.currentTimeMillis() - startedAt}ms: " +
                        "wrote=${it.filesWritten} created=${it.createdLocally.size} " +
                        "updated=${it.updatedLocally.size} deleted=${it.deletedLocally.size} " +
                        "aborted=${it.abortedEmptyFolder}",
                )
            }
        } catch (e: Exception) {
            note(context, "failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /**
     * The chosen folder, as something the engine can read and write.
     *
     * Two schemes, following the same split [org.isoron.uhabits.database.AutoBackup]
     * already makes. A `content://` tree is what the folder picker returns and
     * what a folder on shared storage has to be reached through. A `file://`
     * path skips the Storage Access Framework entirely and uses plain file I/O
     * -- only useful for a directory this app may write to directly, which on
     * Android 12 means its own, but that makes the sync path testable on device
     * without a human tapping through a picker first.
     */
    private fun openFolder(context: Context): org.isoron.uhabits.core.sync.SyncFolder? {
        val uri = folderUri(context) ?: return null
        if (uri.scheme == "file") {
            val dir = uri.path?.let(::File) ?: return null
            if (!dir.isDirectory && !dir.mkdirs()) return null
            return FileSyncFolder(dir)
        }
        val root = DocumentFile.fromTreeUri(context, uri) ?: return null
        if (!root.isDirectory || !root.canWrite()) return null
        return AndroidSyncFolder(context, root)
    }

    /** A one-line summary for the snackbar, or null when there was nothing to say. */
    fun describe(report: SyncReport): String? {
        if (report.isEmpty) return null
        val parts = mutableListOf<String>()
        if (report.createdLocally.isNotEmpty()) parts += "${report.createdLocally.size} added"
        if (report.updatedLocally.isNotEmpty()) parts += "${report.updatedLocally.size} updated"
        if (report.deletedLocally.isNotEmpty()) parts += "${report.deletedLocally.size} removed"
        if (report.conflictFilesHealed > 0) parts += "${report.conflictFilesHealed} conflicts merged"
        if (report.abortedEmptyFolder) {
            return "Sync folder is empty - not deleting anything. Check Syncthing."
        }
        if (parts.isEmpty()) parts += "${report.filesWritten} files written"
        return "Synced: " + parts.joinToString(", ")
    }
}
