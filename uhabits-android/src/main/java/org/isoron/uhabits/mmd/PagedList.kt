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

package org.isoron.uhabits.mmd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyDefaultsMMD
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture

/**
 * A list that pages instead of scrolling, with a permanent scrollbar in MMD's own look.
 *
 * Paging (owner, 2026-09-29): a page turn brings **the last row you could see to the top**, so
 * nothing is skipped and you keep your place. `LazyColumnMMD` pages by a fixed number of items;
 * here the swipe step is "rows on screen less one" and the arrows turn by the exact height.
 *
 * The scrollbar: MMD's own sizes its thumb by *item count*, so a page of a few tall rows (the edit
 * screen) showed a sliver of a thumb. This one is drawn from the same parts (MMD's `chevron_*`
 * drawables, `LazyDefaultsMMD` track and border) but sized by height: the thumb is the share of
 * the list's height that is on screen, from each row's measured height (see [Heights]). Tap the arrows to turn a page, hold them to jump to either end, tap
 * the track to jump there.
 *
 * Ported from the Fit kit (`ui/kit/PagedList.kt`) to replace `LazyColumnMMD`, which paints every
 * screen twice (its rows are drawn at `alpha = 0` for the first frame), pages a fixed 4-6 *items*
 * at a time and sizes its thumb by item count.
 *
 * - Emit one item per row; a single tall item cannot be paged through.
 * - [canGrow]: a list whose rows can come and go while it is open keeps an inactive bar when it
 *   fits, so rows never shift sideways when it grows.
 * - [uniformRows]: every row is the same height, so there is nothing to learn by laying the whole
 *   list out off-screen first. With [canGrow] the bar is always there, so the probe is skipped
 *   entirely, which keeps a long list's first frame as cheap as its visible rows.
 */
@Composable
fun PagedList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    isScrollbarVisible: Boolean = true,
    canGrow: Boolean = false,
    uniformRows: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val probeState = rememberLazyListState()
    val wideProbeState = rememberLazyListState()
    // whether the list is longer than the page, as first measured; afterwards the list's own state
    val firstLook = remember { arrayOfNulls<Boolean>(1) }
    // every row's height, for the scrollbar's size and place and for paging back by exact pixels
    val heights = remember(state) { Heights() }
    // the row count, watched outside the layout: reading the list's layout from inside the layout
    // would make every pass of the list ask for another, a page that never stops redrawing
    var rows by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.layoutInfo.totalItemsCount }.collect { rows = it }
    }
    // A scrollbar only where the list is longer than the page (owner, 2026-10-06, as the Anki
    // app): no dead space beside a page that fits. Where rows can expand while the page is open,
    // [canGrow] keeps the space from the start so nothing shifts sideways.
    // The first frame must already be right: E Ink paints every frame, and a bar that appeared a
    // frame later narrowed the list and repainted all of it. So on the first measure the rows
    // are laid out once off-screen at full width to see whether they overflow, then the list is
    // drawn once, at its width. The rows are Compose's own LazyColumn, set up as LazyColumnMMD
    // sets it up (no fling, scrolling only ours): LazyColumnMMD draws its rows at alpha 0 on the
    // first frame and shows them on the second.
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val fill = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val gutterPx = PagedListDefaults.ScrollbarGutter.roundToPx()
        // On the first measure, and again when the number of rows changes, every row (up to ten
        // pages of them) is laid out off-screen: at full width, to see whether the list is longer
        // than the page, and, if it is, at the width beside the bar, for each row's real height.
        // The bar then fills its true share from the start instead of a guess that changed as
        // rows came on screen, and paging back lands on whole rows.
        val firstMeasure = firstLook[0] == null
        val seen = rows
        val recount = !firstMeasure && seen > 0 && seen != heights.count
        val probe = !(canGrow && uniformRows)
        if (probe && isScrollbarVisible && fill > 0 && (firstMeasure || recount)) {
            val tall = androidx.compose.ui.unit.Constraints(minWidth = 0, maxWidth = width, minHeight = 0, maxHeight = fill * PROBE_PAGES)
            subcompose("probe") {
                androidx.compose.foundation.lazy.LazyColumn(state = wideProbeState, userScrollEnabled = false, content = content)
            }.first().measure(tall)
            val wide = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { wideProbeState.layoutInfo }
            val overflows = wide.contentHeight() > fill
            firstLook[0] = overflows
            if (overflows || canGrow) {
                subcompose("probe-narrow") {
                    androidx.compose.foundation.lazy.LazyColumn(state = probeState, userScrollEnabled = false, content = content)
                }.first().measure(tall.copy(maxWidth = width - gutterPx))
                heights.reset(androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { probeState.layoutInfo })
            } else {
                heights.reset(wide)
            }
        } else if (firstMeasure) {
            firstLook[0] = false
        }
        val bar = isScrollbarVisible && (canGrow || if (firstMeasure || recount) firstLook[0] == true else state.canScrollForward || state.canScrollBackward)
        val gutter = if (bar) gutterPx else 0
        val list = subcompose("list") {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.pageOnSwipe(state, heights),
                state = state,
                userScrollEnabled = false,
                content = content,
            )
        }.first().measure(constraints.copy(minWidth = 0, maxWidth = width - gutter, minHeight = fill))
        val height = list.height
        val scrollbar = if (bar) {
            subcompose("bar") {
                PageScrollbar(
                    state = state,
                    heights = heights,
                    onPage = { forward -> scope.launch { if (forward) state.pageForward(heights) else state.pageBack(heights) } },
                    onEnd = { forward ->
                        scope.launch {
                            state.scrollToItem(if (forward) (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0) else 0)
                        }
                    },
                    onJump = { index -> scope.launch { state.scrollToItem(index) } },
                )
            }.first().measure(androidx.compose.ui.unit.Constraints.fixed(gutter, height))
        } else {
            null
        }
        layout(width, height) {
            list.placeRelative(0, 0)
            scrollbar?.placeRelative(width - gutter, 0)
        }
    }
}

