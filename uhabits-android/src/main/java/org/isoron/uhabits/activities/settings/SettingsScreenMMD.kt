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

package org.isoron.uhabits.activities.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import org.isoron.uhabits.R
import org.isoron.uhabits.mmd.DashedDividerMMD
import org.isoron.uhabits.mmd.MmdTheme
import org.isoron.uhabits.mmd.PagedList

/**
 * One page of settings, built from Mudita Mindful Design components.
 *
 * Settings is grouped drill-down pages, as the Kompakt's own Settings and the
 * owner's Anki and Tasks apps are (UI-PATTERNS A4): the first page lists the
 * groups, each with what it currently holds written under its name, and each
 * opens a page of its own. Every row reads and edits the same way -- bold
 * title, current value under it, tap for the right editor: on/off is a switch
 * on the row, a short choice is a sheet of radio rows, anything else opens a
 * page.
 *
 * It used to be one long list with plain section headers.
 */

/** One row of a settings page. */
sealed interface SettingsRow {
    val title: String

    /** Opens another page. [value] says what that page currently holds. */
    data class Page(
        override val title: String,
        val value: String? = null,
        val onClick: () -> Unit,
    ) : SettingsRow

    data class Toggle(
        override val title: String,
        val checked: Boolean,
        val onToggle: (Boolean) -> Unit,
    ) : SettingsRow

    data class Action(
        override val title: String,
        val onClick: () -> Unit,
        /** What the row currently holds, shown under the title -- a chosen folder, say. */
        val value: String? = null,
    ) : SettingsRow

    /** A line of text in the shape of a row; not a control. */
    data class Info(override val title: String) : SettingsRow

    /**
     * One of several values, shown with the current one under the title and
     * picked from a sheet -- what MuditaOS raises for a choice (Sound,
     * Bluetooth).
     */
    data class Choice(
        override val title: String,
        val options: List<String>,
        val selectedIndex: Int,
        val onSelect: (Int) -> Unit,
    ) : SettingsRow
}

@Composable
fun SettingsScreenMMD(
    title: String,
    rows: List<SettingsRow>,
    onNavigateBack: () -> Unit,
) {
    MmdTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                // The 3dp rule is drawn here rather than by the bar's own
                // showDivider, which renders Material's 1dp (see
                // PROJECT-STATUS, "MMD's own gaps").
                Column {
                    TopAppBarMMD(
                        showDivider = false,
                        title = {
                            TextMMD(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_back_eink),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        },
                    )
                    HorizontalDividerMMD()
                }
            },
        ) { insets ->
            PagedList(
                modifier = Modifier.fillMaxSize().padding(insets),
            ) {
                items(rows, key = { it.title }) { row ->
                    Column {
                        when (row) {
                            is SettingsRow.Page -> LinkRow(row.title, row.value, row.onClick)
                            is SettingsRow.Toggle -> ToggleRow(row)
                            is SettingsRow.Action -> ValueRow(row.title, row.value, row.onClick)
                            is SettingsRow.Info -> ValueRow(row.title, null, null)
                            is SettingsRow.Choice -> ChoiceRow(row)
                        }
                        RowDivider()
                    }
                }
            }
        }
    }
}

/**
 * The text of a row: MMD's list label and supporting text (zeroheight "List").
 * The label is Black when a value sits under it and regular when alone, as in
 * the drawing and the Kompakt's Settings; the value is regular-weight black,
 * never small grey.
 */
@Composable
private fun RowText(title: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        TextMMD(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, lineHeight = 23.sp),
            fontWeight = if (value != null) FontWeight.Black else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            TextMMD(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 18.sp, lineHeight = 18.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A row's frame: MMD list padding, 64dp minimum (Kompakt Settings). */
@Composable
private fun RowFrame(
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .padding(horizontal = EDGE, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A row that opens a page, marked with the thin line chevron Kompakt Settings uses. */
@Composable
private fun LinkRow(title: String, value: String?, onClick: () -> Unit) {
    RowFrame(Modifier.clickable(onClick = onClick)) {
        RowText(title, value, Modifier.weight(1f).padding(end = 16.dp))
        Icon(
            painter = painterResource(R.drawable.ic_chevron_line_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** An action, or with no [onClick] a plain line of text in the shape of a row. */
@Composable
private fun ValueRow(title: String, value: String?, onClick: (() -> Unit)?) {
    RowFrame(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        RowText(title, value, Modifier.weight(1f))
    }
}

/**
 * On/off. The whole row toggles and the switch itself is inert, so there is
 * one touch target (MMD-GUIDE 3.15). `SwitchMMD`: 52x32dp pill, 2dp outline,
 * inverting on state.
 */
@Composable
private fun ToggleRow(row: SettingsRow.Toggle) {
    var checked by remember(row.title, row.checked) { mutableStateOf(row.checked) }
    RowFrame(
        Modifier.toggleable(value = checked, role = Role.Switch) {
            checked = it
            row.onToggle(it)
        },
    ) {
        RowText(row.title, null, Modifier.weight(1f).padding(end = 16.dp))
        SwitchMMD(checked = checked, onCheckedChange = null)
    }
}

/**
 * MMD's row divider: dotted, starting where the label starts (zeroheight
 * "List", Kompakt Settings).
 */
@Composable
private fun RowDivider() {
    DashedDividerMMD(Modifier.padding(start = EDGE))
}

/** A one-of-N setting: the value it holds under the title, a sheet of radio rows to change it. */
@Composable
private fun ChoiceRow(row: SettingsRow.Choice) {
    var open by remember { mutableStateOf(false) }
    RowFrame(Modifier.clickable { open = true }) {
        RowText(row.title, row.options.getOrNull(row.selectedIndex), Modifier.weight(1f))
    }
    if (open) {
        ModalBottomSheetMMD(
            onDismissRequest = { open = false },
            // Without this a sheet taller than half the screen opens at half
            // height with its last options below the fold.
            sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true),
            dragHandle = null,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                TextMMD(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = EDGE, vertical = 12.dp),
                )
                row.options.forEachIndexed { index, option ->
                    // MMD's own row shape, from the usage sample in
                    // RadioButtonMMD.kt: the row carries the selection, so the
                    // button takes no handler and the whole row is one target.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = index == row.selectedIndex,
                                onClick = {
                                    row.onSelect(index)
                                    open = false
                                },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = EDGE),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButtonMMD(selected = index == row.selectedIndex, onClick = null)
                        TextMMD(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (index == row.selectedIndex) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** MMD list padding, left and right. */
private val EDGE = 16.dp

/** Kompakt Settings rows measure 64dp. */
private val ROW_HEIGHT = 64.dp
