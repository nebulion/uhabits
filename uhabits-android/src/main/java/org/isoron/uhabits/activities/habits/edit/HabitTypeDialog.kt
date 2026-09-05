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

package org.isoron.uhabits.activities.habits.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.dialogs.MmdPanelDialogFragment
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.intents.IntentFactory

/**
 * Which kind of habit is being created: one you either did or did not, or one
 * you measure.
 *
 * It was a translucent `AppCompatDialogFragment` inflating its own layout of
 * two `LinearLayout` buttons over a full-screen click-catching background.
 * Now it is [MmdPanelDialogFragment], the one popup container this app has --
 * white fill, 3dp black border, 8dp corners, no elevation and no dim -- so it
 * is the same object as the checkmark editor and the delete confirmation
 * rather than a third thing that resembles them.
 *
 * The two options keep their example lines. They are what make the choice
 * answerable by someone who has not met the words "measurable habit" before,
 * and they are set in regular-weight black beneath the option rather than as a
 * smaller grey subtitle, which is how MuditaOS writes a subtitle and the only
 * way available on a panel with no grey in its palette.
 */
class HabitTypeDialog : MmdPanelDialogFragment() {

    @Composable
    override fun PanelContent() {
        Column(modifier = Modifier.fillMaxWidth()) {
            TypeOption(
                titleRes = R.string.yes_or_no,
                exampleRes = R.string.yes_or_no_example,
                type = HabitType.YES_NO,
            )
            HorizontalDividerMMD(thickness = 1.dp)
            TypeOption(
                titleRes = R.string.measurable,
                exampleRes = R.string.measurable_example,
                type = HabitType.NUMERICAL,
            )
        }
    }

    @Composable
    private fun TypeOption(titleRes: Int, exampleRes: Int, type: HabitType) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable {
                    startActivity(IntentFactory().startEditActivity(requireActivity(), type.value))
                    dismiss()
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            TextMMD(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            TextMMD(
                text = stringResource(exampleRes),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
