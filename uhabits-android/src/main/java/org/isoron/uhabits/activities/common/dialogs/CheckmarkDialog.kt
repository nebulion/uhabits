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

import android.content.DialogInterface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Entry.Companion.NO
import org.isoron.uhabits.core.models.Entry.Companion.SKIP
import org.isoron.uhabits.core.models.Entry.Companion.UNKNOWN
import org.isoron.uhabits.core.models.Entry.Companion.YES_MANUAL

/**
 * The editor for one day of a yes-or-no habit, as MMD.
 *
 * It was a `Dialog` holding an XML layout whose border and button divider were
 * `?contrast40` — literally `#BDBDBD` — with two of its four buttons tinted
 * `contrast60` grey, over Android's default grey window dim. Three separate
 * greys on a panel whose design has none.
 *
 * Now it is a [PanelMMD] on [MmdPanelDialogFragment], so it floats in the
 * middle of the screen with nothing dimmed behind it, and the states are told
 * apart the way MMD tells any state apart — by fill, not by tint. **Yes is the
 * solid button**, because it is the affirmative and the one this popup exists
 * to record; No, Skip and Unknown are outlined. That is `eink-design-habits.md`'s
 * button hierarchy applied literally.
 *
 * The two optional buttons keep their old gates: Skip only when
 * `isSkipEnabled`, Unknown only when `areQuestionMarksEnabled`.
 */
class CheckmarkDialog : MmdPanelDialogFragment() {
    var onToggle: (Int, String) -> Unit = { _, _ -> }
    var onDismiss: () -> Unit = {}

    private var dismissedViaSaveAction = false
    private var originalNotes: String = ""
    private var originalValue: Int = 0
    private var currentNotes: String = ""

    @Composable
    override fun PanelContent() {
        val prefs = (requireActivity().application as HabitsApplication).component.preferences
        originalNotes = requireArguments().getString("notes").orEmpty()
        originalValue = requireArguments().getInt("value")

        var notes by rememberSaveable { mutableStateOf(originalNotes) }
        currentNotes = notes

        fun commit(value: Int) {
            dismissedViaSaveAction = true
            onToggle(value, notes.trim())
            dismiss()
        }

        TextFieldMMD(
            value = notes,
            onValueChange = { notes = it },
            label = {
                TextMMD(
                    text = stringResource(R.string.notes),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            singleLine = true,
            colors = panelTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        // Stacked, solid on top: written escape-first, the commit last.
        PanelActions {
            if (prefs.areQuestionMarksEnabled) {
                PanelSecondaryAction(
                    label = stringResource(R.string.unknown_label),
                    onClick = { commit(UNKNOWN) },
                )
            }
            if (prefs.isSkipEnabled) {
                PanelSecondaryAction(
                    label = stringResource(R.string.skip_day),
                    onClick = { commit(SKIP) },
                )
            }
            PanelSecondaryAction(
                label = stringResource(R.string.no),
                onClick = { commit(NO) },
            )
            PanelPrimaryAction(
                label = stringResource(R.string.yes),
                onClick = { commit(YES_MANUAL) },
            )
        }
    }

    /**
     * Dismissing without choosing still saves an edited note, which is what
     * the old dialog did and the one behaviour here worth preserving exactly.
     */
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!dismissedViaSaveAction && currentNotes.trim() != originalNotes) {
            onToggle(originalValue, currentNotes.trim())
        }
        onDismiss()
    }
}
