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
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.activities.habits.list.RESULT_EXPORT_CSV
import org.isoron.uhabits.activities.habits.list.RESULT_EXPORT_DB
import org.isoron.uhabits.activities.habits.list.RESULT_IMPORT_DATA
import org.isoron.uhabits.activities.habits.list.RESULT_REPAIR_DB
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
        add(SettingsRow.Header(getString(R.string.reminder)))
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
