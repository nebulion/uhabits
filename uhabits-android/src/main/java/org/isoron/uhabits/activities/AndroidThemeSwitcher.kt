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

package org.isoron.uhabits.activities

import android.app.Activity
import android.content.Context
import androidx.core.content.ContextCompat
import org.isoron.uhabits.R
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.ui.views.EInkTheme
import org.isoron.uhabits.core.ui.views.Theme
import org.isoron.uhabits.inject.ActivityContext
import org.isoron.uhabits.inject.ActivityScope

@ActivityScope
class AndroidThemeSwitcher
constructor(
    @ActivityContext val context: Context,
    preferences: Preferences
) : ThemeSwitcher(preferences) {

    override var currentTheme: Theme = EInkTheme()

    /**
     * The system's own night setting is deliberately ignored. The app is
     * white-paper-only, and `forceDarkAllowed` is off in the theme so that
     * the platform cannot invert it either.
     */
    override fun applyTheme() {
        currentTheme = EInkTheme()
        context.setTheme(R.style.AppBaseTheme)
        (context as Activity).window.navigationBarColor =
            ContextCompat.getColor(context, R.color.white)
    }
}
