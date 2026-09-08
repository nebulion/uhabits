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

package org.isoron.uhabits.activities.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import org.isoron.uhabits.BuildConfig
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.activities.habits.list.RESULT_EXPORT_CSV
import org.isoron.uhabits.activities.habits.list.RESULT_EXPORT_DB
import org.isoron.uhabits.activities.habits.list.RESULT_IMPORT_DATA
import org.isoron.uhabits.activities.habits.list.RESULT_FORGET_SYNC_FOLDER
import org.isoron.uhabits.activities.habits.list.RESULT_PICK_SYNC_FOLDER
import org.isoron.uhabits.activities.habits.list.RESULT_REPAIR_DB
import org.isoron.uhabits.activities.habits.list.RESULT_GRANT_FILE_ACCESS
import org.isoron.uhabits.activities.habits.list.RESULT_SYNC_NOW
import org.isoron.uhabits.activities.habits.list.RESULT_USE_DEFAULT_SYNC_FOLDER
import org.isoron.uhabits.sync.AndroidSync
import org.isoron.uhabits.notifications.AndroidNotificationTray.Companion.createAndroidNotificationChannel
import org.isoron.uhabits.core.ui.NotificationTray

/**
 * Settings, hosted as Compose and built from Mudita Mindful Design.
 *
 * This replaces the AndroidX [androidx.preference.PreferenceFragmentCompat]
 * screen. That framework draws its own rows, dividers, summaries and switches,
 * none of which can be made into MMD components -- the best that could be done
 * with it was to style the Material widgets to resemble them. Building the
 * list directly means the switches really are [com.mudita.mmd.components.switcher.SwitchMMD]
 * and the rules really are MMD dividers.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val component = (application as HabitsApplication).component
        AndroidThemeSwitcher(this, component.preferences).apply()
        prefs = component.preferences

        setContent {
            SettingsScreenMMD(
                rows = buildRows(),
                onNavigateBack = { finish() },
            )
        }
    }

    /**
     * Weekday names in the preference's own order: Sunday first, because
     * `pref_first_weekday` stores 1 for Sunday through 7 for Saturday. Taken
     * from the calendar rather than hard-coded so they arrive translated.
     */
    private val weekdayNames: List<String>
        get() {
            val symbols = java.text.DateFormatSymbols.getInstance().weekdays
            // `weekdays` is 1-based with an empty slot at index 0, which lines
            // up with the preference exactly.
            return (1..7).map { symbols[it] }
        }

    private fun buildRows(): List<SettingsRow> = buildList {
        add(SettingsRow.Header(getString(R.string.interface_preferences)))
        add(
            SettingsRow.Toggle(
                title = getString(R.string.pref_toggle_title),
                checked = prefs.isShortToggleEnabled,
                onToggle = { prefs.isShortToggleEnabled = it },
            )
        )
        add(
            SettingsRow.Toggle(
                title = getString(R.string.pref_skip_title),
                checked = prefs.isSkipEnabled,
                onToggle = { prefs.isSkipEnabled = it },
            )
        )
        add(
            SettingsRow.Toggle(
                title = getString(R.string.pref_unknown_title),
                checked = prefs.areQuestionMarksEnabled,
                onToggle = { prefs.areQuestionMarksEnabled = it },
            )
        )
        add(
            SettingsRow.Toggle(
                title = getString(R.string.pref_eink_refresh_title),
                checked = prefs.isEinkRefreshEnabled,
                onToggle = { prefs.isEinkRefreshEnabled = it },
            )
        )
        // Restored below: these were lost when the preference XML was gutted,
        // not removed on purpose. Both preferences survived and are still
        // read -- `isMidnightDelayEnabled` at every launch, and
        // `shouldMakeNotificationsSticky` on every reminder -- so each was in
        // force with no way left to change it.
        add(
            SettingsRow.Choice(
                title = getString(R.string.first_weekday),
                options = weekdayNames,
                // The preference is 1 for Sunday through 7 for Saturday, and
                // reads the locale's own answer when it was never set. The
                // list is in that order, so the index is one less.
                selectedIndex = (prefs.firstWeekdayInt - 1).coerceIn(0, 6),
                onSelect = { index ->
                    prefs.setFirstWeekday(index + 1)
                    recreate()
                },
            )
        )
        add(
            SettingsRow.Toggle(
                title = getString(R.string.pref_midnight_delay_title),
                checked = prefs.isMidnightDelayEnabled,
                onToggle = { prefs.isMidnightDelayEnabled = it },
            )
        )
        add(SettingsRow.Header(getString(R.string.reminder)))
        add(
            SettingsRow.Toggle(
                title = getString(R.string.sticky_notifications),
                checked = prefs.shouldMakeNotificationsSticky(),
                onToggle = { prefs.setNotificationsSticky(it) },
            )
        )
        add(
            SettingsRow.Action(
                title = getString(R.string.customize_notification),
                onClick = {
                    createAndroidNotificationChannel(this@SettingsActivity)
                    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        putExtra(Settings.EXTRA_CHANNEL_ID, NotificationTray.REMINDERS_CHANNEL_ID)
                    }
                    startActivity(intent)
                },
            )
        )

        add(SettingsRow.Header(getString(R.string.about)))
        // All that is left of the About screen. It held a version number and a
        // contributor list behind an activity nothing linked to -- the habits
        // list lost its menu, and `onViewAbout` had no caller anywhere -- so
        // the screen is deleted and the one fact worth keeping moved here.
        // A row with no `onClick` is not a control; it is a line of text in the
        // shape of the rows around it.
        add(
            SettingsRow.Action(
                title = getString(R.string.version_n, BuildConfig.VERSION_NAME),
                onClick = {},
            )
        )

        // Sync sits above Database because it is the thing that runs by
        // itself; the export and import rows below it are the manual escape
        // hatches for when it is not set up.
        add(SettingsRow.Header(getString(R.string.sync)))
        val folder = AndroidSync.folderLabel(this@SettingsActivity)

        // The direct route comes first. Reaching /sdcard/Sync through the file
        // picker means navigating DocumentsUI on a panel that repaints slowly;
        // granting shared-storage access once is a single system toggle, after
        // which the folder is opened as an ordinary path.
        if (!AndroidSync.hasAllFilesAccess()) {
            add(
                SettingsRow.Action(
                    title = getString(R.string.sync_grant_access),
                    onClick = { setResultAndFinish(RESULT_GRANT_FILE_ACCESS) },
                )
            )
        }
        add(
            SettingsRow.Action(
                title = getString(R.string.sync_use_default, AndroidSync.defaultFolder.path),
                onClick = { setResultAndFinish(RESULT_USE_DEFAULT_SYNC_FOLDER) },
            )
        )
        add(
            SettingsRow.Action(
                title = if (folder == null) {
                    getString(R.string.sync_folder) + " — " +
                        getString(R.string.sync_folder_summary_unset)
                } else {
                    getString(R.string.sync_folder) + " — " + folder
                },
                onClick = { setResultAndFinish(RESULT_PICK_SYNC_FOLDER) },
            )
        )
        if (folder != null) {
            add(
                SettingsRow.Action(
                    title = getString(R.string.sync_now),
                    onClick = { setResultAndFinish(RESULT_SYNC_NOW) },
                )
            )
            add(
                SettingsRow.Action(
                    title = getString(R.string.sync_forget_folder),
                    onClick = { setResultAndFinish(RESULT_FORGET_SYNC_FOLDER) },
                )
            )
        }

        add(SettingsRow.Header(getString(R.string.database)))
        add(
            SettingsRow.Action(
                title = getString(R.string.export_full_backup),
                onClick = { setResultAndFinish(RESULT_EXPORT_DB) },
            )
        )
        add(
            SettingsRow.Action(
                title = getString(R.string.export_to_csv),
                onClick = { setResultAndFinish(RESULT_EXPORT_CSV) },
            )
        )
        add(
            SettingsRow.Action(
                title = getString(R.string.import_data),
                onClick = { setResultAndFinish(RESULT_IMPORT_DATA) },
            )
        )

        // No "Generate bug report" and no About section. The bug report is only
        // useful if it can be mailed or filed somewhere, and neither the About
        // screen's contents nor its links have anywhere to go on a device with
        // no browser, store or mail client.
        add(SettingsRow.Header(getString(R.string.troubleshooting)))
        add(
            SettingsRow.Action(
                title = getString(R.string.repair_database),
                onClick = { setResultAndFinish(RESULT_REPAIR_DB) },
            )
        )
    }

    private fun setResultAndFinish(code: Int) {
        setResult(code)
        finish()
    }
}
