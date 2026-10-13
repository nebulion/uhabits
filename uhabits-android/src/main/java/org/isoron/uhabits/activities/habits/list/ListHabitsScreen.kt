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

package org.isoron.uhabits.activities.habits.list

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import dagger.Lazy
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.CheckmarkDialog
import org.isoron.uhabits.activities.common.dialogs.ConfirmDeleteDialog
import org.isoron.uhabits.activities.common.dialogs.NumberDialog
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.core.commands.ArchiveHabitsCommand
import org.isoron.uhabits.core.commands.ChangeHabitColorCommand
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.commands.UnarchiveHabitsCommand
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.Task
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.ui.callbacks.OnColorPickedCallback
import org.isoron.uhabits.core.ui.callbacks.OnConfirmedCallback
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.COULD_NOT_EXPORT
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.COULD_NOT_GENERATE_BUG_REPORT
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.DATABASE_REPAIRED
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.FILE_NOT_RECOGNIZED
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.IMPORT_FAILED
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior.Message.IMPORT_SUCCESSFUL
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsMenuBehavior
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsSelectionMenuBehavior
import org.isoron.uhabits.inject.ActivityContext
import org.isoron.uhabits.inject.ActivityScope
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.sync.AndroidSync
import org.isoron.uhabits.tasks.ExportDBTaskFactory
import org.isoron.uhabits.tasks.ImportDataTask
import org.isoron.uhabits.tasks.ImportDataTaskFactory
import org.isoron.uhabits.utils.ColorUtils
import org.isoron.uhabits.utils.copyTo
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dismissCurrentAndShow
import org.isoron.uhabits.utils.restartWithFade
import org.isoron.uhabits.utils.showMessage
import org.isoron.uhabits.utils.showSendEmailScreen
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

const val RESULT_IMPORT_DATA = 101
const val RESULT_EXPORT_CSV = 102
const val RESULT_EXPORT_DB = 103
const val RESULT_BUG_REPORT = 104
const val RESULT_REPAIR_DB = 105
const val RESULT_PICK_SYNC_FOLDER = 111
const val RESULT_FORGET_SYNC_FOLDER = 112
const val RESULT_GRANT_FILE_ACCESS = 114
const val REQUEST_OPEN_DOCUMENT = 106
const val REQUEST_CREATE_DOCUMENT = 107

// Was also 107. Kotlin's `when` takes the first matching branch, so every
// result coming back from SettingsActivity was routed to the create-document
// handler instead -- which silently disabled Export, Import and Repair.
const val REQUEST_SETTINGS = 108
const val REQUEST_PICK_SYNC_FOLDER = 109

