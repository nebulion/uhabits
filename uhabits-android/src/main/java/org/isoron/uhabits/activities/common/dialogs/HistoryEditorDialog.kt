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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import org.isoron.platform.gui.AndroidDataView
import org.isoron.platform.time.JavaLocalDateFormatter
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardPresenter
import org.isoron.uhabits.core.ui.views.EInkTheme
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.uhabits.core.ui.views.OnDateClickedListener
import org.isoron.uhabits.core.utils.DateUtils
import java.util.Locale

/**
 * The calendar you tap to correct a day you missed marking.
 *
 * Its chart was never the problem -- `HistoryChart` draws through the core
 * `Canvas` and screens its own midtones, so it has looked right since the
 * halftone pass. The chrome around it did not: a bare `Dialog` with Android's
 * default background behind the chart and its default dim over everything
 * else, which is a flat mid-grey wash on a panel whose palette contains no
 * grey at all.
 *
 * So the chart is hosted inside [MmdPanelDialogFragment] now, through
 * `AndroidView`, exactly as ShowHabit hosts its own View-drawn cards. That
 * gives it the same white fill, 3dp black border, 8dp corners and absence of
 * dim as every other popup here -- and it takes those from `PanelMMD` rather
 * than from a hand-drawn XML shape repeating MMD's numbers, which is the
 * mistake this fork already made once with its switch and header drawables.
 */
class HistoryEditorDialog : MmdPanelDialogFragment(), CommandRunner.Listener {

    private lateinit var commandRunner: CommandRunner
    private lateinit var habit: Habit
    private lateinit var preferences: Preferences
    lateinit var dataView: AndroidDataView

    private var chart: HistoryChart? = null
    private var onDateClickedListener: OnDateClickedListener? = null

    /**
     * Builds the chart, then hands the dialog itself to [MmdPanelDialogFragment].
     *
     * The registration below is not tidiness. This dialog can be raised
     * *underneath* another one -- tapping a day opens the number editor over
     * it -- so it tracks its own instance rather than relying on the shared
     * "dismiss whatever is showing" path, which would take the wrong one.
     */
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        clearCurrentDialog()
        val component = (requireActivity().application as HabitsApplication).component
        commandRunner = component.commandRunner
        habit = component.habitList.getById(requireArguments().getLong("habit"))!!
        preferences = component.preferences

        val themeSwitcher = AndroidThemeSwitcher(requireActivity(), preferences)
        themeSwitcher.apply()

        chart = HistoryChart(
            dateFormatter = JavaLocalDateFormatter(Locale.getDefault()),
            firstWeekday = preferences.firstWeekday,
            paletteColor = habit.color,
            series = emptyList(),
            defaultSquare = HistoryChart.Square.OFF,
            notesIndicators = emptyList(),
            theme = themeSwitcher.currentTheme,
            today = DateUtils.getTodayWithOffset().toLocalDate(),
            onDateClickedListener = onDateClickedListener ?: object : OnDateClickedListener {},
            padding = 10.0
        )
        dataView = AndroidDataView(requireContext(), null)
        dataView.view = chart!!

        val dialog = super.onCreateDialog(savedInstanceState)
        currentDialog = dialog
        return dialog
    }

    @Composable
    override fun PanelContent() {
        // The chart sizes itself to its width and would otherwise grow to
        // whatever the year's history needs, so it keeps the height cap the
        // dialog window used to impose.
        val maxHeight = with(LocalDensity.current) {
            resources.getDimensionPixelSize(R.dimen.history_editor_max_height).toDp()
        }
        AndroidView(
            factory = { dataView },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        currentDialog = null
    }

    override fun onResume() {
        super.onResume()
        commandRunner.addListener(this)
        refreshData()
    }

    override fun onPause() {
        commandRunner.removeListener(this)
        super.onPause()
    }

    fun setOnDateClickedListener(listener: OnDateClickedListener) {
        onDateClickedListener = listener
        chart?.onDateClickedListener = listener
    }

    private fun refreshData() {
        val model = HistoryCardPresenter.buildState(
            habit,
            preferences.firstWeekday,
            theme = EInkTheme()
        )
        chart?.series = model.series
        chart?.defaultSquare = model.defaultSquare
        chart?.notesIndicators = model.notesIndicators
        dataView.postInvalidate()
    }

    override fun onCommandFinished(command: Command) {
        refreshData()
    }

    companion object {
        // HistoryEditorDialog handles multiple dialogs on its own,
        // because sometimes we want it to be shown under another dialog (e.g. NumberPopup)
        var currentDialog: Dialog? = null
        fun clearCurrentDialog() {
            currentDialog?.dismiss()
            currentDialog = null
        }
    }
}
