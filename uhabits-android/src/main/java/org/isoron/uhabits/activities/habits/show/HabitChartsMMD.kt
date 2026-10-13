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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.core.ui.screens.habits.show.views.BarCardState
import org.isoron.uhabits.core.ui.screens.habits.show.views.ScoreCardState
import org.isoron.uhabits.core.ui.views.toShortString
import org.isoron.uhabits.utils.toSimpleDataFormat
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Score and Bar charts, drawn in Compose and **stepped with ‹ ›** rather
 * than dragged (UI-PATTERNS B4).
 *
 * They were the last two `android.view.View`s on ShowHabit -- `ScoreChart`
 * and the core `BarChart` hosted in `AndroidView`, both scrolled back through
 * history by dragging, through `ScrollableChart`. A drag-scrolled chart is
 * continuous motion on E Ink: a run of partial repaints for as long as the
 * finger moves, and nowhere exact to stop. A step is one repaint and lands on
 * a whole page of buckets, newest on the right, so nothing is lost: every
 * bucket the drag could reach, the arrows reach too, and the range on screen
 * is written between them.
 *
 * Both read the same `ScoreCardState` / `BarCardState` the Views did, so no
 * core change was needed. Index 0 of either list is the newest bucket.
 *
 * Black and white only. Where the Views drew grey gridlines (`contrast20`,
 * halftoned), these draw **dotted** 1px lines, which is MMD's own answer to
 * "where you would use grey" (Avoid Grey Color). Text never goes below 14sp.
 */

/** The score over time: a line through one point per bucket, 0-100% gridlines on the left. */
@Composable
fun ScoreChartMMD(state: ScoreCardState, modifier: Modifier = Modifier) {
    val scores = state.scores
    val measurer = rememberTextMeasurer()
    val label = chartLabelStyle()
    val ink = MaterialTheme.colorScheme.onBackground
    val paper = MaterialTheme.colorScheme.background
    val gutter = remember(measurer, label) { measurer.measure("100%", label).size.width }
    // Reset to the newest page whenever the span changes.
    var page by rememberSaveable(state.bucketSize) { mutableIntStateOf(0) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val plotWidth = with(density) { maxWidth.toPx() } - gutter - with(density) { AXIS_GAP.toPx() }
        val columns = columnsFor(plotWidth, with(density) { MIN_COLUMN.toPx() })
        val pages = pageCount(scores.size, columns)
        val shown = page.coerceIn(0, pages - 1)
        Column {
            ChartStepper(
                range = rangeLabel(scores.map { it.timestamp }, shown, columns, state.bucketSize),
                canGoOlder = shown < pages - 1,
                canGoNewer = shown > 0,
                onOlder = { page = shown + 1 },
                onNewer = { page = shown - 1 },
            )
            Canvas(Modifier.fillMaxWidth().height(SCORE_CHART_HEIGHT)) {
                val footer = FOOTER_LINES * label.lineHeightPx(this)
                val top = label.lineHeightPx(this) / 2
                val plotLeft = gutter + AXIS_GAP.toPx()
                val plotHeight = size.height - footer - top
                val columnWidth = (size.width - plotLeft) / columns

                // Gridlines and their percentages, every 20%.
                for (i in 0..GRID_ROWS) {
                    val y = top + plotHeight * i / GRID_ROWS
                    dottedLine(ink, Offset(plotLeft, y), Offset(size.width, y))
                    val text = "${100 - i * 100 / GRID_ROWS}%"
                    val measured = measurer.measure(text, label)
                    drawText(
                        measured,
                        topLeft = Offset(gutter - measured.size.width.toFloat(), y - measured.size.height / 2f),
                    )
                }

                // The line first, then the points over it, so each point's
                // white ring cuts the line where it meets it.
                val points = (0 until columns).mapNotNull { c ->
                    val index = dataIndex(c, columns, shown)
                    scores.getOrNull(index)?.let { score ->
                        Offset(
                            plotLeft + columnWidth * (c + 0.5f),
                            top + plotHeight * (1f - score.value.toFloat()),
                        )
                    }
                }
                val stroke = LINE_WIDTH.toPx()
                points.zipWithNext { a, b -> drawLine(ink, a, b, strokeWidth = stroke, cap = StrokeCap.Round) }
                val outer = MARKER_RADIUS.toPx()
                points.forEach {
                    drawCircle(paper, radius = outer, center = it)
                    drawCircle(ink, radius = outer - stroke, center = it)
                }

                axisLabels(
                    measurer = measurer,
                    style = label,
                    dates = scores.map { it.timestamp },
                    columns = columns,
                    page = shown,
                    bucketSize = state.bucketSize,
                    left = plotLeft,
                    columnWidth = columnWidth,
                    baseline = top + plotHeight,
                )
            }
        }
    }
}

