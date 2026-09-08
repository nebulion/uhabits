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

import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.database.Database
import org.isoron.uhabits.core.database.JdbcDatabase
import org.isoron.uhabits.core.database.MigrationHelper
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import java.io.File
import java.sql.DriverManager

/**
 * The desktop client's own habit database.
 *
 * It is an ordinary Loop database, byte-for-byte the same format the phone
 * uses, at the same schema version -- so a phone backup can simply be copied
 * over it, and this file can equally be imported back into the app. Nothing
 * here is desktop-specific except where the file lives.
 */
class HabitStore(val file: File) : AutoCloseable {

    val database: Database
    val modelFactory: SQLModelFactory
    val habitList: HabitList

    /**
     * This database's own three-way merge base -- its private record of what
     * the shared folder said last time it looked.
     *
     * Derived from the database path rather than fixed, because the base is
     * only meaningful next to the database it was built from. Pointing --db at
     * a second database and reusing one base would make every habit in it look
     * deleted.
     */
    val baseDir: File
        get() = File(file.absoluteFile.parentFile, file.nameWithoutExtension + ".sync-base")

    init {
        file.parentFile?.mkdirs()
        val fresh = !file.isFile || file.length() == 0L
        database = JdbcDatabase(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"))

        // Migrations start at 09.sql, so a brand new file has to be told it is
        // already at 8 before the helper has anything to apply. This is the
        // same bootstrap the core test harness uses.
        if (fresh || database.version == 0) database.execute("pragma user_version=8;")
        if (database.version < DATABASE_VERSION) {
            MigrationHelper(database).migrateTo(DATABASE_VERSION)
            // The helper runs the migrations but never records how far it got,
            // and none of the .sql files touch user_version either. On Android
            // that is invisible, because SQLiteOpenHelper stamps the version
            // itself; this client is the first thing to migrate a file that
            // outlives the process, so without this the same migrations run
            // again on the next start and fail on "table Habits already exists".
            database.execute("pragma user_version=$DATABASE_VERSION;")
        }

        modelFactory = SQLModelFactory(database)
        habitList = modelFactory.buildHabitList()
    }

    /**
     * Scores and streaks are derived, not stored, and nothing computes them at
     * load time -- on Android that happens as each card is shown. A terminal
     * draws everything at once, so they are computed up front.
     */
    fun recomputeAll() {
        for (habit in habitList) habit.recompute()
    }

    fun findHabit(query: String): Habit? {
        val habits = habitList.toList()
        return habits.firstOrNull { it.name.equals(query, ignoreCase = true) }
            ?: habits.firstOrNull { it.name.contains(query, ignoreCase = true) }
            ?: habits.firstOrNull { it.uuid == query }
    }

    override fun close() {
        database.close()
    }
}
