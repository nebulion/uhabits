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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import org.isoron.uhabits.R
import org.isoron.uhabits.core.ui.screens.habits.show.views.TargetCardState
import org.isoron.uhabits.utils.toShortString

/**
 * How far through its target this habit is, over each period.
 *
 * `TargetChart` drew a rounded track in `lowContrastTextColor` with a filled
 * bar in the habit's colour and the numbers knocked out over the top. Every
 * one of those is a grey once the palette has no colour in it: the track
 * halftoned to a dot field, and the value sat on the boundary between filled
 * and unfilled, so it was legible over one and not the other.
 *
 * Here the track is an outline and the progress is solid, which is the same
 * filled-versus-outlined language the calendar and the frequency grid use, and
 * the numbers sit outside the bar where the fill cannot reach them. Overshoot
 * is not clipped silently: a period past its target fills completely and says
 * so in the figure beside it.
 */
@Composable
fun HabitTargetMMD(
    state: TargetCardState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        TextMMD(
            text = stringResource(R.string.target),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        state.intervals.forEachIndexed { index, interval ->
            TargetRow(
                label = intervalLabel(interval),
                value = state.values.getOrElse(index) { 0.0 },
                target = state.targets.getOrElse(index) { 0.0 },
            )
        }
    }
}

@Composable
private fun TargetRow(label: String, value: Double, target: Double) {
    val ink = MaterialTheme.colorScheme.onBackground
    val share = if (target > 0) (value / target).coerceIn(0.0, 1.0).toFloat() else 1f
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextMMD(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(LABEL_WIDTH).padding(end = 10.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(BAR_HEIGHT)
                .border(OUTLINE, ink),
        ) {
            // Zero draws nothing at all rather than a sliver, so an untouched
            // period is unmistakably empty.
            if (share > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(share)
                        .background(ink),
                )
            }
        }
        TextMMD(
            text = "${value.toShortString()}/${target.toShortString()}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (value >= target && target > 0) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/**
 * The period a row covers.
 *
 * The core hands these over as day counts, which is what the old card
 * translated too -- 91 is a quarter, and anything longer is a year.
 */
@Composable
private fun intervalLabel(interval: Int): String = stringResource(
    when (interval) {
        1 -> R.string.today
        7 -> R.string.week
        30 -> R.string.month
        91 -> R.string.quarter
        else -> R.string.year
    },
)

private val LABEL_WIDTH = 76.dp
private val BAR_HEIGHT = 28.dp
private val OUTLINE = 2.dp
