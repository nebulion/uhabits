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

package org.isoron.platform.gui

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.MainThread
import org.isoron.uhabits.core.preferences.Preferences

/**
 * Clears E Ink ghosting by flashing the screen black for a moment.
 *
 * **This is a heuristic, and it is worth being honest about why.** The Kompakt
 * does expose an e-ink control surface -- a `meink` system service, a root-only
 * `/sys/einkinfo/` tree, and an undocumented `kompakt-sdk.jar` carrying
 * `MeinkManager`, `RefreshMode` and `WaveformMode`. None of it is usable here:
 * every method on `MeinkManager` sets a *persistent display mode*, and not one
 * of them is a one-shot "flash the panel now".
 *
 * So this does what every e-ink launcher does instead, InkOS included: it puts
 * an opaque full-screen view over the window for [FLASH_MS] and takes it away
 * again. A full-screen maximum-delta repaint pushes the EPD driver into a
 * fuller waveform on its own. It needs no permission, no hidden API and no
 * `uses-library`, so it keeps working after an OS update.
 *
 * **What it counts: every lifted finger, in every activity.** A tap, a swipe,
 * a page turn, a drop -- each is one thing the owner did to the panel. It used
 * to count *commands* and *pages scrolled* on the habits list only, which left
 * ShowHabit, Settings and the editor uncounted, and it was never once seen to
 * fire. This is the Fit/Macros kit's version (`ui/kit/EinkRefresh.kt`), the
 * same rule in all five Kompakt apps: on by default, every 12. It flashes a
 * moment after the counted touch, so the screen that touch opened is drawn
 * first and is what the flash clears. Taps inside a popup, which is a window
 * of its own, are not counted.
 *
 * The setting is Settings -> Flash screen to clear ghosting, and the interval
 * beside it; both live in [Preferences].
 */
@MainThread
object EinkRefresh {
    /**
     * How long the black frame stays up. InkOS defaults to the same 100ms and
     * allows 25-1500. Below about 25ms the panel may not complete the update
     * at all; well above 100ms it stops reading as a refresh and starts
     * reading as a fault.
     */
    private const val FLASH_MS = 100L

    /** Long enough for the screen a tap opens to be drawn before the flash clears it. */
    private const val AFTER_ACTION_MS = 400L

    /** The intervals Settings offers; 12 is the default in [Preferences]. */
    val INTERVAL_CHOICES = listOf(6, 8, 12, 16, 24)

    private val handler = Handler(Looper.getMainLooper())
    private var pending = 0
    private var prefs: Preferences? = null

    /** Counts every tap and swipe in every activity of [application]. */
    fun install(application: Application, preferences: Preferences) {
        prefs = preferences
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    val window = activity.window ?: return
                    val callback = window.callback ?: return
                    // AppCompat sets its own callback while the activity is
                    // created: wrap on top of it, once.
                    if (callback !is CountingCallback) {
                        window.callback = CountingCallback(callback, activity)
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /** Records one lifted finger; true when it completes the count and the screen should flash. */
    private fun countAction(): Boolean {
        val prefs = prefs ?: return false
        if (!prefs.isEinkRefreshEnabled) return false
        if (++pending < prefs.einkRefreshInterval) return false
        pending = 0
        return true
    }

    /** Flashes regardless of the count and of the setting, after [delayMs]. */
    fun flash(activity: Activity?, delayMs: Long = 0) {
        val root = activity?.window?.decorView as? ViewGroup ?: return
        handler.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            val overlay = View(activity).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
            root.addView(overlay)
            overlay.bringToFront()
            handler.postDelayed({ root.removeView(overlay) }, FLASH_MS)
        }, delayMs)
    }

    /** Passes everything on to the activity's own callback, counting each lifted finger. */
    private class CountingCallback(
        private val delegate: Window.Callback,
        private val activity: Activity,
    ) : Window.Callback by delegate {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val handled = delegate.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && countAction()) {
                flash(activity, AFTER_ACTION_MS)
            }
            return handled
        }
    }

    private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
}
