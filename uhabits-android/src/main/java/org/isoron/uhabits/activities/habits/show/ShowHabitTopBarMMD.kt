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

package org.isoron.uhabits.activities.habits.show

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.mmd.MmdTheme
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import org.isoron.uhabits.R

/** What the header can do, so the composable does not reach for a presenter. */
data class ShowHabitActions(
    val onBack: () -> Unit,
    val onEdit: () -> Unit,
    val onArchive: () -> Unit,
    val onUnarchive: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * The habit screen's header, as the real `TopAppBarMMD`.
 *
 * The body below it is still Android Views -- `ScoreChart` and friends have
 * their own `onDraw` and have to be ported onto the core `Canvas` before the
 * screen can move wholesale -- but the header has no such dependency, so there
 * is no reason for it to be an AppCompat `Toolbar` styled to resemble MMD.
 * Dressing one up is the mistake this fork already made once with hand-built
 * switch and header drawables; the rule since is to call the component.
 *
 * Everything here therefore comes from MMD: the 3dp rule under the title is
 * `TopAppBarMMD`'s own `showDivider`, the type is `eInkTypography`, and the
 * ink is `eInkColorScheme`.
 *
 * **There is no overflow, at all.** MuditaOS K has no three-dot menu on any
 * screen, so every action this header offers is visible in it. The one
 * exception used to be a developer-only "randomize" behind `DropdownMenuMMD`;
 * developer mode is gone and so is the dropdown, which settles the conflict
 * this screen had with its own rule in eink-design-habits.md (in the folder above this repo).
 */
@Composable
fun ShowHabitTopBarMMD(
    title: String,
    canArchive: Boolean,
    canUnarchive: Boolean,
    actions: ShowHabitActions,
) {
    MmdTheme {
        // The rule is drawn here, not by the bar's own showDivider, which
        // cannot produce the 3dp rule: TopAppBarMMD hands its 3dp
        // `dividerLineHeight` to a raw Material3 HorizontalDivider as a
        // *width* and never passes a thickness, so the line falls back to
        // Material's 1dp. HorizontalDividerMMD defaults to the real
        // DividerDefaultsMMD.Thickness of 3dp.
        Column {
            TopAppBarMMD(
                showDivider = false,
                title = {
                    TextMMD(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back_eink),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            // Same weight as the actions opposite it, rather
                            // than Material's default 24dp.
                            modifier = Modifier.size(ACTION_ICON_SIZE),
                        )
                    }
                },
                actions = {
                    ActionIcon(R.drawable.ic_action_edit_dark, R.string.edit, actions.onEdit)
                    if (canArchive) {
                        ActionIcon(
                            R.drawable.ic_action_archive_dark,
                            R.string.archive,
                            actions.onArchive,
                        )
                    }
                    if (canUnarchive) {
                        ActionIcon(
                            R.drawable.ic_action_unarchive_dark,
                            R.string.unarchive,
                            actions.onUnarchive,
                        )
                    }
                    // Export is not here, and now it is not merely missing:
                    // this header holds four full-size targets beside a habit's
                    // name, and export was the fifth that did not fit. It lives
                    // on the habits list's selection bar, whose title is a
                    // count rather than a name and so has the room.
                    ActionIcon(R.drawable.ic_action_delete_dark, R.string.delete, actions.onDelete)
                },
            )
            HorizontalDividerMMD()
        }
    }
}

/**
 * One header action, at full size.
 *
 * Two separate sizes matter here, and confusing them is what made these look
 * wrong. The **button** is Material's 48dp interactive minimum: it had been
 * shrunk to 40dp to fit five actions and a back arrow across a 320dp header,
 * which is the wrong trade on a panel this size -- a mistap costs a full
 * repaint to undo -- so instead the *number* of actions gave way and export
 * left the header.
 *
 * The **glyph** is [ACTION_ICON_SIZE], not Material's default 24dp. Measured
 * on the device, a 24dp mark in a 48dp button is 12dp of nothing on every
 * side, and beside a 24sp bold title it reads as a much smaller control than
 * it actually is. Growing the glyph costs no layout at all: the button, and
 * so the room left for the habit's name, is unchanged.
 */
@Composable
private fun ActionIcon(iconRes: Int, descriptionRes: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(descriptionRes),
            tint = Color.Unspecified,
            modifier = Modifier.size(ACTION_ICON_SIZE),
        )
    }
}

/**
 * How big a header glyph is drawn.
 *
 * MMD ships no icon set, and so has no icon-size token to inherit -- this one
 * is ours. 32dp inside a 48dp button leaves 8dp of margin, which is enough to
 * keep neighbouring targets visually separate and enough ink to read at a
 * glance on a panel with no colour to help it.
 */
private val ACTION_ICON_SIZE = 32.dp
