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

package org.isoron.uhabits.activities.habits.list

import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.absoluteValue
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.mudita.mmd.components.divider.DividerDefaultsMMD
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.snackbar.SnackbarDataMMD
import com.mudita.mmd.components.snackbar.SnackbarHostMMD
import com.mudita.mmd.components.snackbar.SnackbarHostStateMMD
import com.mudita.mmd.components.snackbar.SnackbarMMD
import com.mudita.mmd.components.menus.DropdownMenuItemMMD
import com.mudita.mmd.components.menus.DropdownMenuMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import org.isoron.uhabits.R
import org.isoron.uhabits.StartupFonts
import org.isoron.uhabits.mmd.DashedDividerMMD
import org.isoron.uhabits.mmd.MmdTheme
import org.isoron.uhabits.mmd.PagedList
import org.isoron.uhabits.mmd.PagedListDefaults
import org.isoron.uhabits.mmd.pageBack
import org.isoron.uhabits.mmd.pageForward
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.Timestamp

/**
 * The habits screen, built from Mudita Mindful Design components.
 *
 * Everything on screen shares one grid: the heading row and the habit rows both
 * open with a [HABIT_NAME_WIDTH] name column and then give every day column an
 * equal `weight`, so a heading, its cell and that cell's touch target always
 * line up. Both are inset from the right by the scrollbar's column, which this
 * list always keeps (see [PagedList]'s `canGrow`): the heading row explicitly,
 * the habit rows because the list sits beside it.
 *
 * [PagedList] supplies the paging and the scrollbar.
 */

/** What the screen needs from the outside, so the composable stays testable. */
data class HabitListActions(
    val onToggle: (HabitCardUiState, Timestamp, Int) -> Unit,
    val onEdit: (HabitCardUiState, Timestamp) -> Unit,
    val onClickHabit: (HabitCardUiState) -> Unit,
    val onLongClickHabit: (HabitCardUiState) -> Unit,
    val onCreateHabit: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onSort: (SortOption) -> Unit,
    val onToggleShowArchived: () -> Unit,
    val onToggleShowCompleted: () -> Unit,
    val onOlder: () -> Unit,
    val onNewer: () -> Unit,
    val onClearSelection: () -> Unit,
    val onEditSelected: () -> Unit,
    val onArchiveSelected: () -> Unit,
    val onUnarchiveSelected: () -> Unit,
    val onExportSelected: () -> Unit,
    /** A drag was released: put this habit at this position in the list. */
    val onDropHabit: (HabitCardUiState, Int) -> Unit,
    val onDeleteSelected: () -> Unit,
)

/**
 * Which actions the current selection allows, straight off
 * [org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsSelectionMenuBehavior].
 * Editing takes exactly one habit; archive and unarchive are all-or-nothing
 * across the selection.
 */
data class SelectionCapabilities(
    val canEdit: Boolean,
    val canArchive: Boolean,
    val canUnarchive: Boolean,
)

/** The orderings the sort menu offers. Colour is gone: every habit is one ink. */
enum class SortOption(val labelRes: Int) {
    MANUAL(R.string.manually),
    NAME(R.string.by_name),
    SCORE(R.string.by_score),
    STATUS(R.string.by_status),
}

/** A column heading: short weekday over day-of-month. */
data class DayLabel(val weekday: String, val dayOfMonth: String, val timestamp: Timestamp)

private val HABIT_NAME_WIDTH = 140.dp

/**
 * Row height, and the minimum a day cell may be. Touch targets are 56dp rather
 * than Material's 48dp: a refresh is slow enough that undoing a mistap is
 * expensive. The cells are laid out by weight and so end up wider than this.
 */
private val CELL_HEIGHT = 56.dp

