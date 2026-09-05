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
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.isoron.uhabits.core.preferences.Preferences
import java.util.concurrent.atomic.AtomicInteger

/**
 * Clears E Ink ghosting by flashing the screen black for a moment.
 *
 * **This is a heuristic, and it is worth being honest about why.** The Kompakt
 * does expose an e-ink control surface — a `meink` system service, a root-only
 * `/sys/einkinfo/` tree, and an undocumented `kompakt-sdk.jar` carrying
 * `MeinkManager`, `RefreshMode` and `WaveformMode`. None of it is usable here:
 * every method on `MeinkManager` sets a *persistent display mode*, and not one
 * of them is a one-shot "flash the panel now". Whether an ordinary app is even
 * permitted to reach the service is unverified — SELinux labels it
 * `mudita_service` and `adb shell` runs in a different domain, so the only way
 * to find out is to ship a build that tries.
 *
 * So this does what every e-ink launcher does instead, InkOS included: it puts
 * an opaque full-screen view over the window for [FLASH_MS] and takes it away
 * again. It works because a full-screen maximum-delta repaint pushes most EPD
 * drivers into selecting a fuller waveform on their own — which this app can
 * neither inspect nor force. It reliably reduces *visible* ghosting; it is not
 * a true panel-level full refresh, and it should not be described as one.
 *
 * It needs no permission, no hidden API and no `uses-library`, which is the
 * other reason to prefer it: it will still work after an OS update.
 */
class EinkRefresh(private val prefs: Preferences) {

    private val pending = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Records one change, and flashes once enough of them have accumulated.
     *
     * "One change" means one thing the owner did that repainted part of the
     * panel — a checkmark toggled, a habit saved. It deliberately does not
     * count redraws, which would fire constantly and mean nothing.
     */
    fun onChange(activity: Activity?) {
        if (!prefs.isEinkRefreshEnabled) return
        if (pending.incrementAndGet() < prefs.einkRefreshInterval) return
        pending.set(0)
        flash(activity)
    }

    /** Flashes now, regardless of the counter. */
    fun flash(activity: Activity?) {
        val root = activity?.window?.decorView as? ViewGroup ?: return
        handler.post {
            val overlay = View(activity).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
            root.addView(overlay)
            overlay.bringToFront()
            handler.postDelayed({ root.removeView(overlay) }, FLASH_MS)
        }
    }

    companion object {
        /**
         * How long the black frame stays up.
         *
         * InkOS defaults to the same 100ms and allows 25–1500. Below about
         * 25ms the panel may not complete the update at all; well above 100ms
         * it stops reading as a refresh and starts reading as a fault.
         */
        private const val FLASH_MS = 100L

        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