/**
 * How much was done per bucket: one solid bar per bucket with its value over
 * it. For a yes/no habit the value is the number of days done.
 */
@Composable
fun BarChartMMD(state: BarCardState, modifier: Modifier = Modifier) {
    val entries = state.entries
    val values = remember(entries) { entries.map { it.value / 1000.0 } }
    val maxValue = remember(values) { max(values.maxOrNull() ?: 0.0, 1.0) }
    val measurer = rememberTextMeasurer()
    val label = chartLabelStyle()
    val ink = MaterialTheme.colorScheme.onBackground
    var page by rememberSaveable(state.bucketSize, state.isNumerical) { mutableIntStateOf(0) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val columns = columnsFor(with(density) { maxWidth.toPx() }, with(density) { MIN_BAR_COLUMN.toPx() })
        val pages = pageCount(entries.size, columns)
        val shown = page.coerceIn(0, pages - 1)
        Column {
            ChartStepper(
                range = rangeLabel(entries.map { it.timestamp }, shown, columns, state.bucketSize),
                canGoOlder = shown < pages - 1,
                canGoNewer = shown > 0,
                onOlder = { page = shown + 1 },
                onNewer = { page = shown - 1 },
            )
            Canvas(Modifier.fillMaxWidth().height(BAR_CHART_HEIGHT)) {
                val line = label.lineHeightPx(this)
                val footer = FOOTER_LINES * line
                // Room above the tallest bar for its value.
                val top = line * 1.2f
                val plotHeight = size.height - footer - top
                val columnWidth = size.width / columns
                val barWidth = (columnWidth * 0.6f).coerceAtMost(BAR_WIDTH.toPx())

                for (i in 0..GRID_ROWS) {
                    val y = top + plotHeight * i / GRID_ROWS
                    dottedLine(ink, Offset(0f, y), Offset(size.width, y))
                }
                for (c in 0 until columns) {
                    val value = values.getOrNull(dataIndex(c, columns, shown)) ?: continue
                    if (value <= 0) continue
                    val height = (plotHeight * (value / maxValue)).toFloat()
                    val x = columnWidth * (c + 0.5f) - barWidth / 2
                    val y = top + plotHeight - height
                    drawRect(ink, topLeft = Offset(x, y), size = Size(barWidth, height))
                    val measured = measurer.measure(value.toShortString(), label)
                    drawText(
                        measured,
                        topLeft = Offset(
                            columnWidth * (c + 0.5f) - measured.size.width / 2f,
                            y - measured.size.height,
                        ),
                    )
                }
                axisLabels(
                    measurer = measurer,
                    style = label,
                    dates = entries.map { it.timestamp },
                    columns = columns,
                    page = shown,
                    bucketSize = state.bucketSize,
                    left = 0f,
                    columnWidth = columnWidth,
                    baseline = top + plotHeight,
                )
            }
        }
    }
}

/**
 * ‹ older · the range on screen · newer ›. MMD's filled chevrons when there
 * is somewhere to go, its dotted ones when there is not -- the same pair the
 * habits list's date pager uses.
 */
