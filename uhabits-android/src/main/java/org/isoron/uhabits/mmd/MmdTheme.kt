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

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.eInkColorScheme
import com.mudita.mmd.eInkTypography

/**
 * Mudita's [ThemeMMD] with the two fixes every screen needs, so they are made
 * in one place: MMD's type scale in **the phone's system font** rather than
 * the Lato MMD bundles (the owner's rule across all five Kompakt apps, from
 * 2026-09-15), and the six colour roles MMD 1.0.2 leaves unspecified filled
 * with white.
 *
 * Every Compose screen in the app is wrapped in this, never in a bare
 * `ThemeMMD`. Ported from the Macros/Fit kit (`ui/kit/MmdTheme.kt`).
 */
@Composable
fun MmdTheme(content: @Composable () -> Unit) {
    ThemeMMD(colorScheme = mmdColorScheme, typography = mmdTypography) {
        // The default for text that names no style (TextMMD, text fields,
        // inside buttons): without it TextMMD falls back to MMD's Lato.
        CompositionLocalProvider(LocalTextStyle provides mmdTypography.bodyLarge, content = content)
    }
}

/**
 * [eInkTypography]'s sizes, weights and line heights in the system font. Use
 * only the roles MMD defines; display* and headlineMedium/Small fall back to
 * the nearest MMD size rather than Material's.
 */
val mmdTypography: Typography =
    with(eInkTypography) {
        fun TextStyle.inSystemFont() = copy(fontFamily = FontFamily.Default)
        Typography(
            displayLarge = headlineLarge.inSystemFont(),
            displayMedium = headlineLarge.inSystemFont(),
            displaySmall = headlineLarge.inSystemFont(),
            headlineLarge = headlineLarge.inSystemFont(),
            headlineMedium = headlineLarge.inSystemFont(),
            headlineSmall = titleLarge.inSystemFont(),
            titleLarge = titleLarge.inSystemFont(),
            titleMedium = titleMedium.inSystemFont(),
            titleSmall = titleSmall.inSystemFont(),
            bodyLarge = bodyLarge.inSystemFont(),
            bodyMedium = bodyMedium.inSystemFont(),
            bodySmall = bodySmall.inSystemFont(),
            labelLarge = labelLarge.inSystemFont(),
            labelMedium = labelMedium.inSystemFont(),
            labelSmall = labelSmall.inSystemFont(),
        )
    }

/**
 * [eInkColorScheme] with the six roles MMD 1.0.2 leaves [Color.Unspecified]
 * set to white. Unfilled, `TextFieldMMD`, `TimeInputMMD`, `CardMMD` and
 * `SearchBarMMD` draw no container at all (see PROJECT-STATUS, "MMD's own
 * gaps").
 */
val mmdColorScheme: ColorScheme =
    eInkColorScheme.copy(
        surfaceBright = Color.White,
        surfaceDim = Color.White,
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color.White,
        surfaceContainerHighest = Color.White,
        surfaceContainerLowest = Color.White,
    )
