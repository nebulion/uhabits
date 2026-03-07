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

package org.isoron.uhabits.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.isoron.uhabits.server.routes.entryRoutes
import org.isoron.uhabits.server.routes.habitRoutes
import org.isoron.uhabits.server.routes.importRoutes
import org.isoron.uhabits.server.routes.syncRoutes
import org.slf4j.event.Level

fun main(args: Array<String>) {
    fun flagValue(flag: String) = args.indexOf(flag).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val port = flagValue("--port")?.toIntOrNull() ?: 8080
    val dbPath = flagValue("--db") ?: "uhabits.db"
    val apiKey = flagValue("--api-key") ?: ""

    println("Starting Loop Habit Tracker Server")
    println("  Port   : $port")
    println("  DB     : $dbPath")
    println("  API key: ${if (apiKey.isEmpty()) "(none — open access)" else "configured"}")

    val appModule = AppModule(dbPath)

    embeddedServer(Netty, port = port) {
        configureServer(appModule, apiKey)
    }.start(wait = true)
}

fun Application.configureServer(appModule: AppModule, apiKey: String) {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = false
                isLenient = true
                ignoreUnknownKeys = true
            }
        )
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            cause.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "Unknown error")))
        }
    }

    // API key guard — applied before routing so all /api/* paths are protected
    if (apiKey.isNotBlank()) {
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (path.startsWith("/api")) {
                val key = call.request.headers["X-API-Key"]
                if (key != apiKey) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid API key"))
                    finish()
                }
            }
        }
    }

    routing {
        // Serve web UI static files
        get("/") { call.respondResource("web/index.html", "text/html") }
        get("/app.js") { call.respondResource("web/app.js", "application/javascript") }
        get("/style.css") { call.respondResource("web/style.css", "text/css") }

        // REST API
        habitRoutes(appModule.habitList, appModule.modelFactory)
        entryRoutes(appModule.habitList)
        syncRoutes(appModule.habitList, appModule.modelFactory)
        importRoutes(appModule)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondResource(
    resourcePath: String,
    contentTypeStr: String
) {
    val bytes = object {}.javaClass.classLoader.getResourceAsStream(resourcePath)?.readBytes()
    if (bytes != null) {
        respondBytes(bytes, ContentType.parse(contentTypeStr))
    } else {
        respond(HttpStatusCode.NotFound)
    }
}
