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

package org.isoron.uhabits.core.ui.views

import org.isoron.platform.gui.Color

/**
 * The single theme for electrophoretic (E Ink) displays.
 *
 * There is deliberately no dark variant and no habit palette. An E Ink panel
 * is reflective, so it has no backlight to dim and nothing to gain from an
 * inverted theme; a black page also costs a full-screen repaint of every pixel
 * on every update. Habit colour is dropped for the same kind of reason -- the
 * panel cannot render hue at all, so a colour would arrive as an arbitrary
 * grey that dithers, ghosts, and tells the reader nothing.
 *
 * What replaces them, following Mudita's E Ink guidelines:
 *
 *  - "Avoid grey": surfaces are pure white and ink is pure black. The single
 *    intermediate tone, [lowContrastTextColor], is spent on structure --
 *    gridlines and unfilled checkmarks -- and never on text.
 *  - "Follow the lines": hierarchy and grouping are drawn with rules rather
 *    than fills, shadows or elevation.
 *  - Typographic hierarchy comes from size and weight, since tone is no
 *    longer available to carry it.
 */
open class EInkTheme : Theme() {
    override val appBackgroundColor = Color(0xFFFFFF)
    override val cardBackgroundColor = Color(0xFFFFFF)
    override val headerBackgroundColor = Color(0xFFFFFF)
    override val itemBackgroundColor = Color(0xFFFFFF)
    override val toolbarBackgroundColor = Color(0xFFFFFF)
    override val toolbarColor = Color(0xFFFFFF)
    override val statusBarBackgroundColor = Color(0xFFFFFF)

    // Borders are structural and must read as clearly as text.
    override val headerBorderColor = Color(0x000000)

    // Text is black at every level; hierarchy is carried by size and weight.
    override val headerTextColor = Color(0x000000)
    override val highContrastTextColor = Color(0x000000)
    override val mediumContrastTextColor = Color(0x000000)

    // The one recessive tone, reserved for gridlines and unfilled checkmarks.
    override val lowContrastTextColor = Color(0xBDBDBD)

    // Refreshes are slow enough that a mistapped checkmark is expensive to
    // undo, so targets are larger than the Material default. Type is larger
    // because the panel has no subpixel rendering to lean on.
    override val checkmarkButtonSize = 56.0
    override val smallTextSize = 12.0
    override val regularTextSize = 18.0

    /**
     * Every habit is drawn in the same black ink. The palette index is kept in
     * the data model so that existing databases still load and so that the app
     * can be switched back to a colour theme, but it has no visual effect.
     */
    override fun color(paletteIndex: Int) = Color(0x000000)
}

/**
 * Homescreen widgets. E Ink launchers composite widgets onto the wallpaper,
 * so the background stays transparent while the ink stays fully opaque.
 */
class EInkWidgetTheme : EInkTheme() {
    override val cardBackgroundColor = Color.TRANSPARENT
}
