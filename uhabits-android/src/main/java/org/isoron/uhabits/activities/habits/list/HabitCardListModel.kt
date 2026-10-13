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

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsMenuBehavior
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsSelectionMenuBehavior
import org.isoron.uhabits.core.utils.MidnightTimer
import org.isoron.uhabits.inject.ActivityScope
import javax.inject.Inject

/**
 * How many days of history the cache holds. Also the limit on how far the
 * habits screen can page back, since nothing beyond this is loaded.
 */
/**
 * How many days of check-marks are held for each habit.
 *
 * The screen shows three columns; the rest is how far the date arrows can page
 * back. Fetching them is measured at 24ms for twelve days and 28ms for sixty
 * across forty habits, so this is not a performance number -- it is how much
 * history the arrows reach.
 */
const val MAX_CHECKMARK_COUNT = 15

/**
 * One habit, flattened into everything the list needs to draw a row.
 *
 * **`@Stable` is what makes the list cheap to touch.** `checkmarks` and
 * `notes` are `IntArray` and `Array<String>`, and the Compose compiler treats
 * an array-typed property as unconditionally unstable no matter what `equals`
 * says. An unstable parameter makes the composable that takes it
 * non-skippable, so with [rebuild] handing every row a freshly allocated state
 * on every cache callback, one checkmark tap recomposed *every visible row*
 * rather than the cell that changed. On a panel this slow that is work you can
 * feel.
 *
 * The annotation is a promise, so it is worth saying why it is a true one
 * here. The arrays are never written through: `HabitCardListCache` replaces
 * the reference (`data.checkmarks[id] = newCheckmarks`) rather than mutating
 * in place, so the content comparison below cannot silently go stale. And
 * although [habit] is a mutable model object, nothing on the row reads through
 * it -- every field the row draws is flattened into this class beside it, and
 * [habit] is carried only as a handle for the action callbacks. `@Stable`
 * rather than `@Immutable` for exactly that reason: it is the weaker claim,
 * and the one this class can actually keep.
 */
@Stable
data class HabitCardUiState(
    val habit: Habit,
    val id: Long,
    val name: String,
    val isNumerical: Boolean,
    val isArchived: Boolean,
    val score: Double,
    /**
     * Index 0 is today and index N is N days ago: [HabitCardListCache] fills
     * this from `EntryList.getByInterval`, which walks backwards from today.
     */
    val checkmarks: IntArray,
    val notes: Array<String>,
    val unit: String,
    /**
     * The per-period target, `targetValue / frequency.denominator` — not
     * `targetValue`. This is what a numerical entry is compared against.
     */
    val threshold: Double,
    val targetType: NumericalHabitType,
) {
    // IntArray/Array give identity equals by default, which would defeat the
    // change detection this state feeds.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HabitCardUiState) return false
        return id == other.id &&
            name == other.name &&
            isNumerical == other.isNumerical &&
            isArchived == other.isArchived &&
            score == other.score &&
            checkmarks.contentEquals(other.checkmarks) &&
            notes.contentEquals(other.notes) &&
            unit == other.unit &&
            threshold == other.threshold &&
            targetType == other.targetType
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + score.hashCode()
        result = 31 * result + checkmarks.contentHashCode()
        result = 31 * result + notes.contentHashCode()
        return result
    }
}

/**
 * Holds the state behind the habits screen.
 *
 * This is what `HabitCardListAdapter` used to be, minus the RecyclerView. That
 * class was doing two unrelated jobs: adapting a list for a RecyclerView, and
 * being the app's habit-list state — it owned the selection, the
 * [org.isoron.uhabits.core.models.ModelObservable], and the cache and midnight
 * listener registrations, and it satisfied both `…Behavior.Adapter`
 * interfaces. Only the first job disappears with Compose, so the rest moved
 * here, where the per-item `notifyItemChanged` calls collapse into emitting a
 * new list.
 */