/**
 * A short swipe turns a whole page (owner, 2026-10-02: swipes needed most of the screen). Left to
 * itself the list follows the finger, so a page took a swipe as long as the page. This looks at
 * the touch first: once the finger has moved past the touch slop up or down, the page turns and the
 * rest of the gesture is taken, so the list never drags. Taps never move that far and pass through.
 */
private fun Modifier.pageOnSwipe(state: LazyListState, heights: Heights? = null): Modifier = pointerInput(state) {
    val slop = viewConfiguration.touchSlop
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var moved = 0f
            var paged = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                moved += change.positionChange().y
                if (!paged && abs(moved) > slop) {
                    paged = true
                    val forward = moved < 0
                    launch { if (forward) state.pageForward(heights) else state.pageBack(heights) }
                }
                if (paged) change.consume()
            }
        }
    }
}

/**
 * The last row on screen, even if cut off, becomes the first. A row taller than the page is paged
 * through by the page instead of skipped.
 */
suspend fun LazyListState.pageForward(heights: Heights? = null) {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    val first = items.firstOrNull() ?: return
    val last = items.last()
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    if (last.index > first.index) {
        scrollToItem(last.index)
    } else if (first.offset + first.size > viewport) {
        // one row fills the page and goes on below it: the next page of the same row
        scrollBy(viewport.toFloat())
    } else {
        scrollToItem(first.index + 1)
    }
    heights?.update(layoutInfo)
}

/**
 * The page before, ending where this one starts: the first row on screen becomes the last whole
 * row of the page before, which starts on a whole row. Worked out in pixels from each row's height
 * ([heights], else the rows on screen), so it always moves back: before, it went back by pixels
 * and then on to the next whole row, which, above a tall row, was the row it started from, so
 * the list could not be paged back up (owner, 2026-10-07).
 */
suspend fun LazyListState.pageBack(heights: Heights? = null) {
    val info = layoutInfo
    if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return
    heights?.update(info)
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    val visible = info.visibleItemsInfo
    val average = if (visible.isEmpty()) 1f else visible.sumOf { it.size }.toFloat() / visible.size
    fun size(i: Int): Float = heights?.known(i) ?: visible.firstOrNull { it.index == i }?.size?.toFloat() ?: average
    val (i, o) = pageBackTarget(firstVisibleItemIndex, firstVisibleItemScrollOffset, viewport, ::size)
    scrollToItem(i, o)
    heights?.update(layoutInfo)
}

/**
 * Where the page before starts, as (row, pixels into it), from the first row on screen [index],
 * scrolled [offset] pixels into it. Always earlier than where it is (unless at the very top).
 */
