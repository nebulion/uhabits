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
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.isoron.uhabits.server.AppModule
import java.io.File

/**
 * Accepts the same file formats the Android app exports:
 *   - Loop Habit Tracker backup (.db)
 *   - CSV/ZIP export
 *   - HabitBull CSV
 *
 * Uses the existing GenericImporter from uhabits-core, so any format the
 * Android app can export, the server can import.
 */
fun Route.importRoutes(appModule: AppModule) {
    route("/api/import") {
        post {
            val multipart = call.receiveMultipart()
            var importedCount = 0
            var errorMessage: String? = null

            multipart.forEachPart { part ->
                if (part is PartData.FileItem) {
                    val tmpFile = File.createTempFile("import_", "_${part.originalFileName ?: "upload"}")
                    try {
                        part.streamProvider().use { input ->
                            tmpFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        val importer = appModule.buildImporter()
                        if (importer.canHandle(tmpFile)) {
                            importer.importHabitsFromFile(tmpFile)
                            for (h in appModule.habitList) h.recompute()
                            importedCount++
                        } else {
                            errorMessage = "Unrecognized file format: ${part.originalFileName}"
                        }
                    } catch (e: Exception) {
                        errorMessage = "Import failed: ${e.message}"
                    } finally {
                        tmpFile.delete()
                    }
                }
                part.dispose()
            }

            when {
                importedCount > 0 -> call.respond(HttpStatusCode.OK, mapOf("imported" to importedCount))
                errorMessage != null -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to errorMessage))
                else -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file uploaded"))
            }
        }
    }
}