@Composable
fun HabitListScreenMMD(
    habits: List<HabitCardUiState>,
    selectedIds: Set<Long>,
    /** Oldest column first, matching the order the cells are laid out in. */
    dayLabels: List<DayLabel>,
    dataOffset: Int,
    maxDataOffset: Int,
    selection: SelectionCapabilities,
    isShortToggleEnabled: Boolean,
    areQuestionMarksEnabled: Boolean,
    showArchived: Boolean,
    showCompleted: Boolean,
    actions: HabitListActions,
    /**
     * Transient messages -- a finished CSV export, an import that could not
     * be read. Set to null once shown.
     */
    message: String? = null,
    onMessageShown: () -> Unit = {},
    /**
     * True until the habits have finished loading.
     *
     * The content area is left empty meanwhile, and the activity holds the
     * first draw until this is false, so the whole screen -- header, dates,
     * rows and scrollbar -- reaches the panel in one paint. That hold was
     * once seconds long; with the load now about as quick as composing the
     * screen, it costs next to nothing.
     */
    isLoading: Boolean = false,
    /**
     * Called once, when the loaded screen is complete: rows laid out and the
     * scrollbar composed. The activity holds the first draw until then, so the
     * whole screen reaches the panel in one paint.
     */
    onReadyToShow: () -> Unit = {},
) {
    val hasSelection = selectedIds.isNotEmpty()
    val listState = rememberLazyListState()

    // Released once rows are on screen (or there are none to show). The
    // scrollbar is laid out in the same pass as the rows -- [PagedList] keeps
    // its column on this list whether or not it scrolls -- so, unlike
    // `LazyColumnMMD`, there is no second frame to wait for.
    LaunchedEffect(isLoading) {
        if (isLoading) return@LaunchedEffect
        snapshotFlow { habits.isEmpty() || listState.layoutInfo.totalItemsCount > 0 }
            .first { it }
        onReadyToShow()
    }

    // Where the list stood when a habit was dropped, put back as soon as the
    // reordered list arrives. Without it the list scrolled on release: a lazy
    // list keeps its first visible item *by key* in view, and a drop moves
    // that item. `requestScrollToItem` overrides the key for exactly that one
    // measure, so the restore lands in the same frame as the new order.
    var restoreAfterDrop by remember { mutableStateOf<DropRestore?>(null) }
    restoreAfterDrop?.let { restore ->
        if (restore.before !== habits) {
            SideEffect {
                listState.requestScrollToItem(restore.index, restore.offset)
                restoreAfterDrop = null
            }
        }
    }

    // The drag in progress, if any. Both are hoisted here rather than kept
    // inside a row because the indicator belongs to a *boundary between* rows,
    // which no single row owns, and because a drag must not outlive the screen.
    var draggingId by remember { mutableStateOf<Long?>(null) }

    // Where the held habit would land, as a boundary index: 0 is above the
    // first row, `habits.size` is below the last. Null when nothing is held,
    // or when the finger is still over the habit's own place and dropping
    // would do nothing -- so the absence of a line means "this changes
    // nothing", which is worth being able to see.
    var dropBoundary by remember { mutableStateOf<Int?>(null) }
    val snackbarHost = remember { SnackbarHostStateMMD() }

    // Messages used to go through a Material Snackbar, whose surface is a
    // dark grey card with an elevation shadow -- neither of which exists
    // anywhere else in this app. SnackbarMMD is white on black with a 3dp
    // rule and no elevation, straight from eInkColorScheme.
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        // withDismissAction, or there is no close button. It defaults to
        // false, which is why the bar had nothing to tap and sat there until
        // it timed out.
        snackbarHost.showSnackbar(text, withDismissAction = true)
        onMessageShown()
    }
    // Back leaves the selection before it leaves the screen, which is what the
    // ActionMode bar used to do for free.
    BackHandler(enabled = hasSelection, onBack = actions.onClearSelection)

    MmdTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHostMMD(snackbarHost) { MessageBar(it) } },
            topBar = {
                // One rule for both bars, drawn here rather than by their own
                // showDivider, which cannot produce the 3dp rule: TopAppBarMMD
                // hands its 3dp `dividerLineHeight` to a raw Material3
                // HorizontalDivider as a *width* and never passes a thickness,
                // so the line falls back to Material's 1dp.
                Column {
                    if (hasSelection) {
                        SelectionTopBar(
                            count = selectedIds.size,
                            selection = selection,
                            actions = actions,
                        )
                    } else {
                        TopAppBarMMD(
                            showDivider = false,
                            title = {
                                TextMMD(
                                    text = stringResource(R.string.main_activity_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            actions = {
                                IconButton(onClick = actions.onCreateHabit) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_action_add_dark),
                                        contentDescription = stringResource(R.string.add_habit),
                                        tint = Color.Unspecified,
                                    )
                                }
                                SortMenuButton(
                                    showArchived = showArchived,
                                    showCompleted = showCompleted,
                                    actions = actions,
                                )
                                // No sync button: sync runs by itself
                                // (AutoSync), after every change and while
                                // the app is open.
                                IconButton(onClick = actions.onOpenSettings) {
                                    GlyphText(
                                        glyph = stringResource(R.string.fa_cog),
                                        size = 20.sp,
                                    )
                                }
                            },
                        )
                    }
                    HorizontalDividerMMD()
                }
            },
        ) { insets ->
            Column(modifier = Modifier.fillMaxSize().padding(insets)) {
                DayHeadingRow(
                    dayLabels = dayLabels,
                    scrollbarGutter = PagedListDefaults.ScrollbarGutter,
                    canGoOlder = dataOffset < maxDataOffset,
                    canGoNewer = dataOffset > 0,
                    onOlder = actions.onOlder,
                    onNewer = actions.onNewer,
                )
                HorizontalDividerMMD()

                if (isLoading) {
                    // Nothing. The activity holds the first draw until the
                    // habits are in, so this is never on the panel; it only
                    // keeps "still loading" from being drawn as "no habits".
                } else if (habits.isEmpty()) {
                    EmptyState()
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .reorderGesture(
                                listState = listState,
                                habits = habits,
                                onDragChange = { id, boundary ->
                                    draggingId = id
                                    dropBoundary = boundary
                                },
                                onBeforeDrop = {
                                    restoreAfterDrop = DropRestore(
                                        before = habits,
                                        index = listState.firstVisibleItemIndex,
                                        offset = listState.firstVisibleItemScrollOffset,
                                    )
                                },
                                actions = actions,
                            ),
                    ) {
                        PagedList(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            // Habits come and go while the screen is open
                            // (filters, archive, a sync), so the scrollbar's
                            // column is always kept and the day columns never
                            // shift sideways to make room for it. Every row is
                            // one height, so nothing is laid out off-screen.
                            canGrow = true,
                            uniformRows = true,
                        ) {
                            itemsIndexed(habits, key = { _, it -> it.id }) { index, habit ->
                                // The boundary above the very first row has no
                                // separator of its own -- the header's rule
                                // sits there -- so the list carries an empty
                                // one, which exists only to be bolded when a
                                // habit is being dropped at the top.
                                if (index == 0) RowSeparator(bold = dropBoundary == 0, drawn = false)
                                HabitRow(
                                    habit = habit,
                                    // The held row bolds its own border only
                                    // while the drop would leave it where it
                                    // is. Once the finger reaches a boundary
                                    // that would move it, the line takes over.
                                    isDragging = habit.id == draggingId &&
                                        dropBoundary == null,
                                    isSelected = habit.id in selectedIds,
                                    dayLabels = dayLabels,
                                    dataOffset = dataOffset,
                                    isShortToggleEnabled = isShortToggleEnabled,
                                    areQuestionMarksEnabled = areQuestionMarksEnabled,
                                    actions = actions,
                                )
                                RowSeparator(bold = dropBoundary == index + 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The list's position when a habit was dropped, and the list it was taken from. */
private class DropRestore(
    val before: List<HabitCardUiState>,
    val index: Int,
    val offset: Int,
)

/**
 * The header while habits are selected: a count, a way out, and the batch
 * actions.
 *
 * This replaces the `ActionMode` bar. MuditaOS has no counterpart to
 * `ActionMode` — every screen on the device keeps its actions in the header —
 * and `ActionMode` also drags in an overflow menu, which the device has
 * nowhere. Putting the actions in a second `TopAppBarMMD` keeps the whole
 * screen inside the design system and lets the selection live in
 * [HabitCardListModel] rather than in an `ActionMode.Callback`.
 *
 * Which actions appear is [SelectionCapabilities], straight off the core
 * behavior: edit needs exactly one habit, and archive/unarchive are offered
 * only when they apply to every habit selected.
 */
@Composable
private fun SelectionTopBar(
    count: Int,
    selection: SelectionCapabilities,
    actions: HabitListActions,
) {
    TopAppBarMMD(
        // The caller draws the rule under whichever bar is showing.
        showDivider = false,
        title = {
            TextMMD(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        navigationIcon = {
            IconButton(onClick = actions.onClearSelection) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_cancel),
                    contentDescription = stringResource(R.string.clear),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        },
        actions = {
            if (selection.canEdit) {
                IconButton(onClick = actions.onEditSelected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_edit_dark),
                        contentDescription = stringResource(R.string.edit),
                        tint = Color.Unspecified,
                    )
                }
            }
            if (selection.canArchive) {
                IconButton(onClick = actions.onArchiveSelected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_archive_dark),
                        contentDescription = stringResource(R.string.archive),
                        tint = Color.Unspecified,
                    )
                }
            }
            if (selection.canUnarchive) {
                IconButton(onClick = actions.onUnarchiveSelected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_unarchive_dark),
                        contentDescription = stringResource(R.string.unarchive),
                        tint = Color.Unspecified,
                    )
                }
            }
            // Export lives here rather than on ShowHabit, where it used to be
            // and where it no longer fits: that header carries a habit's name
            // beside its actions, and a fifth full-size target left too little
            // for the name. This bar's title is a selection count -- one or two
            // characters -- so it has the room, and exporting a selection is
            // strictly more than the single habit ShowHabit could manage.
            IconButton(onClick = actions.onExportSelected) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_download_dark),
                    contentDescription = stringResource(R.string.export_to_csv),
                    tint = Color.Unspecified,
                )
            }
            IconButton(onClick = actions.onDeleteSelected) {
                GlyphText(glyph = stringResource(R.string.fa_trash), size = 20.sp)
            }
        },
    )
}

