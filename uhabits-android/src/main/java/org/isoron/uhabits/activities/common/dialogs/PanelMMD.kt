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

package org.isoron.uhabits.activities.common.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldColorsMMD
import com.mudita.mmd.components.text_field.TextFieldDefaultsMMD

/**
 * The panel every popup in this app sits in: MMD's dialog (zeroheight
 * "Dialog", and the Kompakt's own Notes delete prompt, pattern P5 in
 * MMD-GUIDE).
 *
 * **MMD ships no dialog component**, so the container is ours, built from MMD
 * parts to zeroheight's measurements: anchored to the **bottom** of the screen
 * at full width, a 3dp black rule and 2dp of white across its top, then 24dp
 * above the content, 12dp at the sides and below. The heading and body are
 * centred; the buttons are stacked full width with the solid one on top. No
 * border, no corners, no elevation, no dim.
 *
 * It used to be a bordered box floating in the middle of the screen, which is
 * how no Mudita app draws a dialog. Ported from the Macros/Fit kit
 * (`ui/kit/Panel.kt`), which had itself started as a port of the old version
 * of this file.
 */
@Composable
fun PanelMMD(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDividerMMD(thickness = RULE_THICKNESS, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(RULE_GAP))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}

/** A dialog's heading: Black 25sp on 28sp lines, centred (zeroheight). */
@Composable
fun PanelTitle(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 25.sp, lineHeight = 28.sp),
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

/** A dialog's body: Medium 21sp on 25sp lines, centred. Never small grey text. */
@Composable
fun PanelBody(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, lineHeight = 25.sp),
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A dialog's buttons, stacked full width 12dp apart below 24dp of space.
 * Callers write the escape first and the committing action last; the last is
 * placed on top, as MMD puts the solid button above the outlined one.
 */
@Composable
fun PanelActions(content: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    Layout(
        content = content,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }.reversed()
        val height = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            placeables.forEach {
                it.placeRelative(0, y)
                y += it.height + gap
            }
        }
    }
}

/** The one action that commits: solid black, bold label knocked out in white. */
@Composable
fun PanelPrimaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ButtonMMD(
        onClick = onClick,
        modifier = modifier.heightIn(min = BUTTON_HEIGHT),
        shape = BUTTON_SHAPE,
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

/** Everything beside it: black outline, no fill. */
@Composable
fun PanelSecondaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButtonMMD(
        onClick = onClick,
        modifier = modifier.heightIn(min = BUTTON_HEIGHT),
        shape = BUTTON_SHAPE,
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

/**
 * `TextFieldMMD`'s own colours, with the container stated rather than left to
 * chance: its default resolves to `surfaceContainerHighest`, which MMD's
 * `eInkColorScheme` leaves unspecified. `MmdTheme` fills that role too; this
 * stays so a field is right even outside it.
 */
@Composable
fun panelTextFieldColors(): TextFieldColorsMMD {
    val white = MaterialTheme.colorScheme.surface
    return TextFieldDefaultsMMD.colors(
        focusedContainerColor = white,
        unfocusedContainerColor = white,
        disabledContainerColor = white,
        errorContainerColor = white,
    )
}

/** `DividerDefaultsMMD.Thickness`: the rule across a dialog's top. */
private val RULE_THICKNESS = 3.dp

/** The white gap zeroheight puts under that rule. */
private val RULE_GAP = 2.dp

/**
 * Measured off the Kompakt's Notes delete prompt: buttons 75px (56dp) tall.
 * Taller than MMD's 32dp minimum because a mistap on E Ink costs a repaint.
 */
private val BUTTON_HEIGHT = 56.dp

/** `ButtonDefaultsMMD.buttonCornerRadius`. */
private val BUTTON_SHAPE = RoundedCornerShape(8.dp)
