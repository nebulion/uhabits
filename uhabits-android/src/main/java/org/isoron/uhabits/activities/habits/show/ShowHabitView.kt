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

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.tabs.SecondaryTabRowMMD
import com.mudita.mmd.components.tabs.TabMMD
import com.mudita.mmd.components.text.TextMMD
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.R
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitState
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.FrequencyCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.TargetCardState
import org.isoron.uhabits.mmd.DashedDividerMMD
import org.isoron.uhabits.mmd.MmdTheme
import org.isoron.uhabits.mmd.PagedList
import org.isoron.uhabits.utils.toSimpleDataFormat
import kotlin.math.ceil

/**
 * The habit screen: a fixed summary over a tabbed detail.
 *
 * It used to be nine cards stacked in one `LazyColumnMMD`, which was wrong in
 * two ways that only showed up on the device. Nothing read at a glance -- the
 * numbers you actually check daily were the third card down -- and the
 * scrollbar visibly changed size as you dragged, because `LazyColumnMMD`
 * sizes its thumb by item count.
 *
 * So the summary is hoisted out and pinned: four figures, always visible,
 * never scrolled past. Everything else is grouped behind `SecondaryTabRowMMD`
 * -- MMD's own documentation describes the secondary row as "used within a
 * content area to further separate related content".
 *
 * **All of it is Compose now.** The Score and Bar charts were the last Views
 * on this screen, hosted through `AndroidView` and scrolled by dragging; they
 * are [ScoreChartMMD] and [BarChartMMD], stepped with ‹ ›. The panels page
 * with [PagedList] instead of `LazyColumnMMD`, which painted every panel
 * twice and paged by item count; its scrollbar is shown only where a panel
 * overflows, and sized by height.
 */
class ShowHabitView(context: Context) : FrameLayout(context) {

    // Header inputs, held as Compose state so a refresh recomposes the bar
    // rather than re-setting the ComposeView's content.
    private var title by mutableStateOf("")
    private var canArchive by mutableStateOf(false)
    private var canUnarchive by mutableStateOf(false)

    // The pinned summary, read straight off the state the cards are given.
    private var statScore by mutableStateOf(0)
    private var statBest by mutableStateOf(0)
    private var statMonth by mutableStateOf(0)
    private var statTotal by mutableStateOf(0L)

    /** Which detail panel is showing. */
    private var tab by mutableIntStateOf(0)

    /** How many [CALENDAR_WEEKS]-week pages back the calendar shows. */
    private var calendarPage by mutableIntStateOf(0)

    private var history by mutableStateOf<HistoryCardState?>(null)
    private var streaks by mutableStateOf<StreakCardState?>(null)
    private var frequency by mutableStateOf<FrequencyCardState?>(null)
    private var target by mutableStateOf<TargetCardState?>(null)
    private var scores by mutableStateOf<ScoreCardState?>(null)
    private var bars by mutableStateOf<BarCardState?>(null)
    private var isNumerical by mutableStateOf(false)

    private var scorePresenter: ScoreCardPresenter? = null
    private var barPresenter: BarCardPresenter? = null
    private var historyPresenter: HistoryCardPresenter? = null

    private val composeView = ComposeView(context)

    init {
        addView(composeView)
    }