/**
 * Sort and filter, in `DropdownMenuMMD`. Its default border is what makes the
 * pane read as a surface at all: there is no elevation here, so an unbordered
 * white panel on a white page is invisible.
 */
@Composable
private fun SortMenuButton(
    showArchived: Boolean,
    showCompleted: Boolean,
    actions: HabitListActions,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_action_filter_dark),
            contentDescription = stringResource(R.string.filter),
            tint = Color.Unspecified,
        )
    }
    DropdownMenuMMD(expanded = expanded, onDismissRequest = { expanded = false }) {
        SortOption.entries.forEach { option ->
            DropdownMenuItemMMD(
                text = { TextMMD(stringResource(option.labelRes)) },
                onClick = {
                    expanded = false
                    actions.onSort(option)
                },
            )
        }
        // MMD's own Menus KDoc separates groups with `DashedDivider()`.
        DashedDividerMMD()
        DropdownMenuItemMMD(
            text = { TextMMD(stringResource(R.string.hide_archived)) },
            trailingIcon = { if (!showArchived) CheckGlyph() },
            onClick = {
                expanded = false
                actions.onToggleShowArchived()
            },
        )
        DropdownMenuItemMMD(
            text = { TextMMD(stringResource(R.string.hide_completed)) },
            trailingIcon = { if (!showCompleted) CheckGlyph() },
            onClick = {
                expanded = false
                actions.onToggleShowCompleted()
            },
        )
    }
}

