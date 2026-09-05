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
package org.isoron.uhabits.core.ui

import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.views.Theme

/**
 * Applies the app's single theme.
 *
 * The dark and pure-black themes, the night-mode toggle and the follow-the-
 * system behaviour were all removed when the app moved to an E Ink design.
 * A reflective panel has no backlight to dim, so an inverted theme buys the
 * reader nothing while costing a full-screen repaint on every update.
 *
 * The class is kept rather than folded into its caller because the platform
 * still has to be told which resources to install, and the charts still need
 * somewhere to read [currentTheme] from.
 */
abstract class ThemeSwitcher(private val preferences: Preferences) {
    fun apply() = applyTheme()

    abstract fun applyTheme()
    abstract val currentTheme: Theme?

    companion object {
        /**
         * Retained because [Preferences.theme] persists it and older
         * databases still have a value stored under that key.
         */
        const val THEME_AUTOMATIC = 0
    }
}
