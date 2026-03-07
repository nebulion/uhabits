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

package org.isoron.uhabits.sync

import android.util.Log
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.Timestamp
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.utils.DateUtils
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal bidirectional sync between Android and the self-hosted Loop server.
 *
 * Strategy:
 *  1. Push all local habits + 2 years of entries to /api/sync/push (upsert by UUID)
 *  2. Pull server state from /api/sync/pull and update local habits/entries
 *
 * All network errors are caught and logged — sync never blocks normal app usage.
 * Sync only runs if pref_sync_base_url is configured (non-empty, non-default).
 */
class SyncManager(
    private val habitList: HabitList,
    private val modelFactory: ModelFactory,
    private val preferences: Preferences,
) {

    fun syncIfEnabled() {
        val serverUrl = preferences.syncServerUrl.trimEnd('/')
        if (serverUrl.isBlank()) return

        Thread {
            try {
                pushChanges(serverUrl)
                pullChanges(serverUrl)
                Log.i(TAG, "Sync completed successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed (will retry next launch): ${e.message}")
            }
        }.apply {
            isDaemon = true
            name = "LoopSyncThread"
        }.start()
    }

    private fun pushChanges(serverUrl: String) {
        val habitsArray = JSONArray()
        val entriesMap = JSONObject()

        val today = DateUtils.getToday()
        val from = today.minus(TWO_YEARS_DAYS)

        for (habit in habitList) {
            habitsArray.put(habitToJson(habit))

            val entries = JSONArray()
            val computedEntries = habit.computedEntries
            var ts = from
            while (!ts.isNewerThan(today)) {
                val entry = computedEntries.get(ts)
                if (entry.value != Entry.UNKNOWN) {
                    entries.put(entryToJson(ts, entry))
                }
                ts = ts.plus(1)
            }
            if (entries.length() > 0) {
                entriesMap.put(habit.uuid ?: continue, entries)
            }
        }

        val body = JSONObject().apply {
            put("habits", habitsArray)
            put("entries", entriesMap)
        }

        doPost("$serverUrl/api/sync/push", body.toString())
        Log.d(TAG, "Push: ${habitsArray.length()} habits")
    }

    private fun pullChanges(serverUrl: String) {
        val response = doGet("$serverUrl/api/sync/pull")
        val json = JSONObject(response)
        val serverHabits = json.getJSONArray("habits")
        val serverEntries = json.optJSONObject("entries") ?: JSONObject()

        for (i in 0 until serverHabits.length()) {
            val hJson = serverHabits.getJSONObject(i)
            val uuid = hJson.getString("uuid")

            val existing = habitList.getByUUID(uuid)
            if (existing != null) {
                applyJsonToHabit(hJson, existing)
                habitList.update(existing)
                existing.recompute()
            } else {
                val newHabit = modelFactory.buildHabit()
                newHabit.uuid = uuid
                applyJsonToHabit(hJson, newHabit)
                habitList.add(newHabit)
                newHabit.recompute()
            }
        }

        for (uuid in serverEntries.keys()) {
            val habit = habitList.getByUUID(uuid) ?: continue
            val entries = serverEntries.getJSONArray(uuid)
            for (i in 0 until entries.length()) {
                val eJson = entries.getJSONObject(i)
                val ts = Timestamp(eJson.getLong("timestamp"))
                val value = eJson.getInt("value")
                val notes = eJson.optString("notes", "")
                habit.originalEntries.add(Entry(ts, value, notes))
            }
            habit.recompute()
        }

        Log.d(TAG, "Pull: ${serverHabits.length()} habits")
    }

    private fun habitToJson(habit: org.isoron.uhabits.core.models.Habit): JSONObject {
        return JSONObject().apply {
            put("uuid", habit.uuid ?: "")
            put("name", habit.name)
            put("description", habit.description)
            put("question", habit.question)
            put("type", habit.type.value)
            put("color", habit.color.paletteIndex)
            put("freqNum", habit.frequency.numerator)
            put("freqDen", habit.frequency.denominator)
            put("targetType", habit.targetType)
            put("targetValue", habit.targetValue)
            put("unit", habit.unit)
            put("isArchived", habit.isArchived)
            put("position", habit.position)
            put("priority", habit.priority)
            put("updatedAt", System.currentTimeMillis())
            habit.reminder?.let { r ->
                put("reminderHour", r.hour)
                put("reminderMin", r.minute)
                put("reminderDays", r.days.toInteger())
            }
        }
    }

    private fun applyJsonToHabit(
        json: JSONObject,
        habit: org.isoron.uhabits.core.models.Habit,
    ) {
        habit.name = json.optString("name", habit.name)
        habit.description = json.optString("description", habit.description)
        habit.question = json.optString("question", habit.question)
        habit.isArchived = json.optBoolean("isArchived", habit.isArchived)
        habit.position = json.optInt("position", habit.position)
        habit.priority = json.optInt("priority", habit.priority)
        habit.targetValue = json.optDouble("targetValue", habit.targetValue)
        habit.unit = json.optString("unit", habit.unit)
    }

    private fun entryToJson(ts: Timestamp, entry: Entry): JSONObject {
        return JSONObject().apply {
            put("timestamp", ts.unixTime)
            put("value", entry.value)
            put("notes", entry.notes)
        }
    }

    private fun doPost(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val apiKey = preferences.syncApiKey
            if (apiKey.isNotBlank()) conn.setRequestProperty("X-API-Key", apiKey)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code from $url")
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun doGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            val apiKey = preferences.syncApiKey
            if (apiKey.isNotBlank()) conn.setRequestProperty("X-API-Key", apiKey)
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code from $url")
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "LoopSyncManager"
        private const val TWO_YEARS_DAYS = 730
    }
}
