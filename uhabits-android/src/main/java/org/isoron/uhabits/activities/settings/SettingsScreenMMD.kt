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

import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import org.isoron.uhabits.R

/**
 * The settings screen, built from Mudita Mindful Design components.
 *
 * This is the first screen moved off Android Views and onto MMD proper, rather
 * than onto XML drawables imitating it. Everything visible here -- the header
 * and its rule, the rows, the dividers, the switches, the type -- is a real
 * MMD composable under [ThemeMMD], so it inherits `eInkColorScheme` (black and
 * white only), `eInkTypography` (Lato, nothing below 14sp) and MMD's global
 * disabling of ripples.
 */

/**
 * How many rows one drag moves, matching the habits list.
 *
 * `LazyColumnMMD` does not scroll freely -- it disables user scrolling and
 * calls `scrollToItem` in fixed steps -- so this is a page size, not a speed.
 * The default `LazyDefaultsMMD.SCROLL_STEP` is 4, which left this screen
 * noticeably slower to get through than the habits list next door. Six is what
 * that list uses, and a settings row is the taller of the two (64dp against
 * 56dp), so six here covers slightly more of the screen, not less.
 */
private const val SCROLL_STEP = 6

/** One row of the settings list. */
sealed interface SettingsRow {
    val title: String

    data class Header(override val title: String) : SettingsRow

    data class Toggle(
        override val title: String,
        val checked: Boolean,
        val onToggle: (Boolean) -> Unit,
    ) : SettingsRow

    data class Action(
        override val title: String,
        val onClick: () -> Unit,
    ) : SettingsRow

    /**
     * One of several values, shown with the current one under the title and
     * picked from a sheet.
     *
     * A sheet rather than a dialog because that is what MuditaOS raises for a
     * choice -- Sound and Bluetooth both do -- and because MMD ships a bottom
     * sheet and no dialog at all.
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
    rows: List<SettingsRow>,
    onNavigateBack: () -> Unit,
) {
    ThemeMMD {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                // The rule is drawn here rather than by the bar's own
                // showDivider, which does not produce the 3dp rule this design
                // calls for: TopAppBarMMD passes its 3dp `dividerLineHeight` to
                // a raw Material3 HorizontalDivider as a *width*, and never
                // passes a thickness at all, so the line falls back to
                // Material's 1dp. HorizontalDividerMMD defaults to the real
                // DividerDefaultsMMD.Thickness of 3dp.
                Column {
                    TopAppBarMMD(
                        showDivider = false,
                        title = {
                            TextMMD(
                                text = stringOrEmpty(R.string.settings),
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
            LazyColumnMMD(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
                contentPadding = PaddingValues(bottom = 24.dp),
                scrollStep = SCROLL_STEP,
            ) {
                items(rows, key = { it.title }) { row ->
                    when (row) {
                        is SettingsRow.Header -> SectionHeader(row)
                        is SettingsRow.Toggle -> ToggleRow(row)
                        is SettingsRow.Action -> ActionRow(row)
                        is SettingsRow.Choice -> ChoiceRow(row)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(row: SettingsRow.Header) {
    Column {
        TextMMD(
            text = row.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        )
        // MMD's divider is 3dp solid black: the same weight that closes a
        // header, used here to open a section.
        HorizontalDividerMMD()
    }
}

@Composable
private fun ToggleRow(row: SettingsRow.Toggle) {
    var checked by remember(row.title, row.checked) { mutableStateOf(row.checked) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable {
                    checked = !checked
                    row.onToggle(checked)
                }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextMMD(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
            )
            // The real thing: 52x32dp pill, 2dp outline, inverting on state.
            SwitchMMD(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    row.onToggle(it)
                },
            )
        }
        RowDivider()
    }
}

@Composable
private fun ActionRow(row: SettingsRow.Action) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClick = row.onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            TextMMD(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        RowDivider()
    }
}

/**
 * The rule between rows, indented to line up with the text as it is in the
 * Kompakt's own Settings.
 */
@Composable
private fun RowDivider() {
    HorizontalDividerMMD(
        modifier = Modifier.padding(start = 20.dp),
        thickness = 1.dp,
    )
}

@Composable
private fun stringOrEmpty(resId: Int): String =
    androidx.compose.ui.res.stringResource(resId)

/**
 * A one-of-N setting: the title, the value it currently holds beneath it, and
 * a sheet of radio rows to change it.
 *
 * The value is shown in regular-weight black rather than as a smaller grey
 * subtitle. `eInkColorScheme` has no grey in it, and MuditaOS writes its own
 * subtitles this way -- Bluetooth's "USB Preferences / File transfer" is the
 * same shape.
 */
@Composable
private fun ChoiceRow(row: SettingsRow.Choice) {
    var open by remember { mutableStateOf(false) }
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable { open = true }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            TextMMD(text = row.title, style = MaterialTheme.typography.bodyMedium)
            row.options.getOrNull(row.selectedIndex)?.let { current ->
                TextMMD(
                    text = current,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        HorizontalDividerMMD(thickness = 1.dp)
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
                row.options.forEachIndexed { index, option ->
                    // MMD's own row shape, from the usage sample in
                    // RadioButtonMMD.kt: the row carries the selection, so the
                    // button itself takes no handler and the whole row stays a
                    // single target. An inert RadioButtonMMD measures 24dp, not
                    // Material's interactive 48dp, which is why the label is
                    // held off it by a further 16dp.
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
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButtonMMD(selected = index == row.selectedIndex, onClick = null)
                        TextMMD(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
