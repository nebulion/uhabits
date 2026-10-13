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

package org.isoron.uhabits.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps a copy of the habits database where `adb pull` can reach it, written
 * whenever the app goes to the background (UI-PATTERNS E3, from the Fit app).
 *
 * The database is the only copy of years of entries, and on a release build
 * `run-as` cannot read it -- so until now a backup meant installing the debug
 * build, pulling, and reinstalling. This writes
 * `/sdcard/Android/data/org.isoron.uhabits/files/uhabits.db`, which needs no
 * permission to write and none but adb to read:
 *
 * ```
 * adb pull /sdcard/Android/data/org.isoron.uhabits/files/uhabits.db
 * ```
 *
 * It is a whole, consistent SQLite file (`VACUUM INTO`, never a raw copy of a
 * file another thread may be writing), so it opens in any SQLite tool and
 * imports back through Settings -> Data -> Import. It is skipped when nothing
 * has changed since the last copy, and written to a temporary name first, so
 * a copy cut short never replaces a good one.
 *
 * Note it lives in the app's own external folder, so **uninstalling the app
 * deletes it** -- pull it before an uninstall.
 */
object DataCopy {
    private const val NAME = "uhabits.db"
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "data-copy") }
    private var started = 0

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    started++
                }

                override fun onActivityStopped(activity: Activity) {
                    started--
                    // A screen change stops one activity after starting the
                    // next, so this reaches 0 only when the app leaves the
                    // screen.
                    if (started <= 0 && !activity.isChangingConfigurations) {
                        started = 0
                        executor.execute { write(application) }
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

    private fun write(application: Application) {
        try {
            val dir = application.getExternalFilesDir(null) ?: return
            val source = DatabaseUtils.getDatabaseFile(application)
            val target = File(dir, NAME)
            // Nothing written since the last copy: nothing to do.
            if (target.exists() && target.lastModified() >= source.lastModified()) return
            val temp = File(dir, "$NAME.tmp")
            temp.delete()
            DatabaseUtils.openDatabase().execSQL("VACUUM INTO ?", arrayOf(temp.absolutePath))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            Log.i("DataCopy", "Wrote ${target.absolutePath} (${target.length()} bytes)")
        } catch (e: Exception) {
            Log.i("DataCopy", "Could not write the data copy: $e")
        }
    }
}
