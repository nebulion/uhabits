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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import org.isoron.uhabits.core.ui.screens.habits.show.views.HistoryCardState
import org.isoron.uhabits.core.ui.views.HistoryChart
import org.isoron.platform.time.DayOfWeek
import java.util.Calendar
import java.text.DateFormatSymbols

/**
 * The record itself: one square per day, weeks running left to right.
 *
 * This replaces `HistoryCardView`, and it is the one card that never needed the
 * chart port to escape being a View. `HistoryCardState.series` is already a
 * plain list of [HistoryChart.Square] values with nothing Android in it — the
 * old card only drew it through a canvas because everything else on the screen
 * did.
 *
 * States are told apart by fill, which is the same language the habits list
 * uses for its checkmarks and the same one `SwitchMMD` and `CheckboxMMD` use
 * for theirs. There is no grey to reach for and none is wanted:
 *
 *  - **done** — a filled square, whether it was recorded by hand or credited
 *    automatically. On the panel, ink means it happened.
 *  - **skipped** — an outline holding a smaller filled square, so a
 *    deliberate skip reads as its own thing rather than as a failure.
 *  - **short of target** — the same inner mark, smaller again. This only
 *    occurs for numerical habits, where a day can be partly done.
 *  - **not done, or never recorded** — an empty outline.
 *
 * `series[0]` is **today** and the list runs backwards from it, which is what
 * `EntryList.getByInterval` produces. Getting that backwards mirrors a habit's
 * whole history and looks entirely plausible on screen, so the index maths is
 * written out once, here, and explained rather than inlined.
 */
@Composable
fun HabitCalendarMMD(
    state: HistoryCardState,
    modifier: Modifier = Modifier,
    weeks: Int = WEEKS,
) {
    val ink = MaterialTheme.colorScheme.onBackground

    // Where today sits inside its own week, counting from whichever day the
    // owner starts their week on. `daysSinceSunday` is the core's own numbering
    // and is all this needs -- the difference is what matters, not the origin.
    val todayRow = Math.floorMod(
        state.today.dayOfWeek.daysSinceSunday - state.firstWeekday.daysSinceSunday,
        DAYS_IN_WEEK,
    )

    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            // Weekday initials, one per row, in the same order the rows run.
            Column(
                verticalArrangement = Arrangement.spacedBy(CELL_GAP),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(DAYS_IN_WEEK) { row ->
                    Box(
                        modifier = Modifier.size(LABEL_WIDTH, CELL),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextMMD(
                            text = weekdayInitial(state.firstWeekday, row),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            repeat(weeks) { column ->
                Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                    repeat(DAYS_IN_WEEK) { row ->
                        // Days back from today. The rightmost column is the
                        // current week, so it is `weeks - 1 - column` whole
                        // weeks back, plus however far up the column today is.
                        val daysAgo = (weeks - 1 - column) * DAYS_IN_WEEK + (todayRow - row)
                        DaySquare(
                            square = state.series.getOrNull(daysAgo),
                            // Negative is the future — the rest of this week —
                            // and past the end of the series is before this
                            // habit had any entries at all. Neither is a day
                            // that can be judged, so neither is drawn.
                            isDay = daysAgo >= 0 && daysAgo < state.series.size,
                            ink = ink,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySquare(
    square: HistoryChart.Square?,
    isDay: Boolean,
    ink: androidx.compose.ui.graphics.Color,
) {
    if (!isDay) {
        Box(modifier = Modifier.size(CELL))
        return
    }
    when (square) {
        HistoryChart.Square.ON, HistoryChart.Square.DIMMED ->
            Box(
                modifier = Modifier
                    .size(CELL)
                    .background(ink, CELL_SHAPE),
            )

        HistoryChart.Square.HATCHED -> InnerMark(ink, SKIP_MARK)
        HistoryChart.Square.GREY -> InnerMark(ink, PARTIAL_MARK)

        else ->
            Box(
                modifier = Modifier
                    .size(CELL)
                    .border(OUTLINE, ink, CELL_SHAPE),
            )
    }
}

/** An outlined day holding a smaller solid one. */
@Composable
private fun InnerMark(ink: androidx.compose.ui.graphics.Color, inner: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(CELL)
            .border(OUTLINE, ink, CELL_SHAPE),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(inner)
                .background(ink, CELL_SHAPE),
        )
    }
}

/**
 * The initial of the weekday shown in [row], counting from the week's first
 * day — which is a preference, not a constant, so this cannot be a fixed list.
 */
private fun weekdayInitial(firstWeekday: DayOfWeek, row: Int): String {
    // Calendar.SUNDAY is 1, and the core counts Sunday as 0, so the shift is
    // by one. DateFormatSymbols gives the locale's own short names; the first
    // character of one is as close to an initial as this can portably get.
    val index = (firstWeekday.daysSinceSunday + row) % DAYS_IN_WEEK
    val names = DateFormatSymbols.getInstance().shortWeekdays
    return names[Calendar.SUNDAY + index].take(1)
}

private const val DAYS_IN_WEEK = 7

/**
 * How many weeks are drawn.
 *
 * Twelve columns at [CELL] plus [CELL_GAP] comes to about 312dp, which fits
 * inside the panel's 361dp with the weekday labels and still leaves the cells
 * big enough to count at a glance.
 */
private const val WEEKS = 12

private val CELL = 20.dp
private val CELL_GAP = 6.dp
private val LABEL_WIDTH = 16.dp
private val OUTLINE = 1.5.dp

/** Skipped and short-of-target, as progressively smaller marks. */
private val SKIP_MARK = 10.dp
private val PARTIAL_MARK = 6.dp

/** Barely rounded, matching the light rounding MMD gives its own controls. */
private val CELL_SHAPE = RoundedCornerShape(2.dp)
