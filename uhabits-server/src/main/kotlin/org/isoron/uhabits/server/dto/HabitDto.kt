/*
 * Copyright (C) 2016-2021 Álinson Santos Xavier <git@axavier.org>
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

package org.isoron.uhabits.server.dto

import kotlinx.serialization.Serializable
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList

@Serializable
data class HabitDto(
    val uuid: String,
    val name: String,
    val description: String,
    val question: String,
    val type: Int,
    val color: Int,
    val freqNum: Int,
    val freqDen: Int,
    val targetType: Int,
    val targetValue: Double,
    val unit: String,
    val isArchived: Boolean,
    val position: Int,
    val priority: Int,
    val reminderHour: Int?,
    val reminderMin: Int?,
    val reminderDays: Int?,
    val updatedAt: Long,
)

fun Habit.toDto(updatedAt: Long = System.currentTimeMillis()): HabitDto {
    val (freqNum, freqDen) = frequency
    return HabitDto(
        uuid = uuid ?: "",
        name = name,
        description = description,
        question = question,
        type = type.value,
        color = color.paletteIndex,
        freqNum = freqNum,
        freqDen = freqDen,
        targetType = targetType.value,
        targetValue = targetValue,
        unit = unit,
        isArchived = isArchived,
        position = position,
        priority = priority,
        reminderHour = reminder?.hour,
        reminderMin = reminder?.minute,
        reminderDays = reminder?.days?.toInteger(),
        updatedAt = updatedAt,
    )
}

fun HabitDto.applyTo(habit: Habit) {
    habit.name = name
    habit.description = description
    habit.question = question
    habit.type = HabitType.fromInt(type)
    habit.color = PaletteColor(color)
    habit.frequency = Frequency(freqNum, freqDen)
    habit.targetType = NumericalHabitType.fromInt(targetType)
    habit.targetValue = targetValue
    habit.unit = unit
    habit.isArchived = isArchived
    habit.position = position
    habit.priority = priority
    habit.reminder = if (reminderHour != null && reminderMin != null) {
        Reminder(reminderHour, reminderMin, WeekdayList(reminderDays ?: 0))
    } else {
        null
    }
}
