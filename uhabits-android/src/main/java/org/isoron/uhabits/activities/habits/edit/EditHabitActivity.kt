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

package org.isoron.uhabits.activities.habits.edit

import android.annotation.SuppressLint
import android.content.res.Resources
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.DialogFragment
import org.isoron.platform.gui.toInt
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.utils.applyRootViewInsets
import org.isoron.uhabits.utils.applyToolbarInsets
import org.isoron.uhabits.utils.dismissCurrentAndShow
import org.isoron.uhabits.utils.formatTime
import org.isoron.uhabits.utils.toFormattedString

fun formatFrequency(freqNum: Int, freqDen: Int, resources: Resources) = when {
    freqNum == 1 && (freqDen == 30 || freqDen == 31) -> resources.getString(R.string.every_month)
    freqDen == 30 || freqDen == 31 -> resources.getString(R.string.x_times_per_month, freqNum)
    freqNum == 1 && freqDen == 1 -> resources.getString(R.string.every_day)
    freqNum == 1 && freqDen == 7 -> resources.getString(R.string.every_week)
    freqNum == 1 && freqDen > 1 -> resources.getString(R.string.every_x_days, freqDen)
    freqDen == 7 -> resources.getString(R.string.x_times_per_week, freqNum)
    else -> resources.getString(R.string.x_times_per_y_days, freqNum, freqDen)
}

class EditHabitActivity : AppCompatActivity() {

    private lateinit var themeSwitcher: AndroidThemeSwitcher
    private lateinit var commandRunner: CommandRunner

    var habitId = -1L
    lateinit var habitType: HabitType
    var unit = ""
    var color = PaletteColor(11)
    var androidColor = 0
    var freqNum = 1
    var freqDen = 1
    var reminderHour = -1
    var reminderMin = -1
    var reminderDays: WeekdayList = WeekdayList.EVERY_DAY
    var targetType = NumericalHabitType.AT_LEAST

    // The form's text now lives here rather than in EditText widgets.
    private var nameText by mutableStateOf("")
    private var questionText by mutableStateOf("")
    private var notesText by mutableStateOf("")
    private var unitText by mutableStateOf("")
    private var targetText by mutableStateOf("")
    private var nameError by mutableStateOf<String?>(null)
    private var targetError by mutableStateOf<String?>(null)
    private var revision by mutableStateOf(0)

    // A pending one-of-N choice, rendered by the screen as a sheet.
    private var choiceOptions by mutableStateOf<List<String>?>(null)
    private var choiceCallback: (Int) -> Unit = {}

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        val component = (application as HabitsApplication).component
        themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()

        if (intent.hasExtra("habitId")) {
            habitId = intent.getLongExtra("habitId", -1)
            val habit = component.habitList.getById(habitId)!!
            habitType = habit.type
            color = habit.color
            freqNum = habit.frequency.numerator
            freqDen = habit.frequency.denominator
            targetType = habit.targetType
            habit.reminder?.let {
                reminderHour = it.hour
                reminderMin = it.minute
                reminderDays = it.days
            }
            nameText = habit.name
            questionText = habit.question
            notesText = habit.description
            unitText = habit.unit
            targetText = habit.targetValue.toString()
        } else {
            habitType = HabitType.fromInt(intent.getIntExtra("habitType", HabitType.YES_NO.value))
        }

        // Restoring runs after the two branches above on purpose: on a
        // recreation the bundle is the authority, and what it carries has to
        // win over both the habit's stored values and the defaults.
        if (state != null) {
            habitId = state.getLong("habitId")
            habitType = HabitType.fromInt(state.getInt("habitType"))
            color = PaletteColor(state.getInt("paletteColor"))
            freqNum = state.getInt("freqNum")
            freqDen = state.getInt("freqDen")
            reminderHour = state.getInt("reminderHour")
            reminderMin = state.getInt("reminderMin")
            reminderDays = WeekdayList(state.getInt("reminderDays"))
            targetType = NumericalHabitType.fromInt(state.getInt("targetType"))
            nameText = state.getString("nameText", "")
            questionText = state.getString("questionText", "")
            notesText = state.getString("notesText", "")
            unitText = state.getString("unitText", "")
            targetText = state.getString("targetText", "")
        }

        updateColors()

