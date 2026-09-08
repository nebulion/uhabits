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
package org.isoron.uhabits.desktop

import java.io.File
import java.util.Properties

/**
 * Where the desktop client keeps its database, its settings, and its private
 * copy of what the sync folder last said.
 *
 * XDG on everything that is not Windows, because this is expected to spend most
 * of its life on Linux; `%APPDATA%` on Windows, which is the equivalent
 * convention there. Both are overridable from the command line, so nothing here
 * is load-bearing if the layout does not suit.
 */
object DesktopEnv {

    private val isWindows =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    private val home get() = File(System.getProperty("user.home"))

    private fun xdg(variable: String, fallback: String): File {
        val value = System.getenv(variable)
        return if (!value.isNullOrBlank()) File(value) else File(home, fallback)
    }

    private fun appData(): File =
        System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, "AppData/Roaming")

    val dataDir: File
        get() = if (isWindows) File(appData(), "uhabits") else File(xdg("XDG_DATA_HOME", ".local/share"), "uhabits")

    val configDir: File
        get() = if (isWindows) File(appData(), "uhabits") else File(xdg("XDG_CONFIG_HOME", ".config"), "uhabits")

    val defaultDatabase: File get() = File(dataDir, "uhabits.db")

    val configFile: File get() = File(configDir, "config.properties")
}

class Config(private val file: File = DesktopEnv.configFile) {

    private val props = Properties().apply {
        if (file.isFile) file.inputStream().use { load(it) }
    }

    operator fun get(key: String): String? = props.getProperty(key)?.takeIf { it.isNotBlank() }

    operator fun set(key: String, value: String) {
        props.setProperty(key, value)
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, "Loop Habit Tracker - desktop client") }
    }

    var syncFolder: File?
        get() = this["syncFolder"]?.let(::File)
        set(value) {
            if (value != null) this["syncFolder"] = value.absolutePath
        }
}
