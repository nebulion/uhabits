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

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
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
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.snackbar.SnackbarDataMMD
import com.mudita.mmd.components.snackbar.SnackbarHostMMD
import com.mudita.mmd.components.snackbar.SnackbarHostStateMMD
import com.mudita.mmd.components.snackbar.SnackbarMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.menus.DropdownMenuItemMMD
import com.mudita.mmd.components.menus.DropdownMenuMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Timestamp

/**
 * The habits screen, built from Mudita Mindful Design components.
 *
 * Everything on screen shares one grid: the heading row and the habit rows both
 * open with a [HABIT_NAME_WIDTH] name column and then give every day column an
 * equal `weight`, so a heading, its cell and that cell's touch target always
 * line up. Both are inset from the right by [SCROLLBAR_GUTTER] — the heading
 * row explicitly, the habit rows because `LazyColumnMMD` reserves it for them.
 *
 * `LazyColumnMMD` supplies the paged scrolling and the permanent scrollbar.
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

/**
 * Width of `LazyColumnMMD`'s scrollbar: a 24dp arrow
 * (`LazyDefaultsMMD.navigateIconSize`) inside 8dp of horizontal padding either
 * side.
 *
 * It is **not** content padding. The scrollbar is a sibling of the list inside
 * a `Row`, where the list itself takes `weight(1f)`, and it is composed only
 * when `canScrollBackward || canScrollForward`. So the rows lose this width
 * when the list scrolls and get it back when it does not, and the day-heading
 * row — which sits outside the list — has to be inset to match *conditionally*.
 * A fixed inset here is right only for as long as there are enough habits to
 * scroll; filter the list down to three and every heading sits 40dp left of
 * its column.
 */
private val SCROLLBAR_GUTTER = 40.dp

/**
 * How many habits one drag moves.
 *
 * `LazyColumnMMD` does not scroll freely — it disables user scrolling and
 * calls `scrollToItem` in fixed steps — so this is not a speed, it is the size
 * of a page. `LazyDefaultsMMD.SCROLL_STEP` is 4, which was written for a list
 * of MMD's own row heights; at this screen's [CELL_HEIGHT] about eight rows
 * are on screen at once, so four moved the list half a screen and left most of
 * what you had just read still showing.
 *
 * Six moves most of a page while leaving a row or two of overlap, which is
 * enough to keep your place without paying for two repaints to cover the
 * distance one would.
 */
