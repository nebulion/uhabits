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

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.isoron.uhabits.core.DATABASE_VERSION
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class HabitStoreTest {

    private fun tempDb(): File =
        File.createTempFile("uhabits-test", ".db").also {
            it.delete()
            it.deleteOnExit()
        }

    @Test
    fun `a new database is created at the current schema version`() {
        val file = tempDb()
        HabitStore(file).use { store ->
            assertThat(store.database.version, equalTo(DATABASE_VERSION))
            assertThat(store.habitList.size(), equalTo(0))
        }
        assertTrue(file.isFile && file.length() > 0)
    }

    /**
     * The bootstrap has to record the version it migrated to.
     *
     * `MigrationHelper` runs the .sql files but never writes `user_version`,
     * and none of the migrations write it either -- on Android that is hidden
     * because SQLiteOpenHelper stamps it. Opening the same file twice is the
     * cheapest way to catch it: without the stamp the second open replays every
     * migration and dies on "table Habits already exists".
     */
    @Test
    fun `an existing database can be opened again`() {
        val file = tempDb()
        HabitStore(file).use { it.habitList.size() }
        HabitStore(file).use { store ->
            assertThat(store.database.version, equalTo(DATABASE_VERSION))
        }
    }

    @Test
    fun `the merge base sits beside the database, not in a shared place`() {
        val a = HabitStore(tempDb())
        val b = HabitStore(tempDb())
        a.use {
            b.use {
                assertTrue(a.baseDir != b.baseDir, "two databases must not share one merge base")
            }
        }
    }
}