        setContent {
            @Suppress("UNUSED_EXPRESSION") revision
            EditHabitScreenMMD(
                isEditing = habitId >= 0,
                state = EditHabitFormState(
                    isNumerical = habitType == HabitType.NUMERICAL,
                    name = nameText,
                    question = questionText,
                    notes = notesText,
                    unit = unitText,
                    target = targetText,
                    freqNum = freqNum,
                    freqDen = freqDen,
                    frequencyLabel = frequencyLabel(),
                    targetTypeLabel = targetTypeLabel(),
                    reminderLabel = reminderLabel(),
                    reminderDaysLabel = reminderDays.toFormattedString(this),
                    hasReminder = reminderHour >= 0,
                    nameError = nameError,
                    targetError = targetError,
                ),
                actions = EditHabitFormActions(
                    onName = { nameText = it; nameError = null },
                    onQuestion = { questionText = it },
                    onNotes = { notesText = it },
                    onUnit = { unitText = it },
                    onTarget = { targetText = it.asPartialNumber(); targetError = null },
                    onPickFrequency = { pickFrequency() },
                    onFrequencyPicked = { num, den ->
                        freqNum = num
                        freqDen = den
                        revision++
                    },
                    onPickTargetType = { pickTargetType() },
                    onReminderPicked = { h, m ->
                        reminderHour = h
                        reminderMin = m
                        revision++
                    },
                    onReminderCleared = {
                        reminderHour = -1
                        reminderMin = -1
                        reminderDays = WeekdayList.EVERY_DAY
                        revision++
                    },
                    onWeekdaysPicked = { days ->
                        var picked = WeekdayList(days)
                        if (picked.isEmpty) picked = WeekdayList.EVERY_DAY
                        reminderDays = picked
                        revision++
                    },
                    weekdaysSelected = { reminderDays.toArray() },
                    onSave = { if (validate()) save() },
                    onBack = { finish() },
                ),
                choiceOptions = choiceOptions,
                onChoicePicked = { which ->
                    choiceOptions = null
                    choiceCallback(which)
                },
                onChoiceDismissed = { choiceOptions = null },
            )
        }

