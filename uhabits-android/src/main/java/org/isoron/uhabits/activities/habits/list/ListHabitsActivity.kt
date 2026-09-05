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

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat.checkSelfPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.isoron.uhabits.BaseExceptionHandler
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.tasks.TaskRunner
import org.isoron.uhabits.core.utils.DateUtils
import org.isoron.uhabits.core.utils.MidnightTimer
import org.isoron.uhabits.database.AutoBackup
import org.isoron.uhabits.inject.ActivityContextModule
import org.isoron.uhabits.inject.DaggerHabitsActivityComponent
import org.isoron.uhabits.inject.HabitsActivityComponent
import org.isoron.uhabits.inject.HabitsApplicationComponent
import org.isoron.uhabits.utils.dismissCurrentDialog

class ListHabitsActivity : AppCompatActivity(), Preferences.Listener {

    lateinit var appComponent: HabitsApplicationComponent
    lateinit var component: HabitsActivityComponent
    lateinit var taskRunner: TaskRunner
    lateinit var model: HabitCardListModel
    lateinit var screen: ListHabitsScreen
    lateinit var prefs: Preferences
    lateinit var midnightTimer: MidnightTimer
    private val scope = CoroutineScope(Dispatchers.Main)

    private var permissionAlreadyRequested = false
    private val permissionLauncher =
        registerForActivityResult(RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                scheduleReminders()
            } else {
                Log.i("ListHabitsActivity", "POST_NOTIFICATIONS denied")
            }
        }

    private lateinit var menu: ListHabitsMenu

    override fun onQuestionMarksChanged() {
        invalidateOptionsMenu()
        menu.behavior.onPreferencesChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appComponent = (applicationContext as HabitsApplication).component
        component = DaggerHabitsActivityComponent
            .builder()
            .activityContextModule(ActivityContextModule(this))
            .habitsApplicationComponent(appComponent)
            .build()
        component.themeSwitcher.apply()

        prefs = appComponent.preferences
        prefs.addListener(this)
        midnightTimer = appComponent.midnightTimer
        screen = component.listHabitsScreen
        model = component.habitCardListModel
        taskRunner = appComponent.taskRunner
        menu = component.listHabitsMenu
        Thread.setDefaultUncaughtExceptionHandler(BaseExceptionHandler(this))
        component.listHabitsBehavior.onStartup()

        holdSplashUntilDrawn()

        val behavior = component.listHabitsBehavior
        val selectionBehavior = component.listHabitsSelectionMenuBehavior
        setContent {
            val habits by model.habits.collectAsState()
            val selectedIds by model.selectedIds.collectAsState()

            // Recomputed from the behavior whenever the selection changes: it
            // reads the selected habits back out of the model, so it has to be
            // keyed on the selection rather than remembered across it.
            val selection = remember(selectedIds) {
                SelectionCapabilities(
                    canEdit = selectionBehavior.canEdit(),
                    canArchive = selectionBehavior.canArchive(),
                    canUnarchive = selectionBehavior.canUnarchive(),
                    canMoveUp = model.canMoveSelected(up = true),
                    canMoveDown = model.canMoveSelected(up = false),
                )
            }

            // How many day columns fit, and how far back they can be paged.
            // The cache only holds MAX_CHECKMARK_COUNT days, so that bounds it.
            val columns = 3
            var dataOffset by remember { mutableStateOf(0) }
            val maxDataOffset = (MAX_CHECKMARK_COUNT - columns).coerceAtLeast(0)

            HabitListScreenMMD(
                habits = habits,
                selectedIds = selectedIds,
                dayLabels = buildDayLabels(columns, dataOffset),
                dataOffset = dataOffset,
                maxDataOffset = maxDataOffset,
                selection = selection,
                isShortToggleEnabled = prefs.isShortToggleEnabled,
                areQuestionMarksEnabled = prefs.areQuestionMarksEnabled,
                showArchived = prefs.showArchived,
                showCompleted = prefs.showCompleted,
                onScrolled = { screen.onScrolled() },
                message = screen.message,
                onMessageShown = { screen.onMessageShown() },
                actions = HabitListActions(
                    onToggle = { habit, timestamp, index ->
                        val current = habit.checkmarks.getOrElse(index) { Entry.UNKNOWN }
                        val next = Entry.nextToggleValue(
                            value = current,
                            isSkipEnabled = prefs.isSkipEnabled,
                            areQuestionMarksEnabled = prefs.areQuestionMarksEnabled,
                        )
                        val notes = habit.notes.getOrElse(index) { "" }
                        behavior.onToggle(habit.habit, timestamp, next, notes, 0f, 0f)
                    },
                    onEdit = { habit, timestamp ->
                        behavior.onEdit(habit.habit, timestamp, 0f, 0f)
                    },
                    onClickHabit = { habit ->
                        if (selectedIds.isEmpty()) {
                            behavior.onClickHabit(habit.habit)
                        } else {
                            model.toggleSelection(habit.id)
                        }
                    },
                    onLongClickHabit = { habit -> model.toggleSelection(habit.id) },
                    onCreateHabit = { menu.behavior.onCreateHabit() },
                    onOpenSettings = { menu.behavior.onViewSettings() },
                    onSort = { option ->
                        when (option) {
                            SortOption.MANUAL -> menu.behavior.onSortByManually()
                            SortOption.NAME -> menu.behavior.onSortByName()
                            SortOption.SCORE -> menu.behavior.onSortByScore()
                            SortOption.STATUS -> menu.behavior.onSortByStatus()
                        }
                    },
                    onToggleShowArchived = { menu.behavior.onToggleShowArchived() },
                    onToggleShowCompleted = { menu.behavior.onToggleShowCompleted() },
                    onOlder = {
                        dataOffset = (dataOffset + columns).coerceAtMost(maxDataOffset)
                    },
                    onNewer = {
                        dataOffset = (dataOffset - columns).coerceAtLeast(0)
                    },
                    onClearSelection = { model.clearSelection() },
                    onEditSelected = { selectionBehavior.onEditHabits() },
                    onArchiveSelected = { selectionBehavior.onArchiveHabits() },
                    onUnarchiveSelected = { selectionBehavior.onUnarchiveHabits() },
                    onExportSelected = {
                        behavior.onExportCSV(model.getSelected())
                        model.clearSelection()
                    },
                    // The move itself is two writes: the model moves the row on
                    // screen and hands back the pair to persist, and the
                    // behavior writes the new order to the database. Without
                    // the second the row springs back on the next refresh.
                    onMoveSelectedUp = {
                        model.moveSelected(up = true)?.let { (from, to) ->
                            behavior.onReorderHabit(from, to)
                        }
                    },
                    onMoveSelectedDown = {
                        model.moveSelected(up = false)?.let { (from, to) ->
                            behavior.onReorderHabit(from, to)
                        }
                    },
                    onMoveHabit = { habit, up ->
                        model.moveHabit(habit.id, up)?.let { (from, to) ->
                            behavior.onReorderHabit(from, to)
                        }
                    },
                    onDeleteSelected = { selectionBehavior.onDeleteHabits() },
                ),
            )
        }
    }


    /**
     * Column headings for the visible window. Index 0 is the oldest column on
     * screen and the last is `dataOffset` days before today, matching the
     * order the cells are drawn in.
     */
    private fun buildDayLabels(columns: Int, dataOffset: Int): List<DayLabel> {
        val today = DateUtils.getTodayWithOffset()
        return (columns - 1 downTo 0).map { i ->
            val timestamp = today.minus(i + dataOffset)
            val parts = DateUtils.formatHeaderDate(timestamp.toCalendar()).lines()
            DayLabel(
                weekday = parts.getOrElse(0) { "" }.uppercase(),
                dayOfMonth = parts.getOrElse(1) { "" },
                timestamp = timestamp,
            )
        }
    }

    override fun onPause() {
        midnightTimer.onPause()
        screen.onDetached()
        model.onDetached()
        model.cancelRefresh()
        dismissCurrentDialog()
        super.onPause()
    }

    override fun onResume() {
        model.refresh()
        screen.onAttached()
        model.onAttached()
        midnightTimer.onResume()

        if (appComponent.reminderScheduler.hasHabitsWithReminders()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                scheduleReminders()
            } else {
                if (checkSelfPermission(this, POST_NOTIFICATIONS) == PERMISSION_GRANTED) {
                    scheduleReminders()
                } else {
                    // If we have not requested the permission yet, request it. Otherwide do
                    // nothing. This check is necessary to avoid an infinite onResume loop in case
                    // the user denies the permission.
                    if (!permissionAlreadyRequested) {
                        Log.i("ListHabitsActivity", "Requestion permission: POST_NOTIFICATIONS")
                        permissionLauncher.launch(POST_NOTIFICATIONS)
                        permissionAlreadyRequested = true
                    }
                }
            }
        }

        taskRunner.run {
            try {
                AutoBackup(this@ListHabitsActivity).run()
            } catch (e: Exception) {
                Log.e("ListHabitActivity", "TaskRunner failed", e)
            }
        }
        parseIntents()
        super.onResume()
    }

    private fun scheduleReminders() {
        appComponent.reminderScheduler.scheduleAll()
    }

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        menu.onCreate(menuInflater, m)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        invalidateOptionsMenu()
        return menu.onItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        screen.onResult(request, result, data)
    }

    private fun parseIntents() {
        if (intent == null) return
        if (intent.action == ACTION_EDIT) {
            val habitId = intent.extras?.getLong("habit")
            val timestamp = intent.extras?.getLong("timestamp")
            if (habitId != null && timestamp != null) {
                val habit = appComponent.habitList.getById(habitId)!!
                component.listHabitsBehavior.onEdit(habit, Timestamp(timestamp), 0f, 0f)
            }
        }
        intent = null
    }

    // Non-null since androidx.activity 1.9, which Compose pulls in.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val ACTION_EDIT = "org.isoron.uhabits.ACTION_EDIT"
    }

    /**
     * Keeps the blank startup page up until the screen has actually drawn.
     *
     * Without this the window is shown the moment it exists and the app is
     * revealed mid-composition: the header lands, then the day headings, then
     * the rows, each a separate partial repaint. On a panel this slow that is
     * not a loading animation, it is a sequence of visibly wrong screens.
     *
     * Refusing the first draws holds the system's splash -- which the theme has
     * already made an empty white page -- so the app appears in one piece,
     * fully composed, on a background it already matched.
     *
     * The deadline is the important part. The condition cannot simply be
     * "wait for habits", because a new install legitimately has none and would
     * wait forever; and a draw held too long looks like a hang rather than a
     * load. So it releases on whichever comes first: the list having been
     * measured with something in it, or [SPLASH_HOLD_MS].
     */
    private fun holdSplashUntilDrawn() {
        val content = findViewById<android.view.View>(android.R.id.content)
        val deadline = android.os.SystemClock.uptimeMillis() + SPLASH_HOLD_MS
        content.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val laidOut = content.width > 0 && content.height > 0
                    val ready = laidOut && model.habits.value.isNotEmpty()
                    val expired = android.os.SystemClock.uptimeMillis() >= deadline
                    if (!ready && !expired) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }
}

/** How long the blank startup page may be held before it looks like a hang. */
private const val SPLASH_HOLD_MS = 1200L