/** A tick marking an active filter; state shows as presence, not colour. */
@Composable
private fun CheckGlyph() = GlyphText(glyph = stringResource(R.string.fa_check), size = 14.sp)

/**
 * A date-pager arrow.
 *
 * Disabled uses MMD's dotted chevron rather than a greyed solid one. MMD ships
 * `chevron_dotted_*` alongside `chevron_filled_*` for precisely this: with no
 * midtone available, "unavailable" is expressed as a broken outline, the same
 * way the Kompakt e-reader draws its disabled scroll arrow.
 */
@Composable
private fun PagerButton(
    filled: Int,
    dotted: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    repeatWhileHeld: Boolean = false,
) {
    if (!repeatWhileHeld) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                painter = painterResource(if (enabled) filled else dotted),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        return
    }

    val scope = rememberCoroutineScope()
    // Read inside a gesture that outlives the composition that started it.
    val step = rememberUpdatedState(onClick)
    val live = rememberUpdatedState(enabled)
    Box(
        modifier = Modifier
            .size(48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!live.value) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    // One page immediately, so a tap behaves exactly as it did.
                    step.value()
                    val held = scope.launch {
                        delay(PAGER_HOLD_DELAY_MS)
                        var interval = PAGER_REPEAT_START_MS
                        while (isActive && live.value) {
                            step.value()
                            delay(interval)
                            interval = (interval - PAGER_REPEAT_ACCEL_MS)
                                .coerceAtLeast(PAGER_REPEAT_MIN_MS)
                        }
                    }
                    waitForUpOrCancellation()
                    held.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (enabled) filled else dotted),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * How long the forward arrow waits before it starts repeating, and how fast it
 * goes once it does.
 *
 * Only the forward arrow repeats. Going back through history is browsing --
 * you stop when you see the week you were looking for -- but coming *back* to
 * today is travel: you already know where you are going and every page in
 * between is in the way. The catch on this panel is that each page is a
 * repaint, so this accelerates into it rather than starting fast: a pause long
 * enough that a tap is never mistaken for a hold, then pages that speed up
 * while your thumb stays down, floored well short of a rate the screen could
 * not keep up with.
 *
 * It stops on its own when `canGoNewer` goes false, so holding past today
 * costs nothing.
 */
private const val PAGER_HOLD_DELAY_MS = 450L
private const val PAGER_REPEAT_START_MS = 320L
private const val PAGER_REPEAT_ACCEL_MS = 45L
private const val PAGER_REPEAT_MIN_MS = 140L

/**
 * Day headings, with the pager sitting in the space the habit names occupy on
 * the rows below. Keeping the arrows on this row rather than in a bottom bar
 * means the whole screen is one grid: heading, cell and touch target all line
 * up in the same column.
 */
