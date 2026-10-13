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

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.tasks.TaskRunner

/**
 * Keeps the habits in sync with the shared folder without ever being asked.
 *
 * The owner's rule across the Kompakt apps: **sync is automatic and silent** --
 * no Sync button, no spinner, no message when it went through. So it runs:
 *
 * - **after every change** made here, a moment later ([AFTER_CHANGE_MS]) so a
 *   run of checkmark taps is one sync, not ten;
 * - **every [WHILE_OPEN_MS] while the app is on screen**, which is what brings
 *   in what the desktop wrote. With nothing to do a sync is a stamp check of
 *   about 140ms (see `docs/sync.md`), so this costs next to nothing;
 * - **once when the app leaves the screen**, so the last edits go out before
 *   the process can be killed.
 *
 * The habits list's own sync on resume stays as it is. All of these run on
 * the app's serial [TaskRunner], so two syncs never overlap. When one brings
 * something in, [onChanged] tells whichever screen is showing to redraw.
 * Failures are written to `sync-status.txt` by [AndroidSync.run] and are
 * otherwise silent; nothing is synced until a folder has been chosen.
 */
@MainThread
object AutoSync {
    private const val AFTER_CHANGE_MS = 2_000L
    private const val WHILE_OPEN_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Application
    private lateinit var habitList: () -> HabitList
    private lateinit var modelFactory: () -> ModelFactory
    private lateinit var taskRunner: TaskRunner
    private var started = 0
    private var running = false

    /** The screen showing, told when a sync brought something in. Set in onResume, cleared in onPause. */
    var onChanged: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            syncNow()
            handler.postDelayed(this, WHILE_OPEN_MS)
        }
    }
    private val afterChange = Runnable { syncNow() }

    fun install(
        application: Application,
        /** Read only when a sync runs, so installing costs the startup nothing. */
        habitList: () -> HabitList,
        modelFactory: () -> ModelFactory,
        taskRunner: TaskRunner,
        commandRunner: CommandRunner,
    ) {
        app = application
        this.habitList = habitList
        this.modelFactory = modelFactory
        this.taskRunner = taskRunner
        commandRunner.addListener(
            object : CommandRunner.Listener {
                override fun onCommandFinished(command: Command) {
                    handler.removeCallbacks(afterChange)
                    handler.postDelayed(afterChange, AFTER_CHANGE_MS)
                }
            },
        )
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (started++ == 0) handler.postDelayed(tick, WHILE_OPEN_MS)
                }

                override fun onActivityStopped(activity: Activity) {
                    // A screen change stops one activity after starting the
                    // next, so this reaches 0 only when the app leaves.
                    if (--started <= 0 && !activity.isChangingConfigurations) {
                        started = 0
                        handler.removeCallbacks(tick)
                        handler.removeCallbacks(afterChange)
                        syncNow()
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /** Syncs now, unless a sync is already queued or running. */
    fun syncNow() {
        if (running || AndroidSync.folderUri(app) == null) return
        running = true
        taskRunner.execute(
            object : org.isoron.uhabits.core.tasks.Task {
                private var changed = false

                override fun doInBackground() {
                    changed = try {
                        val report = AndroidSync.run(app, habitList(), modelFactory())
                        report != null && !report.isEmpty
                    } catch (e: Exception) {
                        Log.i("AutoSync", "sync failed: $e")
                        false
                    }
                }

                override fun onPostExecute() {
                    running = false
                    if (changed) onChanged?.invoke()
                }
            },
        )
    }
}