@ActivityScope
class HabitCardListModel @Inject constructor(
    private val cache: HabitCardListCache,
    private val preferences: Preferences,
    private val midnightTimer: MidnightTimer,
) : HabitCardListCache.Listener,
    MidnightTimer.MidnightListener,
    ListHabitsMenuBehavior.Adapter,
    ListHabitsSelectionMenuBehavior.Adapter {

    private val _hasLoaded = MutableStateFlow(false)

    /**
     * Whether the cache has finished its first refresh.
     *
     * This is not "are there habits". The list emits an empty list before it
     * emits a real one -- [onAttached] rebuilds straight away, while the
     * cache's own refresh is still only queued -- so emptiness cannot tell a
     * database that is still loading apart from one that has nothing in it.
     * The startup screen needs exactly that distinction: it holds the first
     * frame until the list is real, and a new install with no habits has to
     * release it immediately rather than wait out a timeout.
     */
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    private val _habits = MutableStateFlow<List<HabitCardUiState>>(emptyList())
    val habits: StateFlow<List<HabitCardUiState>> = _habits.asStateFlow()

    /** Selected habits, by id, so the set survives cache rebuilds. */
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    init {
        cache.setListener(this)
        cache.setCheckmarkCount(MAX_CHECKMARK_COUNT)
        cache.secondaryOrder = preferences.defaultSecondaryOrder
        cache.primaryOrder = preferences.defaultPrimaryOrder
    }

    fun onAttached() {
        cache.onAttached()
        midnightTimer.addListener(this)
        rebuild()
    }

    fun onDetached() {
        cache.onDetached()
        midnightTimer.removeListener(this)
    }

    fun cancelRefresh() = cache.cancelTasks()

    fun hasNoHabit(): Boolean = cache.hasNoHabit()

    val isSortable: Boolean
        get() = cache.primaryOrder == HabitList.Order.BY_POSITION

    // --- selection -------------------------------------------------------

    fun toggleSelection(id: Long) {
        val current = _selectedIds.value
        _selectedIds.value = if (id in current) current - id else current + id
        rebuild()
    }

    override fun clearSelection() {
        if (_selectedIds.value.isEmpty()) return
        _selectedIds.value = emptySet()
        rebuild()
    }

    override fun getSelected(): List<Habit> =
        _habits.value.filter { it.id in _selectedIds.value }.map { it.habit }

    val isSelectionEmpty: Boolean
        get() = _selectedIds.value.isEmpty()

    // --- ListHabitsMenuBehavior.Adapter ----------------------------------

    override fun refresh() = cache.refreshAllHabits()

    override fun setFilter(matcher: HabitMatcher) = cache.setFilter(matcher)

    override var primaryOrder: HabitList.Order
        get() = cache.primaryOrder
        set(value) {
            cache.primaryOrder = value
            preferences.defaultPrimaryOrder = value
        }

    override var secondaryOrder: HabitList.Order
        get() = cache.secondaryOrder
        set(value) {
            cache.secondaryOrder = value
            preferences.defaultSecondaryOrder = value
        }

    // --- optimistic cache edits ------------------------------------------

    /**
     * Both of these touch only the cache, so the list stays responsive while
     * the database command runs. The change is lost on the next refresh, by
     * which point the real result has arrived.
     */
    override fun performRemove(selected: List<Habit>) {
        for (habit in selected) cache.remove(habit.id!!)
    }

    fun performReorder(from: Int, to: Int) = cache.reorder(from, to)

    /**
     * Moves the single selected habit one place up or down.
     *
     * This is what replaced drag-to-reorder. Dragging a row is a gesture that
     * tracks the finger continuously, which is the one kind of motion this
     * panel handles worst -- every pixel of travel is a partial repaint, and a
     * long drag leaves a trail of them. Two discrete moves cost one repaint
     * each and can be aimed exactly.
     *
     * Both halves of a move are needed and they are not the same thing:
     * [HabitCardListCache.reorder] moves the row that is on screen, and
     * `ListHabitsBehavior.onReorderHabit` writes the new position to the
     * database. Doing only the first looks right until the next refresh puts
     * the row back.
     *
     * Returns the pair of habits to persist, or null when the move is not
     * possible -- nothing selected, more than one selected, the list is not in
     * manual order, or the habit is already at the end it is moving towards.
     */
    /**
     * Moves one habit, by id, to [toIndex].
     *
     * Called once, when a drag is released, rather than once per row the
     * finger crosses. Nothing moves while the drag is in progress -- the list
     * holds still and only the drop indicator travels -- so the whole reorder
     * is a single change and a single repaint, however far the habit went.
     *
     * Both halves of the move are needed and they are not the same thing:
     * [HabitCardListCache.reorder] moves the row on screen, and
     * `ListHabitsBehavior.onReorderHabit` writes the new position to the
     * database. Doing only the first looks right until the next refresh puts
     * the row back.
     *
     * Returns the pair of habits to persist, or null when the move is not
     * possible or would not move anything -- no such habit, the list is not in
     * manual order, or the habit is already where it was dropped.
     */
    fun moveHabitTo(id: Long, toIndex: Int): Pair<Habit, Habit>? {
        if (!isSortable) return null
        val count = cache.habitCount
        val from = (0 until count).firstOrNull {
            cache.getHabitByPosition(it)?.id == id
        } ?: return null
        val to = toIndex.coerceIn(0, count - 1)
        if (to == from) return null
        val fromHabit = cache.getHabitByPosition(from) ?: return null
        val toHabit = cache.getHabitByPosition(to) ?: return null
        cache.reorder(from, to)
        return fromHabit to toHabit
    }

    /** Whether a habit can be dragged at all: only a manually ordered list. */
    fun canReorder(): Boolean = isSortable

    // --- cache and midnight callbacks ------------------------------------

    override fun atMidnight() = cache.refreshAllHabits()

    // The cache reports progress one habit at a time, and each of these used
    // to rebuild the whole list and emit it -- so loading forty habits meant
    // forty rebuilds and forty recompositions, measured at 1.76 seconds on the
    // device against 24ms of actual work behind them. The rebuilds are
    // coalesced instead: the first request posts one to the end of the main
    // thread's queue and the rest join it, so a burst collapses into a single
    // emission. Correctness is unchanged -- a rebuild always still happens,
    // just once per burst rather than once per habit.
    override fun onItemChanged(position: Int) = scheduleRebuild()

    override fun onItemInserted(position: Int) = scheduleRebuild()
    override fun onItemMoved(oldPosition: Int, newPosition: Int) = scheduleRebuild()
    override fun onItemRemoved(position: Int) = scheduleRebuild()

    override fun onRefreshFinished() {
        _hasLoaded.value = true
        rebuild()
    }

    private val rebuildHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isRebuildScheduled = false

    private fun scheduleRebuild() {
        if (isRebuildScheduled) return
        isRebuildScheduled = true
        rebuildHandler.post {
            isRebuildScheduled = false
            rebuild()
        }
    }

    private fun rebuild() = org.isoron.uhabits.utils.traced("model rebuild") {
        isRebuildScheduled = false
        val selected = _selectedIds.value
        _habits.value = (0 until cache.habitCount).mapNotNull { position ->
            val habit = cache.getHabitByPosition(position) ?: return@mapNotNull null
            val id = habit.id ?: return@mapNotNull null
            HabitCardUiState(
                habit = habit,
                id = id,
                name = habit.name,
                isNumerical = habit.isNumerical,
                isArchived = habit.isArchived,
                score = cache.getScore(id),
                checkmarks = cache.getCheckmarks(id),
                notes = cache.getNotes(id),
                unit = habit.unit,
                threshold = habit.targetValue / habit.frequency.denominator,
                targetType = habit.targetType,
            )
        }
        // Drop selections for habits that no longer exist.
        val live = _habits.value.map { it.id }.toSet()
        if (!live.containsAll(selected)) {
            _selectedIds.value = selected.intersect(live)
        }
    }
}
