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

package org.isoron.uhabits

import android.app.Application
import android.content.Context
import org.isoron.uhabits.core.database.UnsupportedDatabaseVersionException
import org.isoron.uhabits.core.reminders.ReminderScheduler
import org.isoron.uhabits.core.ui.NotificationTray
import org.isoron.uhabits.core.utils.DateUtils.Companion.setStartDayOffset
import org.isoron.uhabits.inject.AppContextModule
import org.isoron.uhabits.inject.DaggerHabitsApplicationComponent
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.inject.HabitsModule
import org.isoron.uhabits.utils.DatabaseUtils
import java.io.File

/**
 * The Android application for Loop Habit Tracker.
 */
class HabitsApplication : Application() {

    private lateinit var context: Context
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var notificationTray: NotificationTray

    override fun onCreate() {
        super.onCreate()
        context = this

        if (isTestMode()) {
            val db = DatabaseUtils.getDatabaseFile(context)
            if (db.exists()) db.delete()
        }

        try {
            DatabaseUtils.initializeDatabase(context)
        } catch (e: UnsupportedDatabaseVersionException) {
            val db = DatabaseUtils.getDatabaseFile(context)
            db.renameTo(File(db.absolutePath + ".invalid"))
            DatabaseUtils.initializeDatabase(context)
        }

        val db = DatabaseUtils.getDatabaseFile(this)
        HabitsApplication.component = DaggerHabitsApplicationComponent
            .builder()
            .appContextModule(AppContextModule(context))
            .habitsModule(HabitsModule(db))
            .build()

        val prefs = component.preferences
        prefs.lastAppVersion = BuildConfig.VERSION_CODE

        if (prefs.isMidnightDelayEnabled) {
            setStartDayOffset(3, 0)
        } else {
            setStartDayOffset(0, 0)
        }

        // No widget bookkeeping. Every one of the six providers is
        // unregistered from the manifest, so there is no widget for any of
        // this to update -- and the scheduling half of it was not free: it
        // armed an exact `setExactAndAllowWhileIdle` alarm for the start of
        // tomorrow, which wakes the CPU out of Doze, and WidgetReceiver
        // re-armed the next one as it handled it. That made a daily wake, for
        // the life of the install, whose entire job was to redraw widgets that
        // cannot exist. Listening to commands is dropped for the same reason:
        // it ran twelve binder round-trips per checkmark to find no widgets.
        reminderScheduler = component.reminderScheduler
        reminderScheduler.startListening()

        notificationTray = component.notificationTray
        notificationTray.startListening()

        val taskRunner = component.taskRunner
        taskRunner.execute {
            // Recomputing every habit used to happen right here in onCreate,
            // on the main thread, before any Activity existed -- so it gated
            // first paint by however long it took to walk 41 habits and
            // several years of entries each. It cannot simply be dropped:
            // `SQLModelFactory` gives a habit a SQLite-backed
            // `originalEntries` but a plain in-memory `computedEntries` and
            // `ScoreList`, so both really are empty on a cold start and
            // something has to fill them.
            //
            // It is safe here because `AndroidTaskRunner` runs tasks on
            // AsyncTask's serial executor: `HabitCardListCache` queues its own
            // refresh through the same runner, so it cannot read a habit
            // before this has finished writing it, and there is no race to
            // guard. The visible trade is that a cold start can now paint an
            // empty list for a moment on a large database, where before it
            // painted nothing at all for longer.
            // Only the check-marks here, because only the check-marks are on
            // the way to first paint. AndroidTaskRunner runs on AsyncTask's
            // serial executor, so whatever this task does, the habits list's
            // own refresh waits behind it -- and the splash waits on that. A
            // full recompute walks every day from each habit's first entry to a
            // month out, which on years of history is seconds of work that the
            // grid does not draw.
            //
            // ListHabitsActivity finishes the job once it has something on
            // screen; see completeRecompute there. Scores read as zero until it
            // does, which is a moment of an incomplete number rather than
            // several seconds of no screen at all.
            for (h in component.habitList) h.recomputeEntries()
            reminderScheduler.scheduleAll()
        }
    }

    override fun onTerminate() {
        reminderScheduler.stopListening()
        notificationTray.stopListening()
        super.onTerminate()
    }

    val component: HabitsApplicationComponent
        get() = HabitsApplication.component

    companion object {
        lateinit var component: HabitsApplicationComponent

        fun isTestMode(): Boolean {
            return try {
                Class.forName("org.isoron.uhabits.BaseAndroidTest")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
    }
}
