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

import org.isoron.uhabits.sync.AutoSync
import kotlinx.coroutines.withContext
import android.content.ContentUris
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.AndroidDirFinder
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.activities.HabitsDirFinder
import org.isoron.uhabits.activities.common.dialogs.CheckmarkDialog
import org.isoron.uhabits.activities.common.dialogs.ConfirmDeleteDialog
import org.isoron.uhabits.activities.common.dialogs.NumberDialog
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.ui.callbacks.OnConfirmedCallback
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitMenuPresenter
import org.isoron.uhabits.core.ui.screens.habits.show.ShowHabitPresenter
import org.isoron.uhabits.intents.IntentFactory
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dismissCurrentAndShow
import org.isoron.uhabits.utils.dismissCurrentDialog
import org.isoron.uhabits.utils.showMessage

class ShowHabitActivity : AppCompatActivity(), CommandRunner.Listener {

    private lateinit var commandRunner: CommandRunner
    private lateinit var menuPresenter: ShowHabitMenuPresenter
    private lateinit var view: ShowHabitView
    private lateinit var habit: Habit
    private lateinit var preferences: Preferences
    private lateinit var themeSwitcher: AndroidThemeSwitcher

    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Whether the habit's state has reached the view at least once.
     *
     * The first frame of this screen is real chrome around placeholder
     * content -- an empty title, a summary reading 0%/0/+0/0, the tab row and
     * its rules -- because [ShowHabitView] declares those defaults and
     * `setContentView` happens before anything has been computed. On a panel
     * that shows every repaint, that made entering a habit a two-part
     * performance: the furniture, then the numbers.
     */
    private var hasState = false
    private lateinit var presenter: ShowHabitPresenter
    private val screen = Screen()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appComponent = (applicationContext as HabitsApplication).component
        val habitList = appComponent.habitList
        habit = habitList.getById(ContentUris.parseId(intent.data!!))!!
        preferences = appComponent.preferences
        commandRunner = appComponent.commandRunner

        themeSwitcher = AndroidThemeSwitcher(this, preferences)
        themeSwitcher.apply()

        presenter = ShowHabitPresenter(
            commandRunner = commandRunner,
            habit = habit,
            habitList = habitList,
            preferences = preferences,
            screen = screen
        )

        view = ShowHabitView(this)

        val menuPresenter = ShowHabitMenuPresenter(
            commandRunner = commandRunner,
            habit = habit,
            habitList = habitList,
            screen = screen,
            system = HabitsDirFinder(AndroidDirFinder(this)),
            taskRunner = appComponent.taskRunner
        )

        // No options menu: the actions live in TopAppBarMMD, because MuditaOS
        // has no overflow anywhere for them to hide behind.
        view.setTopBar(
            actions = ShowHabitActions(
                onBack = { finish() },
                onEdit = { menuPresenter.onEditHabit() },
                onArchive = { menuPresenter.onArchiveHabits() },
                onUnarchive = { menuPresenter.onUnarchiveHabits() },
                onDelete = { menuPresenter.onDeleteHabit() },
            ),
        )
        this.menuPresenter = menuPresenter

