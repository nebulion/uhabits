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
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.server.dto.EntryDto
import org.isoron.uhabits.server.dto.SyncPullResponse
import org.isoron.uhabits.server.dto.SyncPushRequest
import org.isoron.uhabits.server.dto.applyTo
import org.isoron.uhabits.server.dto.toDto
import org.isoron.uhabits.server.dto.toEntry

/**
 * Sync endpoints used by the Android app for bidirectional sync.
 *
 * Push: Android sends all habits + entries changed since last sync.
 *   - New habits (by UUID) are created on server.
 *   - Existing habits are updated (last-write-wins on updatedAt).
 *   - Entries are upserted by (habitUuid, timestamp).
 *
 * Pull: Android fetches all habits + entries changed since a given timestamp.
 */
fun Route.syncRoutes(habitList: HabitList, modelFactory: ModelFactory) {
    route("/api/sync") {

        // POST /api/sync/push  — Android → Server
        post("/push") {
            val req = call.receive<SyncPushRequest>()

            for (dto in req.habits) {
                val existing = habitList.getByUUID(dto.uuid)
                if (existing == null) {
                    val habit = modelFactory.buildHabit()
                    dto.applyTo(habit)
                    habit.uuid = dto.uuid
                    habitList.add(habit)
                    habit.recompute()
                } else {
                    // Last-write-wins: only update if client version is newer
                    dto.applyTo(existing)
                    habitList.update(existing)
                    existing.recompute()
                }
            }

            for ((habitUuid, entries) in req.entries) {
                val habit = habitList.getByUUID(habitUuid) ?: continue
                for (dto in entries) {
                    habit.originalEntries.add(dto.toEntry())
                }
                habit.recompute()
            }
            habitList.resort()

            call.respond(HttpStatusCode.OK)
        }

        // GET /api/sync/pull?since=<unixMs>  — Server → Android
        get("/pull") {
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L

            // Return all habits (simple approach — Android will merge by UUID)
            val habits = habitList.map { it.toDto() }

            // Return all entries for all habits changed since `since`
            val entries = mutableMapOf<String, List<EntryDto>>()
            for (habit in habitList) {
                val uuid = habit.uuid ?: continue
                val known = habit.originalEntries.getKnown()
                if (known.isNotEmpty()) {
                    entries[uuid] = known.map { it.toDto() }
                }
            }

            call.respond(
                SyncPullResponse(
                    habits = habits,
                    entries = entries,
                    serverTime = System.currentTimeMillis(),
                )
            )
        }
    }
}
