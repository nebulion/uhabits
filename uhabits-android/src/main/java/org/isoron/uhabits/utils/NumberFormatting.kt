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

import java.text.DecimalFormat

/**
 * A number narrowed to fit a chart label or a button face: thousands become
 * `k`, millions `M`, billions `G`, and small values keep at most two decimals.
 *
 * This used to live in `NumberButtonView`, which was deleted along with the
 * rest of the habits-list View classes when that screen moved to Compose. It
 * outlived them because the ShowHabit cards, `TargetChart` and the widget views
 * all format numbers the same way.
 */
fun Double.toShortString(): String = when {
    this >= 1e9 -> String.format("%.1fG", this / 1e9)
    this >= 1e8 -> String.format("%.0fM", this / 1e6)
    this >= 1e7 -> String.format("%.1fM", this / 1e6)
    this >= 1e6 -> String.format("%.1fM", this / 1e6)
    this >= 1e5 -> String.format("%.0fk", this / 1e3)
    this >= 1e4 -> String.format("%.1fk", this / 1e3)
    this >= 1e3 -> String.format("%.1fk", this / 1e3)
    this >= 1e2 -> DecimalFormat("#").format(this)
    this >= 1e1 -> DecimalFormat("#.#").format(this)
    else -> DecimalFormat("#.##").format(this)
}