@Composable
private fun DayHeadingRow(
    dayLabels: List<DayLabel>,
    scrollbarGutter: Dp,
    canGoOlder: Boolean,
    canGoNewer: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(end = scrollbarGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(HABIT_NAME_WIDTH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PagerButton(
                filled = R.drawable.mmd_chevron_filled_left,
                dotted = R.drawable.mmd_chevron_dotted_left,
                enabled = canGoOlder,
                onClick = onOlder,
            )
            PagerButton(
                filled = R.drawable.mmd_chevron_filled_right,
                dotted = R.drawable.mmd_chevron_dotted_right,
                enabled = canGoNewer,
                onClick = onNewer,
                // Hold to run forward through the weeks. Only this way round:
                // see [PAGER_HOLD_DELAY_MS].
                repeatWhileHeld = true,
            )
        }
        dayLabels.forEach { label ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextMMD(
                    text = label.weekday,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextMMD(
                    text = label.dayOfMonth,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun HabitRow(
    habit: HabitCardUiState,
    isDragging: Boolean,
    isSelected: Boolean,
    dayLabels: List<DayLabel>,
    dataOffset: Int,
    isShortToggleEnabled: Boolean,
    areQuestionMarksEnabled: Boolean,
    actions: HabitListActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CELL_HEIGHT)
            .then(
                when {
                    // A held habit whose drop would not move it draws its own
                    // border at the weight of an MMD rule -- heavier than the
                    // selection box it grew out of. That is the "release me
                    // here and nothing happens" state, and it is the only
                    // thing shown then: as soon as the finger reaches a
                    // boundary that would move the habit, the drop line takes
                    // over and this goes back to the plain selection box.
                    isDragging -> Modifier.border(
                        DividerDefaultsMMD.Thickness,
                        MaterialTheme.colorScheme.onBackground,
                    )
                    isSelected -> Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground)
                    else -> Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextMMD(
            text = habit.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(HABIT_NAME_WIDTH)
                // A tap only. Holding the name -- to select it, or to drag it
                // -- is read by the list's container ([reorderGesture]), so
                // the row must not have a long click of its own.
                .clickable { actions.onClickHabit(habit) }
                .padding(start = 16.dp, end = 8.dp),
        )

        dayLabels.forEachIndexed { index, label ->
            // dayLabels run oldest-first, but index 0 of the checkmark array
            // is *today* — EntryList.getByInterval walks backwards. Without
            // this flip every row shows its history mirrored.
            val dataIndex = (dayLabels.size - 1 - index) + dataOffset
            CheckmarkCell(
                modifier = Modifier.weight(1f),
                value = cellState(habit, habit.checkmarks.getOrElse(dataIndex) { UNKNOWN }),
                hasNotes = habit.notes.getOrElse(dataIndex) { "" }.isNotBlank(),
                areQuestionMarksEnabled = areQuestionMarksEnabled,
                onClick = {
                    if (habit.isNumerical || !isShortToggleEnabled) {
                        actions.onEdit(habit, label.timestamp)
                    } else {
                        actions.onToggle(habit, label.timestamp, dataIndex)
                    }
                },
                onLongClick = {
                    if (habit.isNumerical || isShortToggleEnabled) {
                        actions.onEdit(habit, label.timestamp)
                    } else {
                        actions.onToggle(habit, label.timestamp, dataIndex)
                    }
                },
            )
        }
        // No gutter Spacer here: the row sits beside the scrollbar's column,
        // not under it.
    }
}

private const val SKIP = 3
private const val YES_MANUAL = 2
private const val YES_AUTO = 1
private const val NO = 0
private const val UNKNOWN = -1

/**
 * The state a cell draws for [value], one entry of [habit]'s checkmarks.
 *
 * A yes/no habit's entries already are states. A numerical habit's are the
 * amount recorded, times 1000, and have to be judged against the per-period
 * target first — otherwise every non-zero amount reads as a yes, and going
 * over an "at most" habit's limit still earns a check.
 */
private fun cellState(habit: HabitCardUiState, value: Int): Int {
    if (!habit.isNumerical || value == UNKNOWN || value == SKIP) return value
    val amount = value / 1000.0
    val met = when (habit.targetType) {
        NumericalHabitType.AT_LEAST -> amount >= habit.threshold
        NumericalHabitType.AT_MOST -> amount <= habit.threshold
    }
    return if (met) YES_MANUAL else NO
}

/**
 * Line weight of every outlined glyph, in pixels.
 *
 * Shared so the hollow cross an unrecorded day draws and the hollow check an
 * automatic yes draws cannot drift apart: the only thing separating either
 * from its filled counterpart should be that it is an outline, never how heavy
 * that outline is.
 */
private const val HOLLOW_STROKE_WIDTH = 2f

/**
 * One day's state for one habit.
 *
 * Everything is black on white, so the states are told apart by glyph and by
 * fill rather than by colour: a solid check for a manual yes, a hollow one for
 * an automatic yes, a dash for a skip, and a cross for a no.
 *
 * Unknown is a **hollow cross**: the same `fa_times` a recorded no draws, at
 * the same size, outlined instead of filled. MMD ships no icon set to take a
 * glyph from — only chevrons and a loader — so this follows its state
 * *language* instead: `SwitchMMD`, `CheckboxMMD` and `RadioButtonMMD` all
 * separate states by outline versus fill, never by a weight of grey. A day
 * that was never recorded is therefore the unfilled version of the mark a
 * missed day gets.
 *
 * It was a hollow circle, which said "no data" clearly enough but introduced a
 * third shape to the row for a state that is really just an unconfirmed no —
 * and, being a different glyph, it needed its own size to stop out-weighing
 * the check beside it. Sharing the cross's shape and size means the only
 * difference left between a missed day and an unrecorded one is fill, which is
 * exactly the distinction MMD draws everywhere else.
 *
 * The `areQuestionMarksEnabled` gate is kept: with it off, unknown draws as a
 * *solid* cross, which is what the old button did.
 */
@Composable
private fun CheckmarkCell(
    modifier: Modifier = Modifier,
    value: Int,
    hasNotes: Boolean,
    areQuestionMarksEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val glyph = when (value) {
        SKIP -> stringResource(R.string.fa_skipped)
        NO -> stringResource(R.string.fa_times)
        UNKNOWN -> stringResource(R.string.fa_times)
        else -> stringResource(R.string.fa_check)
    }
    // The two hollow states are told apart by shape: a check that was credited
    // automatically, and a cross for a day never recorded.
    val isHollow = value == YES_AUTO || (value == UNKNOWN && areQuestionMarksEnabled)
    Box(
        modifier = modifier
            .height(CELL_HEIGHT)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphText(
            glyph = glyph,
            // One size for every state now. The circle needed its own smaller
            // one because at 18sp a filled disc out-weighed the check beside
            // it; a cross has no such problem, and an unrecorded day drawn
            // smaller than a missed one would be saying something this design
            // does not mean.
            size = 18.sp,
            hollow = isHollow,
        )
        if (hasNotes) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 8.dp)
                    .size(5.dp)
                    .background(ink),
            )
        }
    }
}

/** FontAwesome glyph, in the app's bundled face. */
@Composable
private fun GlyphText(
    glyph: String,
    size: androidx.compose.ui.unit.TextUnit,
    hollow: Boolean = false,
) {
    TextMMD(
        text = glyph,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = size,
        textAlign = TextAlign.Center,
        fontFamily = fontAwesome(),
        // One stroke width for every hollow glyph, so the outlined cross and
        // the outlined check read as the same weight of line.
        style = if (hollow) {
            LocalTextStyle.current.copy(drawStyle = Stroke(width = HOLLOW_STROKE_WIDTH))
        } else {
            LocalTextStyle.current
        },
    )
}

/**
 * The FontAwesome face the glyphs come from, as a Compose family. Same asset
 * the old views loaded through `InterfaceUtils.getFontAwesome`.
 */
@Composable
private fun fontAwesome(): FontFamily {
    val context = LocalContext.current
    return remember(context) {
        FontFamily(androidx.compose.ui.text.font.Typeface(StartupFonts.fontAwesome(context)))
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        TextMMD(
            text = stringResource(R.string.no_habits_found),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A transient message, drawn to MMD's snackbar anatomy rather than to its
 * component defaults, because two of those defaults are wrong on this theme.
 *
 * **The action label would be invisible.** `SnackbarDefaultsMMD` takes its
 * `actionContentColor` from `inversePrimary`, and `eInkColorScheme` defines
 * that as **white** — on a container it also defines as white. Every colour
 * here is therefore stated: black on white, with nothing left to a role whose
 * value happens to suit a different palette.
 *
 * **And its own divider is the `TopAppBarMMD` bug again.** `SnackbarMMD` hands
 * its 3dp `dividerLineHeight` to a raw Material3 `HorizontalDivider` as a
 * *width* and passes no thickness, so the rule falls back to Material's 1dp.
 * That much is real, and is why the rule is drawn here instead.
 *
 * **One rule, not two.** An earlier version of this drew a black rule over a
 * white one, on the belief that MMD's anatomy called for a stacked pair. It
 * does not: `SnackbarMMD` draws a single `HorizontalDivider` coloured
 * `dividerColor`, whose default is `inverseOnSurface` — black on this theme.
 * The second, white rule was invisible on a white page and did nothing but
 * insert 3dp of blank space, leaving the black line floating detached above
 * the bar. Checked against `mmd-core` rather than against a screenshot, which
 * is rule 3 at the top of PROJECT-STATUS and was not followed the first time.
 */
@Composable
private fun MessageBar(data: SnackbarDataMMD) {
    // Literal black and white rather than colour-scheme roles. The bar was
    // reading `surface` for its container and drawing see-through -- the habits
    // showed through it -- which is the same class of fault as the unfilled
    // text fields: a role that eInkColorScheme leaves unspecified renders as no
    // fill at all, and a snackbar with no fill is not a surface, it is text
    // lying on top of the list. This screen is pure black on white by design,
    // so there is nothing a role can express here that the two colours cannot.
    val ink = Color.Black
    val paper = Color.White
    Column(modifier = Modifier.fillMaxWidth().background(paper)) {
        HorizontalDividerMMD(color = ink)
        SnackbarMMD(
            snackbarData = data,
            containerColor = paper,
            contentColor = ink,
            actionContentColor = ink,
            dismissActionContentColor = ink,
            dividerColor = ink,
            // Drawn above, at the right weight.
            showDivider = false,
        )
    }
}

/**
 * Owns holding a habit: let go to select it, or keep holding and drag it to a
 * new place (pattern P7 in MMD-GUIDE, the same in all of the owner's apps).
 * Lives on the list, not on a row.
 *
 * - **Hold, then let go:** the habit's selection toggles, as the long press on
 *   its name used to.
 * - **Hold, then move without letting go:** the habit is picked up. The list
 *   stays still; the separator the habit would drop into thickens; release
 *   puts it there. Holding at the top or bottom edge turns one page a second.
 *
 * There is no "select first, then hold again": that was this screen's old
 * gesture, which the docs described and the other apps dropped.
 *
 * **It is owned by a container around the list, not by a row.** The gesture
 * used to be a `pointerInput` on the dragged row's own name column, which
 * failed once the list scrolled: the finger's position was read as
 * `thatRow.offset + local y`, so turning a page put the row out of
 * `visibleItemsInfo` and no drop boundary could be computed again, and a
 * `LazyColumn` may dispose a row it has scrolled past, destroying the very
 * node hosting the gesture. A container is never disposed and its coordinates
 * are the list's own.
 *
 * **It works in the `Initial` pass**, ahead of the list's own swipe-to-page,
 * and consumes nothing until a hold is recognised, so taps, swipes and the
 * day cells' own long press behave as before. Only a hold on a habit's
 * *name* counts; the day cells keep theirs (it opens the entry editor).
 */
private fun Modifier.reorderGesture(
    listState: LazyListState,
    habits: List<HabitCardUiState>,
    onDragChange: (Long?, Int?) -> Unit,
    onBeforeDrop: () -> Unit,
    actions: HabitListActions,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edgeMarginPx = with(density) { EDGE_MARGIN.toPx() }
    val nameWidthPx = with(density) { HABIT_NAME_WIDTH.toPx() }
    val current = rememberUpdatedState(habits)
    val act = rememberUpdatedState(actions)
    val beforeDrop = rememberUpdatedState(onBeforeDrop)

    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            if (down.position.x > nameWidthPx) return@awaitEachGesture
            val start = listState.layoutInfo.indexAt(down.position.y)
                ?: return@awaitEachGesture
            val habit = current.value.getOrNull(start) ?: return@awaitEachGesture

            // A long press is "still down, and still roughly here, when the
            // timeout expires". Watching for it without consuming leaves the
            // row's own tap and the list's swipe untouched.
            val slop = viewConfiguration.touchSlop
            val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull true
                    if (!change.pressed) return@withTimeoutOrNull true
                    if ((change.position.y - down.position.y).absoluteValue > slop) {
                        return@withTimeoutOrNull true
                    }
                }
                @Suppress("UNREACHABLE_CODE") true
            }
            // Null means the timeout won, which is exactly the hold.
            if (moved != null) return@awaitEachGesture

            // Held: letting go here selects; moving past the slop picks it up.
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: return@awaitEachGesture
                // The row must not take this as a tap, nor the list page.
                change.consume()
                if (!change.pressed) {
                    act.value.onLongClickHabit(habit)
                    return@awaitEachGesture
                }
                if ((change.position.y - down.position.y).absoluteValue > slop) break
            }

            var boundary: Int? = null
            var lastPageTurn = 0L
            var pageTurn: Job? = null
            onDragChange(habit.id, null)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val stillDown = change.pressed
                // Claimed here so the list cannot page underneath the drag.
                change.consume()
                if (!stillDown) break

                val info = listState.layoutInfo
                val next = info.nearestBoundary(change.position.y, start)
                if (next != boundary) {
                    boundary = next
                    onDragChange(habit.id, next)
                }

                val now = System.currentTimeMillis()
                if (now - lastPageTurn >= PAGE_TURN_INTERVAL_MS) {
                    val forward = change.position.y >= info.viewportEndOffset - edgeMarginPx
                    val back = change.position.y <= info.viewportStartOffset + edgeMarginPx
                    if (forward || back) {
                        lastPageTurn = now
                        pageTurn?.cancel()
                        pageTurn = scope.launch {
                            if (forward) listState.pageForward() else listState.pageBack()
                        }
                    }
                }
            }
            pageTurn?.cancel()
            val landed = boundary
            onDragChange(null, null)
            if (landed != null) {
                beforeDrop.value()
                // A boundary below the habit's own place absorbs the row it
                // vacates, so the index it lands on is one less than the
                // boundary; above, the boundary is the index itself.
                act.value.onDropHabit(habit, if (landed > start) landed - 1 else landed)
            }
        }
    }
}