@Composable
internal fun ChartStepper(
    range: String,
    canGoOlder: Boolean,
    canGoNewer: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOlder, enabled = canGoOlder) {
            Icon(
                painter = painterResource(
                    if (canGoOlder) R.drawable.mmd_chevron_filled_left else R.drawable.mmd_chevron_dotted_left,
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        TextMMD(
            text = range,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNewer, enabled = canGoNewer) {
            Icon(
                painter = painterResource(
                    if (canGoNewer) R.drawable.mmd_chevron_filled_right else R.drawable.mmd_chevron_dotted_right,
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/** MMD's 14sp floor, black. */
@Composable
private fun chartLabelStyle(): TextStyle =
    MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onBackground)

private fun TextStyle.lineHeightPx(scope: DrawScope): Float = with(scope) {
    val h = if (lineHeight.isSp) lineHeight.toPx() else fontSize.toPx() * 1.3f
    h
}

/** How many buckets fit across [width] at no less than [minColumn] each. */
private fun columnsFor(width: Float, minColumn: Float): Int = max(1, floor(width / minColumn).toInt())

private fun pageCount(size: Int, columns: Int): Int = max(1, ceil(size / columns.toDouble()).toInt())

/**
 * The bucket drawn in column [c] of [columns] on page [page]: the rightmost
 * column of page 0 is the newest bucket, index 0.
 */
private fun dataIndex(c: Int, columns: Int, page: Int): Int = (columns - 1 - c) + page * columns

/** A 1px dotted line: what MMD draws where a View would have drawn grey. */
private fun DrawScope.dottedLine(color: Color, from: Offset, to: Offset) {
    val dot = 2.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
    val y = floor(from.y) + 0.5f
    drawLine(
        color = color,
        start = Offset(from.x, y),
        end = Offset(to.x, y),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dot, dot)),
    )
}

/**
 * Under each column: the month (one letter) where a month starts, otherwise
 * the day; the year on a second line where a year starts. Year buckets show
 * only the year. The same rules `ScoreChart` used, compared by month *key*
 * rather than by text, because June and July are both "J".
 */
private fun DrawScope.axisLabels(
    measurer: TextMeasurer,
    style: TextStyle,
    dates: List<Timestamp>,
    columns: Int,
    page: Int,
    bucketSize: Int,
    left: Float,
    columnWidth: Float,
    baseline: Float,
) {
    val month = "MMMMM".toSimpleDataFormat()
    val line = style.lineHeightPx(this)
    var previousMonth = Int.MIN_VALUE
    var previousYear = Int.MIN_VALUE
    for (c in 0 until columns) {
        val timestamp = dates.getOrNull(dataIndex(c, columns, page)) ?: continue
        val calendar = timestamp.toCalendar()
        val year = calendar[Calendar.YEAR]
        val centre = left + columnWidth * (c + 0.5f)
        fun put(text: String, row: Int) {
            val measured = measurer.measure(text, style)
            drawText(
                measured,
                topLeft = Offset(centre - measured.size.width / 2f, baseline + line * 0.2f + row * line),
            )
        }
        if (bucketSize >= 365) {
            // Two-digit years: a four-digit one at 14sp is wider than a column.
            put("'" + (year % 100).toString().padStart(2, '0'), 0)
            continue
        }
        val monthKey = year * 12 + calendar[Calendar.MONTH]
        if (monthKey != previousMonth) {
            put(month.format(timestamp.toJavaDate()), 0)
        } else {
            put(calendar[Calendar.DAY_OF_MONTH].toString(), 0)
        }
        if (year != previousYear && previousYear != Int.MIN_VALUE) {
            put("'" + (year % 100).toString().padStart(2, '0'), 1)
        }
        previousMonth = monthKey
        previousYear = year
    }
}

/**
 * The range of buckets on screen, oldest to newest, written between the
 * arrows: "Mar 2025 - Oct 2026", or years alone for year buckets.
 */
private fun rangeLabel(dates: List<Timestamp>, page: Int, columns: Int, bucketSize: Int): String {
    if (dates.isEmpty()) return ""
    val newest = dates.getOrNull(page * columns) ?: return ""
    val oldest = dates[((page + 1) * columns - 1).coerceAtMost(dates.lastIndex)]
    val pattern = when {
        bucketSize >= 365 -> "yyyy"
        bucketSize == 1 -> "dMMM"
        else -> "MMMyyyy"
    }
    val format = pattern.toSimpleDataFormat()
    val from = format.format(oldest.toJavaDate())
    val to = format.format(newest.toJavaDate())
    return if (from == to) to else "$from – $to"
}

/** Five rows of gridlines: 0, 20, 40, 60, 80, 100%. */
private const val GRID_ROWS = 5

/** Under the plot: the month or day, and the year where one starts. */
private const val FOOTER_LINES = 2.4f

private val SCORE_CHART_HEIGHT: Dp = 220.dp
private val BAR_CHART_HEIGHT: Dp = 200.dp
private val AXIS_GAP: Dp = 6.dp

/** Narrow enough for a dozen buckets across the Kompakt, wide enough for "28" at 14sp. */
private val MIN_COLUMN: Dp = 26.dp

/** Bars carry their value over them ("1.2k"), so their columns are wider. */
private val MIN_BAR_COLUMN: Dp = 34.dp
private val BAR_WIDTH: Dp = 14.dp
private val LINE_WIDTH: Dp = 2.dp
private val MARKER_RADIUS: Dp = 5.dp