private const val SCROLL_STEP = 6

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
    /** Called once per page of scrolling, for the E Ink flash counter. */
    onScrolled: () -> Unit = {},
    /**
     * Transient messages -- a finished CSV export, an import that could not
     * be read. Set to null once shown.
     */
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    val hasSelection = selectedIds.isNotEmpty()
    val listState = rememberLazyListState()

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
        snackbarHost.showSnackbar(text)
        onMessageShown()
    }
    // Mirrors LazyColumnMMD's own condition for composing its scrollbar.
    val isScrollable by remember {
        derivedStateOf { listState.canScrollBackward || listState.canScrollForward }
    }

    // Scrolling ghosts worse than anything else on this screen: the whole page
    // of rows moves and leaves its previous position behind. LazyColumnMMD
    // pages by item rather than scrolling freely, so watching the first
    // visible index counts whole pages and never fires mid-drag. The first
    // emission is the initial composition, not a scroll, so it is dropped.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .drop(1)
            .collect { onScrolled() }
    }

    // Back leaves the selection before it leaves the screen, which is what the
    // ActionMode bar used to do for free.
    BackHandler(enabled = hasSelection, onBack = actions.onClearSelection)

    ThemeMMD {
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
                    scrollbarGutter = if (isScrollable) SCROLLBAR_GUTTER else 0.dp,
                    canGoOlder = dataOffset < maxDataOffset,
                    canGoNewer = dataOffset > 0,
                    onOlder = actions.onOlder,
                    onNewer = actions.onNewer,
                )
                HorizontalDividerMMD()

                if (habits.isEmpty()) {
                    EmptyState()
                } else {
                    LazyColumnMMD(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        scrollStep = SCROLL_STEP,
                    ) {
                        itemsIndexed(habits, key = { _, it -> it.id }) { index, habit ->
                            HabitRow(
                                habit = habit,
                                index = index,
                                listState = listState,
                                // The held row bolds its own border only while
                                // the drop would leave it where it is. Once
                                // the finger reaches a boundary that would
                                // move it, the line takes over and says so
                                // instead, so the two never claim at once.
                                isDragging = habit.id == draggingId &&
                                    dropBoundary == null,
                                dropAbove = dropBoundary == index,
                                dropBelow = index == habits.lastIndex &&
                                    dropBoundary == habits.size,
                                onDragChange = { id, boundary ->
                                    draggingId = id
                                    dropBoundary = boundary
                                },
                                isSelected = habit.id in selectedIds,
                                dayLabels = dayLabels,
                                dataOffset = dataOffset,
                                isShortToggleEnabled = isShortToggleEnabled,
                                areQuestionMarksEnabled = areQuestionMarksEnabled,
                                actions = actions,
                            )
                            HorizontalDividerMMD(
                                modifier = Modifier.padding(start = HABIT_NAME_WIDTH),
                                thickness = 1.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

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
        HorizontalDividerMMD(thickness = 1.dp)
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
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(if (enabled) filled else dotted),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

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
    index: Int,
    listState: LazyListState,
    isDragging: Boolean,
    dropAbove: Boolean,
    dropBelow: Boolean,
    onDragChange: (Long?, Int?) -> Unit,
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
            )
            .dropIndicator(
                above = dropAbove,
                below = dropBelow,
                ink = MaterialTheme.colorScheme.onBackground,
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
                .combinedClickable(
                    onClick = { actions.onClickHabit(habit) },
                    // A long press on an *unselected* habit selects it. On a
                    // selected one it must do nothing, or the second long
                    // press -- the one that picks the habit up -- would
                    // deselect it instead of starting a drag.
                    onLongClick = if (isSelected) null else {
                        { actions.onLongClickHabit(habit) }
                    },
                )
                .reorderDragHandle(
                    habit = habit,
                    index = index,
                    listState = listState,
                    enabled = isSelected,
                    onDragChange = onDragChange,
                    actions = actions,
                )
                .padding(start = 16.dp, end = 8.dp),
        )

        dayLabels.forEachIndexed { index, label ->
            // dayLabels run oldest-first, but index 0 of the checkmark array
            // is *today* — EntryList.getByInterval walks backwards. Without
            // this flip every row shows its history mirrored.
            val dataIndex = (dayLabels.size - 1 - index) + dataOffset
            CheckmarkCell(
                modifier = Modifier.weight(1f),
                value = habit.checkmarks.getOrElse(dataIndex) { UNKNOWN },
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
        // No gutter Spacer here: LazyColumnMMD's own contentPadding already
        // holds the scrollbar's width back from this row.
    }
}

private const val SKIP = 3
private const val YES_MANUAL = 2
private const val YES_AUTO = 1
private const val NO = 0
private const val UNKNOWN = -1

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
    val assets = LocalContext.current.assets
    return remember(assets) {
        FontFamily(Font(path = "fontawesome-webfont.ttf", assetManager = assets))
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
 * MMD's anatomy for this component actually calls for two stacked rules — a
 * black one over a white one — so both are drawn here, at the weight
 * `DividerDefaultsMMD.Thickness` really is.
 */
@Composable
private fun MessageBar(data: SnackbarDataMMD) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDividerMMD(color = ink)
        HorizontalDividerMMD(color = paper)
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
 * Picks a selected habit up on a second long press, and drops it where the
 * line shows.
 *
 * **The gesture is long press, then long press again.** The first selects the
 * habit, which is what a long press has always done here; the second, on a
 * habit that is already selected, picks it up, and it stays up until the
 * finger lifts. Two presses rather than one because a single long press has to
 * mean something on every row, and "select on a short hold, drag on a longer
 * one" is a distinction nobody can aim for.
 *
 * Selection is also what keeps the two detectors on this row out of each
 * other's way. `combinedClickable` still owns tap and long-press-to-select,
 * but it is given no long-press handler once a habit is selected, and this
 * detector is not installed at all until then -- so at any moment exactly one
 * of them wants the long press.
 *
 * **Reading the drag has to ignore consumption, and that is the whole reason
 * this did not work for three attempts.** `LazyColumnMMD` handles drags itself
 * to do its paging, and it claims them before this row sees them. Two separate
 * things follow, and each looks like a different bug:
 *
 *  - `verticalDrag` (and `detectDragGesturesAfterLongPress`) treat a claimed
 *    movement as a *cancelled* drag and simply give up.
 *  - `positionChange()` reports a claimed movement as **zero**, so even a loop
 *    that survives sees a finger that never moved.
 *
 * Together they produced a habit that visibly picked up and then refused to go
 * anywhere. A raw pointer loop with `positionChangeIgnoreConsumed` reads the
 * movement whoever claimed it; claiming it here as well stops the list paging
 * underneath the drag.
 *
 * **Nothing moves while you drag.** The list holds still and the only thing
 * that changes is a heavy black line at the boundary the habit would drop
 * into; the reorder happens once, on release. Shuffling rows under the finger
 * would repaint most of the screen for every row crossed, where moving a line
 * repaints two thin strips -- so a drag across thirty rows costs what a drag
 * across one costs, and the list does not rearrange itself while you are
 * aiming at it.
 *
 * When the finger is over the habit's own place, where releasing would change
 * nothing, there is no line and the held row bolds its own border instead.
 *
 * Carrying the line to the top or bottom visible row scrolls the list by one,
 * so a habit can travel further than a screenful without being dropped.
 *
 * Only the vertical component is read, and only on the name column: the day
 * cells own their own taps for toggling a checkmark.
 */
private fun Modifier.reorderDragHandle(
    habit: HabitCardUiState,
    index: Int,
    listState: LazyListState,
    enabled: Boolean,
    onDragChange: (Long?, Int?) -> Unit,
    actions: HabitListActions,
): Modifier = composed {
    if (!enabled) return@composed this
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edgeMarginPx = with(density) { EDGE_MARGIN.toPx() }
    // Read inside a long-lived gesture coroutine, so it is tracked rather than
    // captured: a captured copy goes stale as soon as the list changes.
    val currentIndex = rememberUpdatedState(index)
    pointerInput(habit.id) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val start = currentIndex.value
            var boundary: Int? = null
            var lastScrollAt = 0L
            onDragChange(habit.id, null)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val stillDown = change.pressed
                change.consume()
                if (!stillDown) break

                val info = listState.layoutInfo
                val here = info.visibleItemsInfo.firstOrNull { it.index == currentIndex.value }
                    ?: continue
                // Where the finger is in the list's own coordinates, not in
                // this row's. Measuring the finger's position rather than
                // accumulating how far it has travelled is what makes the drag
                // survive scrolling: when the list moves under the finger, a
                // running total is silently wrong by however far it moved,
                // while a position is still just a position.
                val pointerY = here.offset + change.position.y

                val next = info.nearestBoundary(pointerY, start)
                if (next != boundary) {
                    boundary = next
                    onDragChange(habit.id, next)
                }

                // The list moves only when the finger is actually *at* an
                // edge, and when it does it turns a whole page rather than
                // creeping a row at a time. Creeping was the wrong idea twice
                // over: it is the continuous motion this app removes
                // everywhere else, and it makes the list drift under a finger
                // that is trying to aim at a boundary in it. Anywhere but the
                // very edge, a drag only moves the line.
                //
                // A page is [SCROLL_STEP], the same distance the list turns
                // when you scroll it yourself, so a drag and a scroll travel
                // by the same amount.
                val now = System.currentTimeMillis()
                if (now - lastScrollAt >= PAGE_TURN_INTERVAL_MS) {
                    val top = info.viewportStartOffset
                    val bottom = info.viewportEndOffset
                    val target = when {
                        pointerY <= top + edgeMarginPx ->
                            listState.firstVisibleItemIndex - SCROLL_STEP
                        pointerY >= bottom - edgeMarginPx ->
                            listState.firstVisibleItemIndex + SCROLL_STEP
                        else -> null
                    }
                    if (target != null) {
                        lastScrollAt = now
                        scope.launch { listState.scrollToItem(target.coerceAtLeast(0)) }
                    }
                }
            }
            val landed = boundary
            onDragChange(null, null)
            if (landed != null) {
                // A boundary below the habit's own place absorbs the row it
                // vacates, so the index it lands on is one less than the
                // boundary; above, the boundary is the index itself.
                actions.onDropHabit(habit, if (landed > start) landed - 1 else landed)
            }
        }
    }
}

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
 * Small on purpose. This is not a zone the drag reacts to across, it is the
 * last sliver of the screen: everywhere else a drag moves the drop line and
 * nothing else, and the list stays exactly where it was put.
 */
private val EDGE_MARGIN = 12.dp

/**
 * How often holding at an edge turns another page.
 *
 * A page is a full repaint, so this is paced for a panel that takes a moment
 * to redraw. Slow enough to see each page land and stop, rather than watching
 * the list run away while deciding.
 */
private const val PAGE_TURN_INTERVAL_MS = 700L

/**
 * Draws the drop line for a drag, over the row rather than in it.
 *
 * An overlay, not a real divider, so that showing and hiding it cannot change
 * anyone's height. An indicator that took up space would push every row below
 * it down the moment it appeared, which is a screen repaint to say where one
 * habit would land.
 */
private fun Modifier.dropIndicator(
    above: Boolean,
    below: Boolean,
    ink: Color,
): Modifier = if (!above && !below) {
    this
} else {
    drawWithContent {
        drawContent()
        val thickness = DividerDefaultsMMD.Thickness.toPx()
        val y = if (above) 0f else size.height - thickness
        drawRect(color = ink, topLeft = Offset(0f, y), size = Size(size.width, thickness))
    }
}

