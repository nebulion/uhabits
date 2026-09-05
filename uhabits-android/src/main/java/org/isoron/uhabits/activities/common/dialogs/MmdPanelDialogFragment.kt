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

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.mudita.mmd.ThemeMMD

/**
 * A popup that floats in the middle of the screen, holds a [PanelMMD], and
 * casts no shadow over what is behind it.
 *
 * The window is stripped of both things Android gives a dialog by default:
 *
 * - its **background**, so the only surface on screen is the panel's own white
 *   box with its black border, rather than a second card behind it; and
 * - its **dim**, which is the important one. `setDimAmount(0f)` is what stops
 *   the flat mid-grey wash over the rest of the screen. Every dialog in this
 *   app had it, it is grey ink on a panel whose whole design has none, and it
 *   is exactly what `ModalBottomSheetMMD` refuses by defaulting its own
 *   `ScrimColor` to `Color.Transparent`.
 *
 * Content is Compose under [ThemeMMD], returned from `onCreateView` rather
 * than set in `onCreateDialog` so the fragment wires up the lifecycle and
 * saved-state owners a `ComposeView` needs.
 */
abstract class MmdPanelDialogFragment : AppCompatDialogFragment() {

    @Composable
    protected abstract fun PanelContent()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext()).apply {
            window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setDimAmount(0f)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            ThemeMMD {
                PanelMMD { PanelContent() }
            }
        }
    }
}
