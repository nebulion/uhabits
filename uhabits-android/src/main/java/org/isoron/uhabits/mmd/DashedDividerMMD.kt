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

package org.isoron.uhabits.mmd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The dotted hairline Mudita draws between list rows.
 *
 * MMD's own `DropdownMenuMMD` KDoc separates menu items with `DashedDivider()`,
 * which MMD 1.0.2 does not ship, so it lives here under MMD's name. Ported
 * from the Anki/Macros kit (`DashedDividerMMD.kt`).
 *
 * Measured on the Kompakt's Settings list: a 1px line of ~2.5px dots with
 * ~2.5px gaps, pure black on white, starting at the row label. Everything is
 * snapped to whole device pixels so the panel is never handed an anti-aliased
 * grey edge.
 *
 * The 3dp solid rule under headers stays `HorizontalDividerMMD`; this is only
 * for rows.
 */
@Composable
fun DashedDividerMMD(
    modifier: Modifier = Modifier,
    dash: Dp = 2.dp,
    gap: Dp = 2.dp,
    color: Color = MaterialTheme.colorScheme.outline,
) {
    // one device pixel tall, whatever the density
    val onePixel = with(LocalDensity.current) { (1 / density).dp }
    Canvas(modifier.fillMaxWidth().height(onePixel)) {
        val dashPx = dash.toPx().roundToInt().coerceAtLeast(1).toFloat()
        val gapPx = gap.toPx().roundToInt().coerceAtLeast(1).toFloat()
        // centre of the pixel row, so a 1px stroke covers exactly one row of pixels
        val y = floor(size.height / 2) + 0.5f
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
            cap = StrokeCap.Butt,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx)),
        )
    }
}
