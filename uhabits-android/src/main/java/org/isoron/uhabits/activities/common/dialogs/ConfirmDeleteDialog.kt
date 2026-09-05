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

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentManager
import org.isoron.uhabits.R
import org.isoron.uhabits.core.ui.callbacks.OnConfirmedCallback
import org.isoron.uhabits.utils.dismissCurrentAndShow

/**
 * Asks before deleting, as MMD.
 *
 * This was a stock `AlertDialog`: a Material title, a Material message, and a
 * pair of borderless text buttons, over Android's grey window dim. MMD ships
 * no alert or confirm component — all twenty-two packages were checked — so it
 * is built from the same [PanelMMD] the checkmark and number editors use, and
 * from MMD's button tiers.
 *
 * **Delete is the solid button and Cancel is outlined.** That is the one thing
 * here worth arguing about: solid black is MMD's committing action, and the
 * committing action is the destructive one. The confirmation exists precisely
 * so the destructive tap is deliberate, and making it the quiet option would
 * only invite the reflex "yes" this dialog is meant to interrupt.
 */
class ConfirmDeleteDialog : MmdPanelDialogFragment() {

    var callback: OnConfirmedCallback? = null

    @Composable
    override fun PanelContent() {
        val quantity = requireArguments().getInt(ARG_QUANTITY)
        PanelTitle(resources.getQuantityString(R.plurals.delete_habits_title, quantity))
        PanelBody(resources.getQuantityString(R.plurals.delete_habits_message, quantity))
        PanelActions {
            PanelPrimaryAction(
                label = stringResource(R.string.yes),
                onClick = {
                    callback?.onConfirmed()
                    dismiss()
                },
                modifier = Modifier.weight(1f),
            )
            PanelSecondaryAction(
                label = stringResource(R.string.no),
                onClick = { dismiss() },
                modifier = Modifier.weight(1f),
            )
        }
    }

    companion object {
        private const val ARG_QUANTITY = "quantity"

        fun show(fm: FragmentManager, callback: OnConfirmedCallback, quantity: Int) {
            ConfirmDeleteDialog().apply {
                arguments = Bundle().apply { putInt(ARG_QUANTITY, quantity) }
                this.callback = callback
            }.dismissCurrentAndShow(fm, "confirmDelete")
        }
    }
}
