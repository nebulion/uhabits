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

import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.database.DatabaseOpener
import org.isoron.uhabits.core.database.JdbcDatabase
import org.isoron.uhabits.core.database.MigrationHelper
import org.isoron.uhabits.core.io.GenericImporter
import org.isoron.uhabits.core.io.HabitBullCSVImporter
import org.isoron.uhabits.core.io.LoopDBImporter
import org.isoron.uhabits.core.io.RewireDBImporter
import org.isoron.uhabits.core.io.StandardLogging
import org.isoron.uhabits.core.io.TickmateDBImporter
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import org.isoron.uhabits.core.tasks.SingleThreadTaskRunner
import java.io.File
import java.sql.DriverManager

/**
 * Manual dependency injection for the server — mirrors Android's HabitsModule
 * but without any Android dependencies.
 *
 * Database initialization mirrors BaseUnitTest.buildMemoryDatabase() and
 * HabitsDatabaseOpener: set user_version baseline to 8, then run migrations.
 */
class AppModule(dbPath: String) {

    val database: JdbcDatabase
    val modelFactory: ModelFactory
    val habitList: HabitList
    val commandRunner: CommandRunner
    val databaseOpener: DatabaseOpener

    init {
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        database = JdbcDatabase(conn)

        if (database.version < 8) {
            // Fresh database: establish baseline version before running migrations
            database.execute("pragma user_version = 8")
        }

        MigrationHelper(database).migrateTo(DATABASE_VERSION)

        // Persist final version so subsequent startups skip already-run migrations
        database.execute("pragma user_version = $DATABASE_VERSION")

        modelFactory = SQLModelFactory(database)
        val list = modelFactory.buildHabitList() as SQLiteHabitList
        list.repair()
        for (h in list) h.recompute()
        habitList = list

        commandRunner = CommandRunner(SingleThreadTaskRunner())

        databaseOpener = object : DatabaseOpener {
            override fun open(file: File) =
                JdbcDatabase(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"))
        }
    }

    fun buildImporter() = GenericImporter(
        LoopDBImporter(habitList, modelFactory, databaseOpener, commandRunner, StandardLogging()),
        RewireDBImporter(habitList, modelFactory, databaseOpener),
        TickmateDBImporter(habitList, modelFactory, databaseOpener),
        HabitBullCSVImporter(habitList, modelFactory, StandardLogging()),
    )
}
