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
package org.isoron.uhabits.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.isoron.uhabits.core.models.sqlite.records.HabitRecord
import org.isoron.uhabits.core.utils.DateFormats
import java.util.Date

/**
 * The on-disk representation of a habit and its entries, as written into the
 * shared sync folder.
 *
 * This is deliberately *not* the database schema. Two things are left out on
 * purpose: `id`, which is a local database row number and means nothing on
 * another device, and `highlight`, which nothing has read since upstream
 * dropped it. Everything else round-trips through [HabitRecord], so the
 * field-by-field mapping between a habit and its stored form continues to live
 * in exactly one place.
 *
 * Dates are written as `yyyy-MM-dd` rather than as the underlying millisecond
 * value so the files can be read, diffed and grepped by a person. [Timestamp]
 * is UTC-midnight based, and [DateFormats.getCSVDateFormat] formats in UTC, so
 * the conversion is exact in both directions.
 */
@Serializable
data class SyncFrequency(
    val numerator: Int,
    val denominator: Int
)

@Serializable
data class SyncTarget(
    val type: Int,
    val value: Double,
    val unit: String
)

@Serializable
data class SyncReminder(
    val hour: Int,
    val minute: Int,
    val days: Int
)

@Serializable
data class SyncHabit(
    val uuid: String,
    val name: String,
    val description: String = "",
    val question: String = "",
    val frequency: SyncFrequency = SyncFrequency(1, 1),
    val color: Int = 0,
    val position: Int = 0,
    val archived: Boolean = false,
    val type: Int = 0,
    val target: SyncTarget = SyncTarget(0, 0.0, ""),
    val reminder: SyncReminder? = null
)

/**
 * One check-mark.
 *
 * [value] is the raw stored integer, not a friendlier string. The constants are
 * in `Entry`: -1 unknown, 0 no, 1 yes-auto, 2 yes-manual, 3 skip; a numerical
 * habit stores its value multiplied by 1000. Encoding it as "yes"/"no" would
 * read better but would have to be decoded differently depending on the habit's
 * type, which puts a branch into the one piece of this feature that must never
 * misread a file. The raw integer cannot be misinterpreted.
 */
@Serializable
data class SyncEntry(
    val date: String,
    val value: Int,
    val notes: String = ""
)

@Serializable
data class SyncEntries(
    val uuid: String,
    val entries: List<SyncEntry> = emptyList()
)

/**
 * Note [encodeDefaults] is false: an empty `notes` or `description` is left out
 * of the file entirely rather than written as `""`. Across five thousand
 * entries that is most of the file. It round-trips because the defaults on the
 * data classes above are the same values.
 */
val SYNC_JSON = Json {
    prettyPrint = true
    encodeDefaults = false
    ignoreUnknownKeys = true
}

fun formatSyncDate(unixTime: Long): String =
    DateFormats.getCSVDateFormat().format(Date(unixTime))

fun parseSyncDate(date: String): Long =
    DateFormats.getCSVDateFormat().parse(date).time

fun HabitRecord.toSyncHabit(): SyncHabit = SyncHabit(
    uuid = uuid!!,
    name = name ?: "",
    description = description ?: "",
    question = question ?: "",
    frequency = SyncFrequency(freqNum ?: 1, freqDen ?: 1),
    color = color ?: 0,
    position = position ?: 0,
    archived = (archived ?: 0) != 0,
    type = type ?: 0,
    target = SyncTarget(targetType ?: 0, targetValue ?: 0.0, unit ?: ""),
    reminder = if (reminderHour != null && reminderMin != null) {
        SyncReminder(reminderHour!!, reminderMin!!, reminderDays ?: 0)
    } else {
        null
    }
)

fun SyncHabit.toHabitRecord(): HabitRecord = HabitRecord().also { r ->
    r.uuid = uuid
    r.name = name
    r.description = description
    r.question = question
    r.freqNum = frequency.numerator
    r.freqDen = frequency.denominator
    r.color = color
    r.position = position
    r.archived = if (archived) 1 else 0
    r.type = type
    r.targetType = target.type
    r.targetValue = target.value
    r.unit = target.unit
    r.highlight = 0
    r.reminderHour = reminder?.hour
    r.reminderMin = reminder?.minute
    r.reminderDays = reminder?.days ?: 0
}

fun encodeHabit(habit: SyncHabit): String = SYNC_JSON.encodeToString(habit)
fun decodeHabit(text: String): SyncHabit = SYNC_JSON.decodeFromString(text)
fun encodeEntries(entries: SyncEntries): String = SYNC_JSON.encodeToString(entries)
fun decodeEntries(text: String): SyncEntries = SYNC_JSON.decodeFromString(text)