internal fun pageBackTarget(index: Int, offset: Int, viewport: Int, size: (Int) -> Float): Pair<Int, Int> {
    if (offset > 0 && size(index) > viewport) {
        // inside a row taller than the page: the page of it above, or its top
        return index to (offset - viewport).coerceAtLeast(0)
    }
    // the rows above that fit with this one still whole at the bottom (the overlap paging forward
    // keeps), else without it
    fun startFor(room0: Float): Int {
        var room = room0
        var i = index
        while (i > 0 && size(i - 1) <= room) {
            room -= size(i - 1)
            i--
        }
        return i
    }
    val withOverlap = if (size(index) < viewport) startFor(viewport - size(index)) else index
    val start = if (withOverlap < index) withOverlap else startFor(viewport.toFloat())
    return when {
        start < index -> start to 0
        // part of the first row is above the page and nothing above fits with it: its top
        offset > 0 || index == 0 -> index to 0
        // the row above is taller than the page on its own: its last page, ending where this starts
        else -> (index - 1) to (size(index - 1) - viewport).toInt().coerceAtLeast(0)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageScrollbar(
    state: LazyListState,
    heights: Heights,
    onPage: (forward: Boolean) -> Unit,
    onEnd: (forward: Boolean) -> Unit,
    onJump: (index: Int) -> Unit,
) {
    // read when drawing, never in composition: the list's measurement of this frame decides,
    // so nothing here waits for a second frame
    val atStart = { !state.canScrollBackward }
    val atEnd = { !state.canScrollForward }
    // MMD's own slider colours are internal; they are the theme's surface and its ink
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    Column(
        // `LazyMMD.kt`'s own scrollbar column: 8dp either side of a 24dp arrow, so 40dp in all
        Modifier.fillMaxHeight().padding(horizontal = PagedListDefaults.ScrollbarSidePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScrollArrow(
            up = true,
            active = { !atStart() },
            onClick = { onPage(false) },
            onLongClick = { onEnd(false) },
        )
        Canvas(
            Modifier
                .width(PagedListDefaults.ScrollbarTrackWidth)
                .weight(1f)
                .border(LazyDefaultsMMD.sliderBackgroundBorderWidth, ink, LazyDefaultsMMD.sliderBackgroundCorners)
                .pointerInput(state) {
                    detectTapGestures { tap ->
                        if (state.layoutInfo.totalItemsCount > 0) {
                            heights.update(state.layoutInfo)
                            onJump(heights.indexAt(tap.y / size.height * heights.total()))
                        }
                    }
                },
        ) {
            val radius = CornerRadius(size.width / 2, size.width / 2)
            drawRoundRect(color = paper, size = size, cornerRadius = radius)
            val thumb = thumbOf(state, heights, atStart(), atEnd()) ?: return@Canvas
            // the keyboard can squeeze the track below the thumb's minimum (search): fill it then
            val minHeight = minOf(maxOf(size.height * 0.05f, LazyDefaultsMMD.VERTICAL_SLIDER_MIN_HEIGHT), size.height)
            val height = (size.height * thumb.second).coerceIn(minHeight, size.height)
            val top = ((size.height - height) * thumb.first).coerceIn(0f, size.height - height)
            drawRoundRect(color = ink, topLeft = Offset(0f, top), size = Size(size.width, height), cornerRadius = radius)
        }
        ScrollArrow(
            up = false,
            active = { !atEnd() },
            onClick = { onPage(true) },
            onLongClick = { onEnd(true) },
        )
    }
}

/**
 * Every row's height, by index. A list's rows differ a lot (a section title, a two-line row, a
 * body map), so the scrollbar and paging work from each row's own height: all of them (up to
 * [PROBE_PAGES] pages) measured off-screen when the list opens or its row count changes ([reset]),
 * then kept up to date as rows come on screen. Rows past that, in a very long list, count as the
 * average until they are seen.
 */
class Heights {
    private val sizes = HashMap<Int, Int>()
    var count = -1
        private set

    /** Starts again from a layout of every row (the off-screen one). */
    fun reset(info: LazyListLayoutInfo) {
        sizes.clear()
        count = info.totalItemsCount
        info.visibleItemsInfo.forEach { sizes[it.index] = it.size + info.mainAxisItemSpacing }
    }

    fun update(info: LazyListLayoutInfo) {
        if (info.totalItemsCount != count) {
            sizes.clear()
            count = info.totalItemsCount
        }
        info.visibleItemsInfo.forEach { sizes[it.index] = it.size + info.mainAxisItemSpacing }
    }

    /** A row's measured height, or null when it hasn't been measured. */
    fun known(index: Int): Float? = sizes[index]?.toFloat()

    private fun average() = if (sizes.isEmpty()) 1f else sizes.values.sum().toFloat() / sizes.size

    fun of(index: Int): Float = sizes[index]?.toFloat() ?: average()

    /** Height of rows 0 until [index]. */
    fun before(index: Int): Float {
        var known = 0f
        var unknown = 0
        for (i in 0 until index) {
            val h = sizes[i]
            if (h != null) known += h else unknown++
        }
        return known + unknown * average()
    }

    fun total(): Float = before(count)

    /** The row at [y] pixels down the whole list. */
    fun indexAt(y: Float): Int {
        var top = 0f
        for (i in 0 until count) {
            top += of(i)
            if (top > y) return i
        }
        return (count - 1).coerceAtLeast(0)
    }
}

/**
 * Where the thumb sits (0 = top, 1 = bottom) and how much of the track it fills: the share of the
 * list's height that is on screen. Null when the whole list is on screen (inactive bar).
 */
private fun thumbOf(state: LazyListState, heights: Heights, atStart: Boolean, atEnd: Boolean): Pair<Float, Float>? {
    val info = state.layoutInfo
    heights.update(info)
    if (atStart && atEnd) return null
    val items = info.visibleItemsInfo
    if (items.isEmpty() || info.totalItemsCount == 0) return null
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val content = heights.total().coerceAtLeast(viewport)
    val scrolled = heights.before(items.first().index) - items.first().offset
    val position = when {
        atStart -> 0f
        atEnd -> 1f
        else -> (scrolled / (content - viewport)).coerceIn(0f, 1f)
    }
    return position to (viewport / content).coerceIn(0f, 1f)
}

/**
 * One of the scrollbar's page arrows: MMD's filled chevron when it can be used, its dotted one for
 * "nothing more this way", as `LazyMMD.kt` draws them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScrollArrow(
    up: Boolean,
    active: () -> Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val filled = painterResource(if (up) com.mudita.mmd.R.drawable.chevron_filled_up else com.mudita.mmd.R.drawable.chevron_filled_down)
    val dotted = painterResource(if (up) com.mudita.mmd.R.drawable.chevron_dotted_up else com.mudita.mmd.R.drawable.chevron_dotted_down)
    Box(
        Modifier
            .padding(vertical = PagedListDefaults.ScrollbarArrowPadding)
            .size(PagedListDefaults.ScrollbarArrowSize)
            .combinedClickable(onClick = { if (active()) onClick() }, onLongClick = { if (active()) onLongClick() })
            .drawBehind { with(if (active()) filled else dotted) { draw(size) } },
    )
}

/** How many pages of rows are laid out off-screen to measure them when a list opens. */
private const val PROBE_PAGES = 10

/** The height of the rows laid out, top of the first to bottom of the last. */
private fun LazyListLayoutInfo.contentHeight(): Int =
    visibleItemsInfo.lastOrNull()?.let { it.offset + it.size - (visibleItemsInfo.first().offset) } ?: 0

object PagedListDefaults {
    /**
     * Width of MMD's scrollbar column: its 24dp page arrows (`LazyDefaultsMMD.navigateIconSize`)
     * with 8dp padding either side (`LazyMMD.kt`). Matches the Kompakt's Settings, whose rows end
     * 40dp from the edge (`eink-design-anki.md` → Calibration, in the folder above this repo).
     */
    val ScrollbarGutter = 40.dp

    /** The gap either side of an arrow, and above and below it, in `LazyMMD.kt`'s own scrollbar. */
    val ScrollbarSidePadding = 8.dp
    val ScrollbarArrowPadding = 16.dp

    /** The track's width, from `LazyDefaultsMMD.sliderBackgroundWidth`, which is internal to MMD. */
    val ScrollbarTrackWidth = 8.dp

    /** The page arrows, from `LazyDefaultsMMD.navigateIconSize`. */
    val ScrollbarArrowSize = 24.dp
}