/** Which item is under [y], in the list's own coordinates. */
private fun LazyListLayoutInfo.indexAt(y: Float): Int? =
    visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.index

/**
 * The boundary between rows nearest to [pointerY], or null when that boundary
 * is one of the dragged habit's own two and dropping would change nothing.
 *
 * Boundary *k* is the line above row *k*; the boundary below the last row is
 * one past its index. Every visible row is measured rather than assuming they
 * are all [CELL_HEIGHT] tall, because a habit with a long name wraps to two
 * lines and is not.
 */
private fun LazyListLayoutInfo.nearestBoundary(pointerY: Float, start: Int): Int? {
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    visibleItemsInfo.forEach { item ->
        listOf(item.index to item.offset, item.index + 1 to item.offset + item.size)
            .forEach { (boundary, y) ->
                val distance = kotlin.math.abs(pointerY - y)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = boundary
                }
            }
    }
    // A habit sits between its own two boundaries, so landing on either is a
    // move to where it already is: report none, and let the row bold its own
    // border instead of drawing a line that promises a change.
    return best?.takeUnless { it == start || it == start + 1 }
}

/**
 * How near an edge counts as being at it, while dragging.
 *
 * A row's worth. It was 12dp, and that was too mean to aim at: the last
 * separator on screen sits a whole row above the bottom, so reaching the strip
 * below it meant dragging your finger off the panel. A row means the list
 * offers to move as soon as you are past the last boundary you can see, which
 * is exactly the moment you need it to.
 *
 * This is not the creeping scroll it replaced. Crossing into this zone turns
 * one page and then waits; everywhere above it, a drag moves the drop line and
 * the list stays exactly where it was put.
 */
