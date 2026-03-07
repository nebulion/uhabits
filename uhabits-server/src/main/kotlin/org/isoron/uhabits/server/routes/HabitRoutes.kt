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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.server.dto.HabitDto
import org.isoron.uhabits.server.dto.applyTo
import org.isoron.uhabits.server.dto.toDto

fun Route.habitRoutes(habitList: HabitList, modelFactory: ModelFactory) {
    route("/api/habits") {

        get {
            call.respond(habitList.map { it.toDto() })
        }

        post {
            val dto = call.receive<HabitDto>()
            val habit = modelFactory.buildHabit()
            dto.applyTo(habit)
            habit.uuid = if (dto.uuid.isNotBlank()) dto.uuid else java.util.UUID.randomUUID().toString()
            habitList.add(habit)
            habit.recompute()
            call.respond(HttpStatusCode.Created, habit.toDto())
        }

        route("/{uuid}") {

            get {
                val uuid = call.parameters["uuid"]
                val habit = habitList.getByUUID(uuid)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(habit.toDto())
            }

            put {
                val uuid = call.parameters["uuid"]
                val habit = habitList.getByUUID(uuid)
                    ?: return@put call.respond(HttpStatusCode.NotFound)
                val dto = call.receive<HabitDto>()
                dto.applyTo(habit)
                habitList.update(habit)
                habit.recompute()
                call.respond(habit.toDto())
            }

            delete {
                val uuid = call.parameters["uuid"]
                val habit = habitList.getByUUID(uuid)
                    ?: return@delete call.respond(HttpStatusCode.NotFound)
                habitList.remove(habit)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
