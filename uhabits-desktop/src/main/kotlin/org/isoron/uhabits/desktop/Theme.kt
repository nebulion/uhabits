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
package org.isoron.uhabits.desktop

import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.jline.utils.AttributedStyle

/**
 * Gruvbox, as 256-colour indices.
 *
 * Indices rather than 24-bit RGB on purpose: `AttributedStyle.foreground(r,g,b)`
 * emits a truecolour escape that a surprising number of terminals -- and most
 * `TERM=screen` multiplexer setups -- silently render as the nearest ANSI 8,
 * which turns this palette into primary colours. The 256-colour indices below
 * are the standard gruvbox approximations and degrade honestly.
 *
 * The background is left alone throughout. A terminal running gruvbox already
 * has the right background, and painting our own would fight anyone whose
 * scheme differs.
 */
/**
 * Gruvbox dark as plain hex, with no toolkit attached.
 *
 * The terminal client reaches its colours as 256-colour indices and the window
 * as AWT colours; these are the numbers both of them are approximating, kept
 * in one place so the two clients cannot drift apart on what "done" looks like.
 */
object GruvboxPalette {
    const val BG0 = 0x282828 // page
    const val BG1 = 0x3C3836 // toolbar, header
    const val BG2 = 0x504945 // rules, selection
    const val FG = 0xEBDBB2 // primary text
    const val GRAY = 0x928374 // secondary text
    const val RED = 0xFB4934
    const val GREEN = 0xB8BB26
    const val YELLOW = 0xFABD2F
    const val BLUE = 0x83A598
    const val PURPLE = 0xD3869B
    const val AQUA = 0x8EC07C
    const val ORANGE = 0xFE8019
}

object Gruvbox {
    private fun fg(index: Int) = AttributedStyle.DEFAULT.foreground(index)

    val fg0 = fg(223) // #ebdbb2 foreground
    val gray = fg(245) // #928374 comment grey
    val darkGray = fg(239) // #504945 faint rules
    val red = fg(167) // #fb4934
    val green = fg(142) // #b8bb26
    val yellow = fg(214) // #fabd2f
    val blue = fg(109) // #83a598
    val purple = fg(175) // #d3869b
    val aqua = fg(108) // #8ec07c
    val orange = fg(208) // #fe8019

    /**
     * Colour carries the same distinction the glyphs do, so the grid still
     * reads correctly in a terminal with no colour at all, or to someone who
     * cannot separate red from green.
     */
    fun forValue(habit: Habit, value: Int): AttributedStyle = when {
        habit.isNumerical && value >= 0 -> yellow
        value == Entry.YES_MANUAL -> green
        value == Entry.YES_AUTO -> aqua
        value == Entry.NO -> red
        value == Entry.SKIP -> blue
        else -> darkGray
    }
}
