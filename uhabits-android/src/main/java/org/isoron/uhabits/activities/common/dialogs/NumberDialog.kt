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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Entry
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/**
 * The editor for one day of a measurable habit, as MMD.
 *
 * Same story as [CheckmarkDialog]: it shared that dialog's XML layout, so it
 * carried the same `#BDBDBD` border and divider, the same `contrast60` grey
 * button tints, and the same grey window dim. It is now the same [PanelMMD]
 * on the same [MmdPanelDialogFragment], which is the point — the two editors
 * a habit can raise should not look like two different apps.
 *
 * **Save is the solid button**; Skip and Unknown are outlined, matching the
 * checkmark panel's hierarchy exactly.
 *
 * The value is parsed with `toDoubleOrNull`, not `toDouble`. The old screen's
 * target field taught that lesson the hard way: `KeyboardType` only chooses a
 * keyboard, it does not filter what arrives, and an unguarded `toDouble()`
 * takes the whole app down on "1.2.3".
 */
class NumberDialog : MmdPanelDialogFragment() {
    var onToggle: (Double, String) -> Unit = { _, _ -> }
    var onDismiss: () -> Unit = {}

    private var dismissedViaSaveAction = false
    private var originalNotes: String = ""
    private var originalValue: Double = 0.0
    private var currentNotes: String = ""

    @Composable
    override fun PanelContent() {
        val prefs = (requireActivity().application as HabitsApplication).component.preferences
        originalNotes = requireArguments().getString("notes").orEmpty()
        originalValue = requireArguments().getDouble("value")

        var value by rememberSaveable {
            mutableStateOf(
                if (originalValue < 0.01) "" else DecimalFormat("#.##").format(originalValue)
            )
        }
        var notes by rememberSaveable { mutableStateOf(originalNotes) }
        currentNotes = notes

        fun commit(parsed: Double) {
            dismissedViaSaveAction = true
            onToggle(parsed, notes.trim())
            dismiss()
        }

        TextFieldMMD(
            value = value,
            onValueChange = { text -> value = text.filter { it.isDigit() || it in DECIMAL_MARKS } },
            label = {
                TextMMD(
                    text = stringResource(R.string.value),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = panelTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

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

        PanelActions {
            PanelPrimaryAction(
                label = stringResource(R.string.save),
                // An unparseable or empty value saves nothing rather than
                // crashing or guessing.
                onClick = { value.normalized().toDoubleOrNull()?.let(::commit) },
                modifier = Modifier.weight(1f),
            )
            if (prefs.isSkipEnabled) {
                PanelSecondaryAction(
                    label = stringResource(R.string.skip_day),
                    onClick = { commit(Entry.SKIP.toDouble() / 1000) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (prefs.areQuestionMarksEnabled) {
                PanelSecondaryAction(
                    label = stringResource(R.string.unknown_label),
                    onClick = { commit(Entry.UNKNOWN.toDouble() / 1000) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!dismissedViaSaveAction && currentNotes.trim() != originalNotes) {
            onToggle(originalValue, currentNotes.trim())
        }
        onDismiss()
    }

    /** Accepts whichever separator this locale's keyboard offers. */
    private fun String.normalized() = replace(',', '.')

    companion object {
        private val DECIMAL_MARKS =
            charArrayOf('.', ',', DecimalFormatSymbols.getInstance().decimalSeparator)
    }
}
