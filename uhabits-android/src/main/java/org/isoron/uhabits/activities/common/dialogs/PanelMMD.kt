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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldDefaultsMMD
import com.mudita.mmd.components.text_field.TextFieldColorsMMD

/**
 * The floating panel every popup in this app sits in.
 *
 * **MMD ships no dialog.** All twenty-two component packages were read: there
 * is `ModalBottomSheetMMD` and nothing else that puts content over a screen —
 * no alert, no confirm, no centred panel. So this container is ours, and the
 * point of having exactly one of it is that every popup then looks the same:
 * the checkmark editor, the number editor and the delete confirmation are all
 * this, differing only in what is poured into it.
 *
 * Everything about how it looks is taken from MMD rather than invented:
 *
 * - **White fill, black border, no elevation.** `eInkColorScheme` has no grey,
 *   and MMD never uses elevation — a shadow dithers into a smear. The border
 *   is what makes the panel read as a surface at all, which is the same
 *   reasoning `DropdownMenuMMD` gives for its own.
 * - **3dp border**, matching `DividerDefaultsMMD.Thickness`: MMD's structural
 *   weight, the same rule that sits under every screen header.
 * - **8dp corners**, matching `ButtonDefaultsMMD.buttonCornerRadius`.
 *
 * What it deliberately does *not* have is the grey dim behind it. Every dialog
 * in this app used to raise Android's default window scrim — a flat mid-grey
 * over the whole screen, and the most visible violation left in the app.
 * `ModalBottomSheetMMD` sets `ScrimColor = Color.Transparent` for the same
 * reason; [MmdPanelDialogFragment] clears `dimAmount` to match.
 */
@Composable
fun PanelMMD(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PANEL_MARGIN)
            .background(MaterialTheme.colorScheme.surface, PANEL_SHAPE)
            .border(PANEL_BORDER, MaterialTheme.colorScheme.onSurface, PANEL_SHAPE)
            .padding(PANEL_PADDING),
    ) {
        content()
    }
}

/** A panel's heading, in MMD's own title role. */
@Composable
fun PanelTitle(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** A panel's body copy, regular weight. MuditaOS has no small grey text. */
@Composable
fun PanelBody(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * A panel's actions, along its bottom edge.
 *
 * MMD's button tiers, from `eink-design.md`: the committing action is
 * `ButtonMMD` — solid black, label knocked out in white — and everything
 * beside it is `OutlinedButtonMMD`, a 2dp black outline on nothing.
 */
@Composable
fun PanelActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The one action that commits. Solid black. */
@Composable
fun PanelPrimaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ButtonMMD(onClick = onClick, modifier = modifier) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Everything beside it. Black outline, no fill. */
@Composable
fun PanelSecondaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButtonMMD(onClick = onClick, modifier = modifier) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * `TextFieldMMD`'s own colours, with the container stated rather than left to
 * chance.
 *
 * Its default container resolves to `surfaceContainerHighest`, which MMD's
 * `eInkColorScheme` leaves as `Color.Unspecified` in every one of the field's
 * four states — so the field renders with no fill at all. On a white panel
 * that happens to look right, but it is an accident of what is behind it, not
 * a token, and it would stop looking right the moment a field sat on anything
 * else.
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

private val PANEL_MARGIN = 16.dp
private val PANEL_PADDING = 16.dp

/** `DividerDefaultsMMD.Thickness` — MMD's structural weight. */
private val PANEL_BORDER = 3.dp

/** `ButtonDefaultsMMD.buttonCornerRadius`. */
private val PANEL_SHAPE = RoundedCornerShape(8.dp)
