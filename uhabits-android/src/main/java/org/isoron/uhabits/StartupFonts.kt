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

import android.content.Context
import android.graphics.Typeface
import org.isoron.uhabits.utils.traced

/**
 * The typefaces the first screen draws with, loaded before it asks for them.
 *
 * Compose loads a font the first time text needs it, synchronously, inside
 * composition. The text is in the system font now (MmdTheme), which the
 * platform has already loaded, so what is left is FontAwesome for the glyphs:
 * parsed here, on a thread of their own that starts with the process and runs
 * beside `bindApplication` and the activity's creation. It comes from assets,
 * which has no cache, so the screen takes it from [fontAwesome] directly.
 */
object StartupFonts {

    private val lock = Object()
    private var awesome: Typeface? = null

    fun preload(context: Context) {
        val app = context.applicationContext
        Thread({
            traced("preload fonts") {
                fontAwesome(app)
            }
        }, "font-preload").start()
    }

    /** FontAwesome, loaded at most once per process, whichever thread asks first. */
    fun fontAwesome(context: Context): Typeface = synchronized(lock) {
        awesome ?: Typeface.createFromAsset(context.assets, "fontawesome-webfont.ttf")
            .also { awesome = it }
    }
}
