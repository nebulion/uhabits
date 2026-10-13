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

import org.isoron.uhabits.mmd.DashedDividerMMD
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import org.isoron.uhabits.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import org.isoron.uhabits.core.models.Streak
import org.isoron.uhabits.core.ui.screens.habits.show.views.StreakCardState
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The habit's longest runs, as a list rather than a bar chart.
 *
 * **Why the bars went.** `StreakChart` gave each bar one of four fills from an
 * alpha ramp (255 / 192 / 96 / contrast20) and `Halftone` turned each into a
 * dot pattern, with the count drawn *inside* the bar in a matching grey. So a
 * short streak was a grey blob carrying a grey numeral, and on the device the
 * "8" and the "3" could not be read at all. `eink-design-habits.md` says the opposite
 * in as many words: emphasis is inversion, never a weight of grey.
 *
 * The number itself can carry magnitude instead, at a size worth reading,
 * with the dates as its subtitle. The shape is the one every other list in
 * this app uses: a row, a hairline, the next row.
 *
 * **It is sorted longest-first here**, which the bar chart never had to be:
 * the core hands these over in date order and the bars made length obvious
 * whatever order they came in. Take the bars away and an unsorted list of
 * numbers under the heading "best streaks" is just a jumble -- the first
 * build of this read 10, 21, 13, 3, 7 down the page.
 *
 * Every row is drawn identically. Inverting the top row was tried in the
 * mockups and rejected: it is a lot of ink to repeat something the order
 * already says.
 */
@Composable
fun HabitStreaksMMD(
    state: StreakCardState,
    modifier: Modifier = Modifier,
) {
    val formatter = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    Column(modifier = modifier.fillMaxWidth()) {
        val sorted = remember(state.bestStreaks) {
            state.bestStreaks.sortedByDescending { it.length }
        }
        sorted.forEachIndexed { index, streak ->
            if (index > 0) {
                DashedDividerMMD(Modifier.padding(start = ROW_PADDING))
            }
            StreakRow(streak = streak, formatter = formatter)
        }
    }
}

@Composable
private fun StreakRow(streak: Streak, formatter: SimpleDateFormat) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(horizontal = ROW_PADDING, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A fixed column, so every count is right-aligned against the same
        // edge and the lengths can still be compared down the page -- which is
        // the one thing the bars were genuinely good at.
        TextMMD(
            text = streak.length.toString(),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(COUNT_WIDTH),
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            TextMMD(
                text = pluralStringResource(R.plurals.days_plural, streak.length),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextMMD(
                text = range(streak, formatter),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * The two dates, with anything they share said once.
 *
 * A streak inside one month reads "14 - 25 Jul 2025" rather than repeating the
 * month and the year, and one inside a year drops the first year. Written out
 * twice in full, most of these rows are the same word four times, and the row
 * is too narrow to hold it.
 */
private fun range(streak: Streak, formatter: SimpleDateFormat): String {
    val start = streak.start.toJavaDate()
    val end = streak.end.toJavaDate()
    val full = formatter.format(end)
    val startFull = formatter.format(start)
    val startParts = startFull.split(" ")
    val endParts = full.split(" ")
    if (startParts.size != 3 || endParts.size != 3) return "$startFull - $full"
    val sameYear = startParts[2] == endParts[2]
    val sameMonth = sameYear && startParts[1] == endParts[1]
    val shortStart = when {
        sameMonth -> startParts[0]
        sameYear -> "${startParts[0]} ${startParts[1]}"
        else -> startFull
    }
    return "$shortStart - $full"
}

private val ROW_PADDING = 20.dp

/** Width of the count column; fits three digits at `headlineLarge`. */
private val COUNT_WIDTH = 64.dp