    fun setTopBar(actions: ShowHabitActions) {
        composeView.setContent {
            MmdTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    ShowHabitTopBarMMD(
                        title = title,
                        canArchive = canArchive,
                        canUnarchive = canUnarchive,
                        actions = actions,
                    )

                    StatBand()

                    val titles = listOf(
                        stringResource(R.string.calendar),
                        stringResource(R.string.score),
                        stringResource(R.string.streaks),
                    )
                    val selected = tab.coerceIn(0, titles.lastIndex)

                    SecondaryTabRowMMD(
                        selectedTabIndex = selected,
                        // Suppressed: the row's own default divider is a raw
                        // Material3 HorizontalDivider at 2dp, the same
                        // shortcut that stops TopAppBarMMD's showDivider from
                        // drawing the 3dp rule. The real one is drawn below.
                        divider = {},
                    ) {
                        titles.forEachIndexed { index, text ->
                            TabMMD(
                                selected = index == selected,
                                onClick = { tab = index },
                                text = {
                                    TextMMD(
                                        text = text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        textAlign = TextAlign.Center,
                                    )
                                },
                            )
                        }
                    }
                    HorizontalDividerMMD()

                    when (selected) {
                        CALENDAR_TAB -> CalendarPanel(Modifier.weight(1f))
                        SCORE_TAB -> ScorePanel(Modifier.weight(1f))
                        else -> StreaksPanel(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    /**
     * The four figures, pinned above the tabs. `headlineLarge` is MMD's
     * largest role at 28sp; the captions are `labelSmall` at 14sp, the floor.
     */
    @Composable
    private fun StatBand() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            Stat(statScore.toString() + "%", stringResource(R.string.score))
            Stat(statBest.toString(), stringResource(R.string.best_streak_short))
            Stat(formatDelta(statMonth), stringResource(R.string.month))
            Stat(statTotal.toString(), stringResource(R.string.total))
        }
        HorizontalDividerMMD(thickness = 1.dp)
    }

    @Composable
    private fun RowScope.Stat(value: String, label: String) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TextMMD(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            TextMMD(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }

    /** A signed percentage, because a month delta of zero is worth seeing. */
    private fun formatDelta(value: Int) = if (value >= 0) "+$value" else "$value"

    /**
     * Twelve weeks of days, stepped back through history with ‹ ›, and a tap
     * on a day opens that day's entry.
     */
    @Composable
    private fun CalendarPanel(modifier: Modifier) {
        val state = history ?: return
        // Every day the series holds, plus the rest of this week, in pages.
        val pages = ceil((state.series.size + 7) / (CALENDAR_WEEKS * 7.0)).toInt().coerceAtLeast(1)
        val page = calendarPage.coerceIn(0, pages - 1)
        Column(modifier = modifier.fillMaxWidth()) {
            ChartStepper(
                range = calendarRange(state, page),
                canGoOlder = page < pages - 1,
                canGoNewer = page > 0,
                onOlder = { calendarPage = page + 1 },
                onNewer = { calendarPage = page - 1 },
            )
            HabitCalendarMMD(
                state = state,
                page = page,
                onDayClick = ::onDayClick,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    /**
     * Opens the day's editor -- never a direct toggle, because these squares
     * are small and a mistap should cost a Cancel, not a wrong entry. The
     * core presenter toggles on whichever press the short-toggle setting
     * gives the toggle to, so the other one is used here.
     */
    private fun onDayClick(date: LocalDate) {
        val presenter = historyPresenter ?: return
        if (presenter.preferences.isShortToggleEnabled) {
            presenter.onDateLongPress(date)
        } else {
            presenter.onDateShortPress(date)
        }
    }

    private fun calendarRange(state: HistoryCardState, page: Int): String {
        val format = "dMMM".toSimpleDataFormat()
        val newest = state.today.minus(page * CALENDAR_WEEKS * 7)
        val oldest = newest.minus(CALENDAR_WEEKS * 7 - 1)
        fun LocalDate.text() = format.format(
            org.isoron.uhabits.core.models.Timestamp.fromLocalDate(this).toJavaDate(),
        )
        return oldest.text() + " – " + newest.text()
    }

    /** The score line, the bar chart, and which weekdays the habit happens on. */
    @Composable
    private fun ScorePanel(modifier: Modifier) {
        val score = scores
        val bar = bars
        PagedList(modifier = modifier.fillMaxWidth()) {
            if (score != null) {
                item(key = "score-span") {
                    SpanPicker(
                        options = SPAN_NAMES,
                        selected = score.spinnerPosition,
                        onSelect = { scorePresenter?.onSpinnerPosition(it) },
                    )
                }
                item(key = "score") {
                    ScoreChartMMD(score, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    HorizontalDividerMMD(thickness = 1.dp)
                }
            }
            if (bar != null) {
                item(key = "bar-span") {
                    SpanPicker(
                        // A boolean habit's bar chart cannot be drawn a day at
                        // a time -- there is nothing to count -- so its list
                        // starts at Week and its indices are shifted by one
                        // against the numerical one.
                        options = if (bar.isNumerical) SPAN_NAMES else SPAN_NAMES.drop(1),
                        selected = if (bar.isNumerical) bar.numericalSpinnerPosition else bar.boolSpinnerPosition,
                        onSelect = {
                            if (bar.isNumerical) {
                                barPresenter?.onNumericalSpinnerPosition(it)
                            } else {
                                barPresenter?.onBoolSpinnerPosition(it)
                            }
                        },
                    )
                }
                item(key = "bar") {
                    BarChartMMD(bar, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    HorizontalDividerMMD(thickness = 1.dp)
                }
            }
            frequency?.let { item(key = "frequency") { HabitFrequencyMMD(state = it) } }
        }
    }

    @Composable
    private fun StreaksPanel(modifier: Modifier) {
        PagedList(modifier = modifier.fillMaxWidth()) {
            // Only a numerical habit has a target. The overview card that used
            // to be its boolean counterpart is gone: the pinned summary says
            // all of it.
            if (isNumerical) target?.let { item(key = "target") { HabitTargetMMD(state = it) } }
            streaks?.let { item(key = "streaks") { HabitStreaksMMD(state = it) } }
        }
    }

    fun setState(data: ShowHabitState) {
        title = data.title
        target = data.target
        isNumerical = data.isNumerical
        history = data.history
        streaks = data.streaks
        frequency = data.frequency
        scores = data.scores
        bars = data.bar

        // The summary figures. Score is a fraction, and the month delta is a
        // fraction of a fraction -- both are percentages by the time anyone
        // reads them. "Best" is the longest streak rather than the current
        // one, because that is what the core actually computes.
        statScore = (data.overview.scoreToday * 100).toInt()
        statMonth = (data.overview.scoreMonthDiff * 100).toInt()
        statTotal = data.overview.totalCount
        statBest = data.streaks.bestStreaks.maxOfOrNull { it.length } ?: 0
    }

    fun setMenuState(canArchive: Boolean, canUnarchive: Boolean) {
        this.canArchive = canArchive
        this.canUnarchive = canUnarchive
    }

    fun setListener(presenter: ShowHabitPresenter) {
        scorePresenter = presenter.scoreCardPresenter
        barPresenter = presenter.barCardPresenter
        historyPresenter = presenter.historyCardPresenter
    }
}

/** Index of each tab. */
private const val CALENDAR_TAB = 0
private const val SCORE_TAB = 1

/**
 * The five spans a chart can be drawn over: day, week, month, quarter, year.
 * Read from the same strings the old `android:entries` arrays used, so
 * nothing has to be translated twice.
 */
private val SPAN_NAMES = listOf(
    R.string.day,
    R.string.week,
    R.string.month,
    R.string.quarter,
    R.string.year,
)

/**
 * Picks the span a chart is drawn over.
 *
 * **A sheet, not a tab row**, which is a departure from what this project
 * planned and worth saying why. Two to four always-visible options is a tab
 * row, and that is what the notes called for -- but there are five here, and
 * the arithmetic does not work: the row divides the width evenly and MMD takes
 * 16dp off each side of a tab, which leaves about 40dp of label at 480px. That
 * is enough for "Week" and not for "Quarter". Stacking a second tab row under
 * the Calendar/Score/Streaks one would also be two rows of tabs saying
 * different kinds of thing.
 *
 * So it follows the other rule instead, the one MuditaOS itself follows in
 * Sound and Bluetooth: **a choice is a sheet.** The row shows the span in
 * force with a chevron; tapping it opens `ModalBottomSheetMMD` holding
 * `RadioButtonMMD` rows in MMD's own shape -- an inert button (the row carries
 * the click), a 56dp row, and the label held off the button by 16dp. It is the
 * same pattern the frequency picker and the new first-day-of-week setting use,
 * so the app answers every one-of-N question the same way.
 */
@Composable
private fun SpanPicker(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val index = selected.coerceIn(0, options.lastIndex)
    // A control row, not a floating label: what it sets on the left, the value
    // it is set to on the right, and a hairline closing it. The value is
    // inverted because it is the one thing on this page you can act on, which
    // is the only way an all-black-on-white palette can say "this is a
    // control" without inventing a grey.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .clickable { open = true }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextMMD(
            text = stringResource(R.string.averaged_by),
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.onBackground,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextMMD(
                text = stringResource(options[index]),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.background,
            )
            Icon(
                painter = painterResource(R.drawable.mmd_chevron_filled_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    DashedDividerMMD()
    if (open) {
        ModalBottomSheetMMD(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true),
            dragHandle = null,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                options.forEachIndexed { position, res ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = position == index,
                                onClick = {
                                    open = false
                                    onSelect(position)
                                },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButtonMMD(selected = position == index, onClick = null)
                        TextMMD(
                            text = stringResource(res),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
