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

package org.isoron.uhabits.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.server.dto.EntryDto
import org.isoron.uhabits.server.dto.toDto
import org.isoron.uhabits.server.dto.toEntry

fun Route.entryRoutes(habitList: HabitList) {
    route("/api/habits/{uuid}/entries") {

        get {
            val uuid = call.parameters["uuid"]
            val habit = habitList.getByUUID(uuid)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val from = call.request.queryParameters["from"]?.toLongOrNull()
            val to = call.request.queryParameters["to"]?.toLongOrNull()
            val entries = if (from != null && to != null) {
                habit.originalEntries.getByInterval(Timestamp(from), Timestamp(to))
            } else {
                habit.originalEntries.getKnown()
            }
            call.respond(entries.map { it.toDto() })
        }

        post {
            val uuid = call.parameters["uuid"]
            val habit = habitList.getByUUID(uuid)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val dto = call.receive<EntryDto>()
            val entry = dto.toEntry()
            habit.originalEntries.add(entry)
            habit.recompute()
            habitList.resort()
            call.respond(HttpStatusCode.OK, entry.toDto())
        }
    }
}
