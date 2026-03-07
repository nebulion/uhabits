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
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Timestamp

@Serializable
data class EntryDto(
    val timestamp: Long,
    val value: Int,
    val notes: String,
)

fun Entry.toDto() = EntryDto(timestamp.unixTime, value, notes)

fun EntryDto.toEntry() = Entry(Timestamp(timestamp), value, notes)
