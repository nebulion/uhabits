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

package org.isoron.uhabits.inject

import dagger.Component
import org.isoron.uhabits.activities.common.dialogs.ColorPickerDialogFactory
import org.isoron.uhabits.activities.habits.list.ListHabitsMenu
import org.isoron.uhabits.activities.habits.list.ListHabitsModule
import org.isoron.uhabits.activities.habits.list.ListHabitsScreen
import org.isoron.uhabits.activities.habits.list.HabitCardListModel
import org.isoron.uhabits.core.ui.ThemeSwitcher
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsSelectionMenuBehavior

@ActivityScope
@Component(
    modules = [ActivityContextModule::class, HabitsActivityModule::class, ListHabitsModule::class, HabitModule::class],
    dependencies = [HabitsApplicationComponent::class]
)
interface HabitsActivityComponent {
    val colorPickerDialogFactory: ColorPickerDialogFactory
    val habitCardListModel: HabitCardListModel
    val listHabitsBehavior: ListHabitsBehavior
    val listHabitsMenu: ListHabitsMenu
    val listHabitsScreen: ListHabitsScreen
    val listHabitsSelectionMenuBehavior: ListHabitsSelectionMenuBehavior
    val themeSwitcher: ThemeSwitcher
}