        view.setListener(presenter)
        view.applyRootViewInsets()
        setContentView(view)
        holdFirstDrawUntilReady()
    }

    /**
     * Refuses to draw until the habit's state has arrived, so the screen
     * appears once, complete.
     *
     * The same device the habits list uses for its startup screen, and for the
     * same reason: holding a frame back costs a moment of the previous screen
     * staying up, where letting it through costs a visible repaint of
     * everything on this one.
     *
     * The deadline is the safety net. [Screen.refresh] computes off the main
     * thread and could in principle be slow or fail, and a screen that never
     * draws is worse than one that draws early.
     */
    private fun holdFirstDrawUntilReady() {
        val content = findViewById<View>(android.R.id.content)
        val deadline = android.os.SystemClock.uptimeMillis() + FIRST_DRAW_HOLD_MS
        content.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val laidOut = content.width > 0 && content.height > 0
                    val ready = laidOut && hasState
                    val expired = android.os.SystemClock.uptimeMillis() >= deadline
                    if (!ready && !expired) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        commandRunner.addListener(this)
        // What a background sync brings in is drawn at once.
        AutoSync.onChanged = { screen.refresh() }
        screen.refresh()
    }

    override fun onPause() {
        AutoSync.onChanged = null
        dismissCurrentDialog()
        commandRunner.removeListener(this)
        super.onPause()
    }

    override fun onCommandFinished(command: Command) {
        screen.refresh()
    }

    inner class Screen : ShowHabitMenuPresenter.Screen, ShowHabitPresenter.Screen {
        override fun updateWidgets() {
            // Nothing to update: the widget providers are unregistered from
            // the manifest. The core cards still call this on a span change,
            // so the override stays and does nothing.
        }

        override fun refresh() {
            scope.launch {
                // Built off the main thread. `buildState` is an ordinary
                // function with no dispatcher of its own, and it runs every
                // card's presenter -- score, streak, history, frequency, bar --
                // across the habit's whole history. Launched on
                // `Dispatchers.Main` it stalled the thread that was supposed to
                // be drawing, which is both a freeze and the gap the placeholder
                // frame showed through.
                val state = withContext(Dispatchers.Default) {
                    ShowHabitPresenter.buildState(
                        habit = habit,
                        preferences = preferences,
                        theme = themeSwitcher.currentTheme
                    )
                }
                view.setState(state)
                // Archive and unarchive are mutually exclusive and swap as
                // soon as the command lands, so the header is told on every
                // refresh rather than once at construction.
                view.setMenuState(
                    canArchive = menuPresenter.canArchive(),
                    canUnarchive = menuPresenter.canUnarchive(),
                )
                // Set after both, so the first draw waits for a screen that is
                // complete rather than one that is merely no longer empty.
                // They land in the same snapshot, so this is one recomposition.
                hasState = true
            }
        }

        override fun showFeedback() {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }

        override fun showNumberPopup(
            value: Double,
            notes: String,
            callback: ListHabitsBehavior.NumberPickerCallback
        ) {
            val dialog = NumberDialog()
            dialog.arguments = Bundle().apply {
                putDouble("value", value)
                putString("notes", notes)
            }
            dialog.onToggle = { v, n -> callback.onNumberPicked(v, n) }
            dialog.dismissCurrentAndShow(supportFragmentManager, "numberDialog")
        }

        override fun showCheckmarkPopup(
            selectedValue: Int,
            notes: String,
            color: PaletteColor,
            callback: ListHabitsBehavior.CheckMarkDialogCallback
        ) {
            val theme = view.currentTheme()
            val dialog = CheckmarkDialog()
            dialog.arguments = Bundle().apply {
                putInt("color", theme.color(color).toInt())
                putInt("value", selectedValue)
                putString("notes", notes)
            }
            dialog.onToggle = { v, n -> callback.onNotesSaved(v, n) }
            dialog.dismissCurrentAndShow(supportFragmentManager, "checkmarkDialog")
        }

        override fun showEditHabitScreen(habit: Habit) {
            startActivity(IntentFactory().startEditActivity(this@ShowHabitActivity, habit))
        }

        override fun showMessage(m: ShowHabitMenuPresenter.Message?) {
            when (m) {
                ShowHabitMenuPresenter.Message.COULD_NOT_EXPORT -> {
                    showMessage(resources.getString(R.string.could_not_export))
                }

                ShowHabitMenuPresenter.Message.HABIT_ARCHIVED -> {
                    showMessage(
                        resources.getQuantityString(
                            R.plurals.toast_habits_archived,
                            1
                        )
                    )
                }

                ShowHabitMenuPresenter.Message.HABIT_UNARCHIVED -> {
                    showMessage(
                        resources.getQuantityString(
                            R.plurals.toast_habits_unarchived,
                            1
                        )
                    )
                }

                else -> {}
            }
        }

        override fun showDeleteConfirmationScreen(callback: OnConfirmedCallback) {
            ConfirmDeleteDialog.show(supportFragmentManager, callback, 1)
        }

        override fun close() {
            this@ShowHabitActivity.finish()
        }
    }
}

/**
 * How long the previous screen may stay up before this one draws anyway.
 *
 * A safety net, not a budget: the hold releases as soon as the habit's state
 * has arrived, which is the common case by a wide margin.
 */
private const val FIRST_DRAW_HOLD_MS = 3000L