private val EDGE_MARGIN = CELL_HEIGHT

/**
 * How often holding at an edge turns another page.
 *
 * A page is a full repaint *and* it moves the ground you are aiming at, so the
 * pace is set by how long it takes to read where you have landed and decide,
 * not by how fast the list could go. 700ms ran away underneath the finger and
 * 1400ms was longer than the decision takes.
 */
private const val PAGE_TURN_INTERVAL_MS = 1000L

/**
 * The rule between two habits, and the thing a drag points at.
 *
 * A drop is shown by **bolding the separator that is already there**, not by
 * drawing a second line beside it. The two are easy to confuse in code and
 * impossible to confuse on screen: an overlaid indicator sat at the row's own
 * top edge and ran the full width, while the real rules are inset to start
 * under the day columns, so it read as an extra line that had appeared rather
 * than as this gap being the one.
 *
 * The slot is always [DividerDefaultsMMD.Thickness] tall whether it is bolded
 * or not, and the dotted hairline is centred inside it. Growing a 1px line into a 3dp
 * one would otherwise push every row below it down by 2dp the moment a drag
 * passed over -- a whole-screen repaint to say where one habit would land.
 *
 * [drawn] is false for the one above the first row, which exists only to be
 * bolded: the header's own rule is already sitting in that position.
 */
@Composable
private fun RowSeparator(bold: Boolean, drawn: Boolean = true) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DividerDefaultsMMD.Thickness)
            // Inset exactly like every other rule on this screen, bolded or
            // not. A drop marker that ran the full width would not be *this*
            // separator getting heavier, it would be a different line
            // appearing in the same place.
            .padding(start = HABIT_NAME_WIDTH),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bold -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DividerDefaultsMMD.Thickness)
                    .background(MaterialTheme.colorScheme.onBackground),
            )
            // MMD's row divider is dotted; a solid hairline is the structural
            // weight, for headers.
            drawn -> DashedDividerMMD()
        }
    }
}