@ActivityScope
class ListHabitsScreen
@Inject constructor(
    @ActivityContext val context: Context,
    private val commandRunner: CommandRunner,
    private val intentFactory: IntentFactory,
    private val themeSwitcher: ThemeSwitcher,
    private val model: HabitCardListModel,
    private val taskRunner: TaskRunner,
    private val exportDBFactory: ExportDBTaskFactory,
    private val importTaskFactory: ImportDataTaskFactory,
    private val behavior: Lazy<ListHabitsBehavior>,
    private val preferences: Preferences,
) : CommandRunner.Listener,
    ListHabitsBehavior.Screen,
    ListHabitsMenuBehavior.Screen,
    ListHabitsSelectionMenuBehavior.Screen {

    val activity = (context as AppCompatActivity)

    /**
     * The transient message the screen is currently showing, if any.
     *
     * These used to go straight to a Material `Snackbar` -- a dark grey card
     * with an elevation shadow, neither of which exists anywhere else in this
     * app, and the last Material surface still reachable from a live path
     * (a failed CSV import, a finished export). They are handed to the Compose
     * screen now, which shows them in `SnackbarMMD`.
     */
    var message by mutableStateOf<String?>(null)
        private set

    /** True while [onSyncNow] is running, so a second request is dropped. */
    private var isSyncing = false

    private fun postMessage(text: String) {
        message = text
    }

    /** Called once the screen has shown [message]. */
    fun onMessageShown() {
        message = null
    }

    fun onAttached() {
        commandRunner.addListener(this)
    }

    fun onDetached() {
        commandRunner.removeListener(this)
    }

    override fun onCommandFinished(command: Command) {
        val msg = getExecuteString(command)
        if (msg != null) postMessage(msg)
    }

    fun onResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_OPEN_DOCUMENT -> onOpenDocumentResult(resultCode, data)
            REQUEST_CREATE_DOCUMENT -> onCreateDocumentResult(resultCode, data)
            REQUEST_SETTINGS -> onSettingsResult(resultCode)
            REQUEST_PICK_SYNC_FOLDER -> onPickSyncFolderResult(resultCode, data)
        }
    }

    private fun onOpenDocumentResult(resultCode: Int, data: Intent?) {
        if (data == null) return
        if (resultCode != Activity.RESULT_OK) return
        try {
            val inStream = activity.contentResolver.openInputStream(data.data!!)!!
            val cacheDir = activity.externalCacheDir
            val tempFile = File.createTempFile("import", "", cacheDir)
            inStream.copyTo(tempFile)
            onImportData(tempFile) { tempFile.delete() }
        } catch (e: IOException) {
            postMessage(activity.resources.getString(R.string.could_not_import))
            e.printStackTrace()
        }
    }

    private fun onSettingsResult(resultCode: Int) {
        when (resultCode) {
            RESULT_IMPORT_DATA -> showImportScreen()
            RESULT_EXPORT_CSV -> behavior.get().onExportCSV()
            RESULT_EXPORT_DB -> onExportDB()
            RESULT_BUG_REPORT -> behavior.get().onSendBugReport()
            RESULT_REPAIR_DB -> behavior.get().onRepairDB()
            RESULT_GRANT_FILE_ACCESS -> showAllFilesAccessScreen()

            RESULT_PICK_SYNC_FOLDER -> showPickSyncFolderScreen()
            RESULT_FORGET_SYNC_FOLDER -> {
                AndroidSync.clearFolder(activity)
                postMessage(activity.resources.getString(R.string.sync_folder_cleared))
            }
        }
    }

    /**
     * Opens the system screen where "All files access" is granted.
     *
     * Wrapped because the per-app screen does not exist on every build; the
     * global list always does, and is one tap further away.
     */
    private fun showAllFilesAccessScreen() {
        try {
            activity.startActivity(AndroidSync.allFilesAccessIntent(activity.packageName))
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                postMessage(activity.resources.getString(R.string.sync_grant_failed))
            }
        }
    }

    private fun showPickSyncFolderScreen() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            activity.startActivityForResult(intent, REQUEST_PICK_SYNC_FOLDER)
        } catch (e: Exception) {
            // No document picker on this device. Fall back to the folder the
            // Syncthing install already uses, which is reachable as a path once
            // shared-storage access is granted.
            if (AndroidSync.hasAllFilesAccess()) {
                AndroidSync.useDefaultFolder(activity)
                onSyncNow()
            } else {
                showAllFilesAccessScreen()
            }
        }
    }

    private fun onPickSyncFolderResult(resultCode: Int, data: Intent?) {
        val uri = data?.data ?: return
        if (resultCode != Activity.RESULT_OK) return
        AndroidSync.setFolder(activity, uri)
        onSyncNow()
    }

    fun onSyncNow() {
        if (isSyncing) return
        isSyncing = true
        val component = (activity.application as HabitsApplication).component
        taskRunner.execute(
            object : Task {
                private var message: String? = null

                override fun doInBackground() {
                    message = try {
                        val report = AndroidSync.run(
                            activity,
                            component.habitList,
                            component.modelFactory,
                        )
                        when {
                            report == null ->
                                activity.resources.getString(R.string.sync_folder_not_set)
                            // Asked for by hand, so it always answers -- a
                            // sync with nothing to do is still news.
                            else -> AndroidSync.describe(report)
                                ?: activity.resources.getString(R.string.sync_up_to_date)
                        }
                    } catch (e: Exception) {
                        activity.resources.getString(R.string.sync_failed, e.message ?: "")
                    }
                }

                override fun onPostExecute() {
                    isSyncing = false
                    model.refresh()
                    message?.let { postMessage(it) }
                }
            }
        )
    }

    override fun applyTheme() {
        themeSwitcher.apply()
        activity.restartWithFade(ListHabitsActivity::class.java)
    }

    override fun showSelectHabitTypeDialog() {
        val dialog = HabitTypeDialog()
        dialog.show(activity.supportFragmentManager, "habitType")
    }

    override fun showDeleteConfirmationScreen(callback: OnConfirmedCallback, quantity: Int) {
        ConfirmDeleteDialog.show(activity.supportFragmentManager, callback, quantity)
    }

    override fun showEditHabitsScreen(selected: List<Habit>) {
        val intent = intentFactory.startEditActivity(activity, selected[0])
        activity.startActivity(intent)
    }


    override fun showHabitScreen(h: Habit) {
        val intent = intentFactory.startShowHabitActivity(activity, h)
        activity.startActivity(intent)
    }

    fun showImportScreen() {
        val intent = intentFactory.openDocument()
        activity.startActivityForResult(intent, REQUEST_OPEN_DOCUMENT)
    }

    override fun showMessage(m: ListHabitsBehavior.Message) {
        postMessage(
            activity.resources.getString(
                when (m) {
                    COULD_NOT_EXPORT -> R.string.could_not_export
                    IMPORT_SUCCESSFUL -> R.string.habits_imported
                    IMPORT_FAILED -> R.string.could_not_import
                    DATABASE_REPAIRED -> R.string.database_repaired
                    COULD_NOT_GENERATE_BUG_REPORT -> R.string.bug_report_failed
                    FILE_NOT_RECOGNIZED -> R.string.file_not_recognized
                }
            )
        )
    }

    override fun showSendBugReportToDeveloperScreen(log: String) {
        val to = R.string.bugReportTo
        val subject = R.string.bugReportSubject
        activity.showSendEmailScreen(to, subject, log)
    }

    /**
     * Hands an exported file to the file manager to be saved, rather than to
     * the share sheet.
     *
     * An export is a file you want to keep, and the share sheet answers a
     * different question -- who do you want to send this to. It offered
     * Signal, WhatsApp and a mail client before it offered anywhere to put the
     * file. `ACTION_CREATE_DOCUMENT` asks the question actually being asked,
     * and the same picker already handles import.
     *
     * The exported file is written to the cache first, as it always was, and
     * copied into whatever the picker returns. It is remembered across the
     * round trip because the result comes back to the activity, not to us.
     */
    override fun showSendFileScreen(filename: String) {
        pendingExport = File(filename)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypeOf(filename)
            putExtra(Intent.EXTRA_TITLE, pendingExport?.name)
        }
        activity.startActivityForResult(intent, REQUEST_CREATE_DOCUMENT)
    }

    /**
     * The export waiting for somewhere to be saved, if any.
     *
     * On disk, because nothing smaller survives. The file picker is a
     * separate, heavy activity, and on a device with this little memory the
     * whole habits process is killed while it is open -- confirmed on the
     * Kompakt, which came back to the launcher after saving. An instance field
     * was lost with the screen and a static one was lost with the process;
     * both produced a zero-byte export, because the result arrived with
     * nothing queued and the copy had nothing to copy.
     *
     * SharedPreferences outlives both. Cleared as soon as the result is
     * handled, so a stale path can never be written over a later export.
     */
    private var pendingExport: File?
        get() = activity.getSharedPreferences(EXPORT_PREFS, Context.MODE_PRIVATE)
            .getString(EXPORT_KEY, null)
            ?.let(::File)
        set(value) {
            activity.getSharedPreferences(EXPORT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(EXPORT_KEY, value?.absolutePath)
                .apply()
        }

    private fun mimeTypeOf(filename: String) = when {
        filename.endsWith(".csv") -> "text/csv"
        filename.endsWith(".db") -> "application/octet-stream"
        else -> "application/zip"
    }

    private fun onCreateDocumentResult(resultCode: Int, data: Intent?) {
        val source = pendingExport
        pendingExport = null
        if (resultCode != Activity.RESULT_OK) return
        val target = data?.data ?: return
        if (source == null) return
        try {
            activity.contentResolver.openOutputStream(target)!!.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            postMessage(activity.resources.getString(R.string.export_to_csv))
        } catch (e: IOException) {
            postMessage(activity.resources.getString(R.string.could_not_export))
            e.printStackTrace()
        }
    }

    /**
     * Does nothing. A confetti burst is hundreds of small coloured particles
     * animating over the list for a second -- on an E Ink panel it is the
     * single most expensive thing the app could draw, and it leaves a visible
     * ghost of the whole spray behind. The callback is kept because the core
     * behaviour still fires it on completion.
     */
    override fun showConfetti(color: PaletteColor, x: Float, y: Float) = Unit

    override fun showSettingsScreen() {
        val intent = intentFactory.startSettingsActivity(activity)
        activity.startActivityForResult(intent, REQUEST_SETTINGS)
    }

    override fun showNumberPopup(
        value: Double,
        notes: String,
        callback: ListHabitsBehavior.NumberPickerCallback
    ) {
        val fm = (context as AppCompatActivity).supportFragmentManager
        val dialog = NumberDialog()
        dialog.arguments = Bundle().apply {
            putDouble("value", value)
            putString("notes", notes)
        }
        dialog.onToggle = { v, n -> callback.onNumberPicked(v, n) }
        dialog.dismissCurrentAndShow(fm, "numberDialog")
    }

    override fun showCheckmarkPopup(
        selectedValue: Int,
        notes: String,
        color: PaletteColor,
        callback: ListHabitsBehavior.CheckMarkDialogCallback
    ) {
        // Non-null for AndroidThemeSwitcher, which is the only implementation
        // bound here and always holds an EInkTheme.
        val theme = themeSwitcher.currentTheme!!
        val fm = (context as AppCompatActivity).supportFragmentManager
        val dialog = CheckmarkDialog()
        dialog.arguments = Bundle().apply {
            putInt("color", theme.color(color).toInt())
            putInt("value", selectedValue)
            putString("notes", notes)
        }
        dialog.onToggle = { v, n -> callback.onNotesSaved(v, n) }
        dialog.dismissCurrentAndShow(fm, "checkmarkDialog")
    }

    private fun getExecuteString(command: Command): String? {
        when (command) {
            is ArchiveHabitsCommand -> {
                return activity.resources.getQuantityString(
                    R.plurals.toast_habits_archived,
                    command.selected.size
                )
            }

            is ChangeHabitColorCommand -> {
                return activity.resources.getQuantityString(
                    R.plurals.toast_habits_changed,
                    command.selected.size
                )
            }

            is CreateHabitCommand -> {
                return activity.resources.getString(R.string.toast_habit_created)
            }

            is DeleteHabitsCommand -> {
                return activity.resources.getQuantityString(
                    R.plurals.toast_habits_deleted,
                    command.selected.size
                )
            }

            is EditHabitCommand -> {
                return activity.resources.getQuantityString(R.plurals.toast_habits_changed, 1)
            }

            is UnarchiveHabitsCommand -> {
                return activity.resources.getQuantityString(
                    R.plurals.toast_habits_unarchived,
                    command.selected.size
                )
            }

            else -> return null
        }
    }

    private fun onImportData(file: File, onFinished: () -> Unit) {
        taskRunner.execute(
            importTaskFactory.create(file) { result ->
                when (result) {
                    ImportDataTask.SUCCESS -> {
                        model.refresh()
                        postMessage(activity.resources.getString(R.string.habits_imported))
                    }

                    ImportDataTask.NOT_RECOGNIZED -> {
                        postMessage(activity.resources.getString(R.string.file_not_recognized))
                    }

                    else -> {
                        postMessage(activity.resources.getString(R.string.could_not_import))
                    }
                }
                onFinished()
            }
        )
    }

    private fun onExportDB() {
        taskRunner.execute(
            exportDBFactory.create { filename ->
                if (filename != null) {
                    showSendFileScreen(filename)
                } else {
                    postMessage(activity.resources.getString(R.string.could_not_export))
                }
            }
        )
    }
}

private const val EXPORT_PREFS = "export"
private const val EXPORT_KEY = "pending_export_path"
