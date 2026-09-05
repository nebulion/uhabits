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

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import org.isoron.uhabits.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.core.ui.screens.habits.show.views.FrequencyCardState
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/**
 * Which weekdays this habit actually gets done, month by month.
 *
 * **Why the bubbles went.** `FrequencyChart.drawMarker` scaled each circle's
 * *radius* by the count and then halftoned its fill by the same value: two
 * weak encodings stacked on a panel that has neither colour nor anti-aliasing
 * to spare. Small circle areas are hard to compare by eye at the best of
 * times, and a dithered six-pixel disc is a smudge rather than a shape -- on
 * the device most of this card's data simply could not be seen.
 *
 * This draws the same weekday x month grid in the **Calendar tab's own
 * vocabulary**: a filled square, an outline holding a smaller one, an outline
 * holding a smaller one still, an empty outline. Four discrete steps of shape
 * rather than a continuous radius plus a dither, so every cell reads at arm's
 * length and nothing on the screen is grey. It also means the two data
 * displays on this screen say the same thing the same way, which is most of
 * what makes a screen feel designed rather than assembled.
 *
 * Weekday initials sit on the left, where [HabitCalendarMMD] already puts
 * them; they were on the right here and nowhere else.
 */
@Composable
fun HabitFrequencyMMD(
    state: FrequencyCardState,
    modifier: Modifier = Modifier,
) = BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
    // How many months fit is a question about the panel, not a constant. Nine
    // were hard-coded first and the ninth column was drawn off the edge of the
    // screen: at this cell size a 480px panel holds seven.
    val fit = ((maxWidth - LABEL_WIDTH - CELL_GAP) / (CELL + CELL_GAP)).toInt()
    val months = fit.coerceIn(1, MAX_MONTHS)
    val columns = remember(state.frequency, months) { columnsFor(state, months) }
    if (columns.isEmpty()) return@BoxWithConstraints

    // One scale for the whole grid, so a cell means the same thing in every
    // column. Per-column scaling would make a bad month look like a good one.
    val busiest = columns.maxOf { column -> column.counts.maxOrNull() ?: 0 }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextMMD(
                text = stringResource(R.string.frequency_done_on),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        repeat(DAYS_IN_WEEK) { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = CELL_GAP),
            ) {
                TextMMD(
                    text = weekdayInitial(state.firstWeekday, row),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(LABEL_WIDTH),
                )
                columns.forEach { column ->
                    Cell(count = column.counts[weekdayIndex(state.firstWeekday, row)], of = busiest)
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Box(modifier = Modifier.width(LABEL_WIDTH))
            columns.forEach { column ->
                TextMMD(
                    text = column.initial,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(CELL),
                )
            }
        }

        // Four shapes need naming once, and only once.
        Key()
    }
}

/** One month's counts, one per weekday, plus the letter under its column. */
private data class MonthColumn(val initial: String, val counts: IntArray)

private fun columnsFor(state: FrequencyCardState, months: Int): List<MonthColumn> {
    val monthNames = DateFormatSymbols.getInstance().shortMonths
    return state.frequency.entries
        .sortedBy { it.key.unixTime }
        .takeLast(months)
        .map { (timestamp, counts) ->
            MonthColumn(
                initial = monthInitial(timestamp, monthNames),
                counts = IntArray(DAYS_IN_WEEK) { counts.getOrNull(it) ?: 0 },
            )
        }
}

private fun monthInitial(timestamp: Timestamp, shortMonths: Array<String>): String {
    val month = timestamp.toCalendar()[Calendar.MONTH]
    val name = shortMonths.getOrNull(month).orEmpty()
    return name.take(1).uppercase(Locale.getDefault())
}

/**
 * One cell, as one of four shapes.
 *
 * The thresholds are fractions of the busiest month rather than absolute
 * counts, because "a lot" for a daily habit and for a weekly one are different
 * numbers and the grid should read the same for both.
 */
@Composable
private fun Cell(count: Int, of: Int) {
    val ink = MaterialTheme.colorScheme.onBackground
    val share = if (of <= 0) 0f else count.toFloat() / of
    when {
        count <= 0 -> Outline(ink, null)
        share >= EVERY -> Box(modifier = Modifier.size(CELL).background(ink))
        share >= MOST -> Outline(ink, CELL_MOST)
        else -> Outline(ink, CELL_SOME)
    }
}

@Composable
private fun Outline(ink: Color, inner: Dp?) {
    Box(
        modifier = Modifier.size(CELL).border(OUTLINE, ink),
        contentAlignment = Alignment.Center,
    ) {
        if (inner != null) Box(modifier = Modifier.size(inner).background(ink))
    }
}

@Composable
private fun Key() {
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
    ) {
        KeyItem(R.string.frequency_none) { KeyMark(ink, null) }
        KeyItem(R.string.frequency_some) { KeyMark(ink, KEY_SOME) }
        KeyItem(R.string.frequency_most) { KeyMark(ink, KEY_MOST) }
        KeyItem(R.string.frequency_every) {
            Box(modifier = Modifier.size(KEY_CELL).background(ink))
        }
    }
}

/** A key mark, drawn smaller than a grid cell so the four fit on one line. */
@Composable
private fun KeyMark(ink: Color, inner: Dp?) {
    Box(
        modifier = Modifier.size(KEY_CELL).border(OUTLINE, ink),
        contentAlignment = Alignment.Center,
    ) {
        if (inner != null) Box(modifier = Modifier.size(inner).background(ink))
    }
}

@Composable
private fun KeyItem(labelRes: Int, mark: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        mark()
        TextMMD(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * The initial of the weekday shown in [row], counting from whichever day the
 * owner starts their week on.
 */
private fun weekdayInitial(firstWeekday: Int, row: Int): String {
    val names = DateFormatSymbols.getInstance().shortWeekdays
    val index = (firstWeekday - 1 + row) % DAYS_IN_WEEK
    return names.getOrNull(Calendar.SUNDAY + index).orEmpty().take(1)
}

/**
 * Where that weekday sits in the core's own array, which is always
 * Sunday-first whatever the owner's week starts on.
 */
private fun weekdayIndex(firstWeekday: Int, row: Int): Int =
    (firstWeekday - 1 + row) % DAYS_IN_WEEK

private const val DAYS_IN_WEEK = 7

/** How many months of history the grid shows. Nine fit at this cell size. */
private const val MAX_MONTHS = 12

private val CELL = 34.dp
private val CELL_GAP = 6.dp
private val CELL_MOST = 20.dp
private val CELL_SOME = 12.dp
private val OUTLINE = 2.dp
private val LABEL_WIDTH = 22.dp
private val KEY_CELL = 22.dp
private val KEY_MOST = 13.dp
private val KEY_SOME = 8.dp

/** Fractions of the busiest month at which a cell moves up a shape. */
private const val EVERY = 0.9f
private const val MOST = 0.5f