        for (fragment in supportFragmentManager.fragments) {
            (fragment as DialogFragment).dismiss()
        }
    }

    private fun save() {
        val component = (application as HabitsApplication).component
        val habit = component.modelFactory.buildHabit()

        var original: Habit? = null
        if (habitId >= 0) {
            original = component.habitList.getById(habitId)!!
            habit.copyFrom(original)
        }

        habit.name = nameText.trim()
        habit.question = questionText.trim()
        habit.description = notesText.trim()
        habit.color = color
        if (reminderHour >= 0) {
            habit.reminder = Reminder(reminderHour, reminderMin, reminderDays)
        } else {
            habit.reminder = null
        }

        habit.frequency = Frequency(freqNum, freqDen)
        if (habitType == HabitType.NUMERICAL) {
            // validate() has already rejected anything unparseable, so this
            // cannot normally be null. It is still not allowed to throw: this
            // line used to be `toDouble()`, and "1.2.3" -- which the field
            // accepted, because KeyboardType only picks a keyboard and filters
            // nothing -- crashed the app on Save.
            habit.targetValue = parsedTarget() ?: return
            habit.targetType = targetType
            habit.unit = unitText.trim()
        }
        habit.type = habitType

        val command = if (habitId >= 0) {
            EditHabitCommand(
                component.habitList,
                habitId,
                habit
            )
        } else {
            CreateHabitCommand(
                component.modelFactory,
                component.habitList,
                habit
            )
        }
        component.commandRunner.run(command)
        finish()
    }

    private fun validate(): Boolean {
        var isValid = true
        if (nameText.isBlank()) {
            nameError = getString(R.string.validation_cannot_be_blank)
            isValid = false
        }
        if (habitType == HabitType.NUMERICAL) {
            if (targetText.isBlank()) {
                targetError = getString(R.string.validation_cannot_be_blank)
                isValid = false
            } else if (parsedTarget() == null) {
                // Blank was the only thing this used to check, which is why a
                // malformed target reached toDouble() and crashed.
                targetError = getString(R.string.validation_number_expected)
                isValid = false
            }
        }
        return isValid
    }

    /**
     * The target as a number, or null if it is not one.
     *
     * The separator is normalised because a locale whose keyboard offers ","
     * types "1,5", which is a perfectly good number to the person typing it
     * and not one at all to [String.toDoubleOrNull].
     */
    private fun parsedTarget(): Double? =
        targetText.trim().replace(',', '.').toDoubleOrNull()

    /**
     * Keeps what someone can plausibly be part-way through typing: digits, and
     * at most one separator. It has to accept "1." — that is what "1.5" looks
     * like halfway through — so this is not the same test as [parsedTarget],
     * and it does not replace it.
     */
    private fun String.asPartialNumber(): String {
        var separatorSeen = false
        return buildString {
            for (c in this@asPartialNumber) {
                when {
                    c.isDigit() -> append(c)
                    (c == '.' || c == ',') && !separatorSeen -> {
                        separatorSeen = true
                        append(c)
                    }
                }
            }
        }
    }

    private fun reminderLabel(): String =
        if (reminderHour < 0) {
            getString(R.string.reminder_off)
        } else {
            formatTime(this, reminderHour, reminderMin) ?: getString(R.string.reminder_off)
        }

    @SuppressLint("StringFormatMatches")
    private fun frequencyLabel(): String =
        if (habitType == HabitType.NUMERICAL) {
            when (freqDen) {
                1 -> getString(R.string.every_day)
                7 -> getString(R.string.every_week)
                30 -> getString(R.string.every_month)
                else -> "$freqNum/$freqDen"
            }
        } else {
            formatFrequency(freqNum, freqDen, resources)
        }

    private fun targetTypeLabel(): String = when (targetType) {
        NumericalHabitType.AT_MOST -> getString(R.string.target_type_at_most)
        else -> getString(R.string.target_type_at_least)
    }

    /**
     * A numerical habit only chooses a period, so it is a one-of-N sheet. A
     * boolean habit's frequency is an arbitrary numerator and denominator
     * ("3 times in 7 days"), which is a form rather than a list, so the screen
     * raises `FrequencySheet` for it directly and never reaches this.
     */
    private fun pickFrequency() {
        val options = listOf(
            getString(R.string.every_day) to 1,
            getString(R.string.every_week) to 7,
            getString(R.string.every_month) to 30,
        )
        showChoiceSheet(options.map { it.first }) { which ->
            freqDen = options[which].second
            revision++
        }
    }

    private fun pickTargetType() {
        val options = listOf(
            getString(R.string.target_type_at_least),
            getString(R.string.target_type_at_most),
        )
        showChoiceSheet(options) { which ->
            targetType =
                if (which == 0) NumericalHabitType.AT_LEAST else NumericalHabitType.AT_MOST
            revision++
        }
    }

    /** A one-of-N choice, raised as a sheet. */
    private fun showChoiceSheet(options: List<String>, onPicked: (Int) -> Unit) {
        choiceOptions = options
        choiceCallback = onPicked
        revision++
    }

    /**
     * Every habit is drawn in the same black ink, so there is nothing to tint
     * and no swatch to update. [androidColor] is still resolved because the
     * time picker takes an accent colour.
     */
    private fun updateColors() {
        androidColor = themeSwitcher.currentTheme.color(color).toInt()
    }

    private fun getFormattedValidationError(@StringRes resId: Int): Spanned {
        val html = "<font color=#000000>${getString(resId)}</font>"
        return Html.fromHtml(html)
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        with(state) {
            putLong("habitId", habitId)
            putInt("habitType", habitType.value)
            putInt("paletteColor", color.paletteIndex)
            putInt("androidColor", androidColor)
            putInt("freqNum", freqNum)
            putInt("freqDen", freqDen)
            putInt("reminderHour", reminderHour)
            putInt("reminderMin", reminderMin)
            putInt("reminderDays", reminderDays.toInteger())
            // The five text fields and the target type were missing here, so
            // everything typed into the form was lost whenever the process was
            // killed in the background and the activity rebuilt -- the habit's
            // stored values came back and the edit did not. They are plain
            // `mutableStateOf`, which survives a configuration change but not
            // process death; only the bundle does.
            putInt("targetType", targetType.value)
            putString("nameText", nameText)
            putString("questionText", questionText)
            putString("notesText", notesText)
            putString("unitText", unitText)
            putString("targetText", targetText)
        }
    }
}
