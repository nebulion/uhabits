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
const val MAX_CHECKMARK_COUNT = 60

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
    fun moveSelected(up: Boolean): Pair<Habit, Habit>? {
        val selected = _selectedIds.value
        if (selected.size != 1) return null
        return moveHabit(selected.first(), up)
    }

    /**
     * Moves one habit, by id, a single place up or down. See [moveSelected]
     * for why both halves of the move are needed.
     *
     * A drag calls this once per row it crosses rather than once per pixel of
     * travel, which is what keeps a drag affordable here: the list only ever
     * redraws when the order actually changes.
     */
    fun moveHabit(id: Long, up: Boolean): Pair<Habit, Habit>? {
        if (!isSortable) return null
        val from = (0 until cache.habitCount).firstOrNull {
            cache.getHabitByPosition(it)?.id == id
        } ?: return null
        val to = if (up) from - 1 else from + 1
        if (to < 0 || to >= cache.habitCount) return null
        val fromHabit = cache.getHabitByPosition(from) ?: return null
        val toHabit = cache.getHabitByPosition(to) ?: return null
        cache.reorder(from, to)
        return fromHabit to toHabit
    }

    /** Whether [moveSelected] would do anything in that direction. */
    fun canMoveSelected(up: Boolean): Boolean {
        if (!isSortable) return false
        val selected = _selectedIds.value
        if (selected.size != 1) return false
        val id = selected.first()
        val from = (0 until cache.habitCount).firstOrNull {
            cache.getHabitByPosition(it)?.id == id
        } ?: return false
        val to = if (up) from - 1 else from + 1
        return to >= 0 && to < cache.habitCount
    }

    // --- cache and midnight callbacks ------------------------------------

    override fun atMidnight() = cache.refreshAllHabits()

    override fun onItemChanged(position: Int) = rebuild()
    override fun onItemInserted(position: Int) = rebuild()
    override fun onItemMoved(oldPosition: Int, newPosition: Int) = rebuild()
    override fun onItemRemoved(position: Int) = rebuild()
    override fun onRefreshFinished() = rebuild()

    private fun rebuild() {
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
