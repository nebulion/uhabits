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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.isoron.platform.gui.EinkRefresh
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

    /** Bumped by every change a page shows elsewhere, so its rows are read again. */
    private var version by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val component = (application as HabitsApplication).component
        AndroidThemeSwitcher(this, component.preferences).apply()
        prefs = component.preferences

        setContent {
            // Which page is open; the first page when null. Saved, so it
            // survives the recreate a first-weekday change does, and "Don't
            // keep activities".
            var page by rememberSaveable { mutableStateOf<Page?>(null) }
            BackHandler(enabled = page != null) { page = null }
            // Read here so a change recomposes the page.
            val rows = version.let {
                when (page) {
                    null -> homeRows { page = it }
                    Page.INTERFACE -> interfaceRows()
                    Page.EINK -> einkRows()
                    Page.REMINDERS -> reminderRows()
                    Page.SYNC -> syncRows()
                    Page.DATA -> dataRows()
                }
            }
            SettingsScreenMMD(
                title = getString(page?.titleRes ?: R.string.settings),
                rows = rows,
                onNavigateBack = { if (page != null) page = null else finish() },
            )
        }
    }

    /** The pages Settings opens onto, in the order the first page lists them. */
    enum class Page(val titleRes: Int) {
        INTERFACE(R.string.interface_preferences),
        EINK(R.string.eink_settings),
        REMINDERS(R.string.reminder),
        SYNC(R.string.sync),
        DATA(R.string.data_settings),
    }

    private fun changed() {
        version++
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

    /**
     * The first page: one row per group, each with what it currently holds
     * under its name where that can be said in a few words.
     */
    private fun homeRows(open: (Page) -> Unit): List<SettingsRow> = buildList {
        add(SettingsRow.Page(getString(R.string.interface_preferences), onClick = { open(Page.INTERFACE) }))
        add(
            SettingsRow.Page(
                title = getString(R.string.eink_settings),
                value = einkSummary(),
                onClick = { open(Page.EINK) },
            )
        )
        add(SettingsRow.Page(getString(R.string.reminder), onClick = { open(Page.REMINDERS) }))
        add(
            SettingsRow.Page(
                title = getString(R.string.sync),
                value = AndroidSync.folderLabel(this@SettingsActivity)
                    ?: getString(R.string.sync_folder_summary_unset),
                onClick = { open(Page.SYNC) },
            )
        )
        add(SettingsRow.Page(getString(R.string.data_settings), onClick = { open(Page.DATA) }))
        // All that is left of the About screen: the version, as a line of text
        // in the shape of the rows around it.
        add(SettingsRow.Info(getString(R.string.version_n, BuildConfig.VERSION_NAME)))
    }

    private fun einkSummary(): String =
        if (prefs.isEinkRefreshEnabled) {
            getString(R.string.eink_refresh_every_n, prefs.einkRefreshInterval)
        } else {
            getString(R.string.eink_refresh_off)
        }

    private fun interfaceRows(): List<SettingsRow> = buildList {
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
    }

    /**
     * The flash that clears ghosting: on or off, and how many taps apart. The
     * same page, with the same defaults (on, every 12), in all five of the
     * owner's Kompakt apps.
     */
    private fun einkRows(): List<SettingsRow> = buildList {
        add(
            SettingsRow.Toggle(
                title = getString(R.string.pref_eink_refresh_title),
                checked = prefs.isEinkRefreshEnabled,
                onToggle = {
                    prefs.isEinkRefreshEnabled = it
                    changed()
                },
            )
        )
        if (prefs.isEinkRefreshEnabled) {
            val choices = EinkRefresh.INTERVAL_CHOICES
            add(
                SettingsRow.Choice(
                    title = getString(R.string.pref_eink_refresh_interval_title),
                    options = choices.map { getString(R.string.eink_refresh_every_n, it) },
                    selectedIndex = choices.indexOf(prefs.einkRefreshInterval),
                    onSelect = { index ->
                        prefs.einkRefreshInterval = choices[index]
                        changed()
                    },
                )
            )
        }
    }

    private fun reminderRows(): List<SettingsRow> = buildList {
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
    }

    private fun syncRows(): List<SettingsRow> = buildList {
        val folder = AndroidSync.folderLabel(this@SettingsActivity)

        // Shared storage first, because without it a folder on /sdcard cannot
        // be opened as a path at all and picking one would achieve nothing.
        if (!AndroidSync.hasAllFilesAccess()) {
            add(
                SettingsRow.Action(
                    title = getString(R.string.sync_grant_access),
                    onClick = { setResultAndFinish(RESULT_GRANT_FILE_ACCESS) },
                )
            )
        }
        // One row, not two. Choosing the folder and seeing which one is chosen
        // are the same question.
        add(
            SettingsRow.Action(
                title = getString(R.string.sync_folder),
                value = folder ?: getString(R.string.sync_folder_summary_unset),
                onClick = { setResultAndFinish(RESULT_PICK_SYNC_FOLDER) },
            )
        )
        if (folder != null) {
            add(
                SettingsRow.Action(
                    title = getString(R.string.sync_forget_folder),
                    onClick = { setResultAndFinish(RESULT_FORGET_SYNC_FOLDER) },
                )
            )
        }
    }

    /**
     * Backups, imports and the repair, which used to be two sections
     * (Database, Troubleshooting). No "Generate bug report": it is only useful
     * if it can be mailed or filed, and this device has no mail client or
     * browser.
     */
    private fun dataRows(): List<SettingsRow> = buildList {
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
