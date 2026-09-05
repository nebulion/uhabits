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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.time.TimeInputMMD
import com.mudita.mmd.components.time.rememberTimeInputMMDState
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import org.isoron.uhabits.R
import org.isoron.uhabits.core.utils.DateUtils
import java.util.Calendar

/**
 * Everything the edit screen shows and can do. The activity still owns the
 * habit and the commands; this is only the form.
 */
data class EditHabitFormState(
    val isNumerical: Boolean,
    val name: String,
    val question: String,
    val notes: String,
    val unit: String,
    val target: String,
    val freqNum: Int,
    val freqDen: Int,
    val frequencyLabel: String,
    val targetTypeLabel: String,
    val reminderLabel: String,
    val reminderDaysLabel: String,
    val hasReminder: Boolean,
    val nameError: String?,
    val targetError: String?,
)

data class EditHabitFormActions(
    val onName: (String) -> Unit,
    val onQuestion: (String) -> Unit,
    val onNotes: (String) -> Unit,
    val onUnit: (String) -> Unit,
    val onTarget: (String) -> Unit,
    val onPickFrequency: () -> Unit,
    val onFrequencyPicked: (num: Int, den: Int) -> Unit,
    val onPickTargetType: () -> Unit,
    val onReminderPicked: (hour: Int, minute: Int) -> Unit,
    val onReminderCleared: () -> Unit,
    val onWeekdaysPicked: (BooleanArray) -> Unit,
    val weekdaysSelected: () -> BooleanArray,
    val onSave: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * The edit screen, built from Mudita Mindful Design components.
 *
 * What it replaces is a `ScrollView` of Material `TextInputLayout` boxes: each
 * field was drawn as an outlined rectangle with a floating label notched into
 * its top edge, and each picker as a row with a Material spinner triangle. None
 * of that is MMD, and the notched outline in particular is a Material
 * signature with no counterpart anywhere on the device.
 *
 * Here the fields are `TextFieldMMD` and the pickers are rows that open a
 * `ModalBottomSheetMMD`, which is what the Kompakt does for a choice: Sound and
 * Bluetooth both raise a sheet rather than a dialog. Time is `TimeInputMMD`,
 * replacing the third-party radial `TimePickerDialog` — a rotary dial is a
 * touch-and-drag gesture on a panel that repaints slowly, and it is the last
 * thing on this screen that assumed a phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditHabitScreenMMD(
    isEditing: Boolean,
    state: EditHabitFormState,
    actions: EditHabitFormActions,
    /** A pending one-of-N choice, or null when nothing is being chosen. */
    choiceOptions: List<String>? = null,
    onChoicePicked: (Int) -> Unit = {},
    onChoiceDismissed: () -> Unit = {},
) {
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    // Frequency is a page of its own rather than a sheet over this one. It is
    // the only picker here that is a form rather than a list, and MuditaOS
    // answers a form with a page -- Settings drills down into one rather than
    // raising anything over itself.
    if (sheet == Sheet.FREQUENCY) {
        ThemeMMD {
            FrequencyPage(
                initialNum = state.freqNum,
                initialDen = state.freqDen,
                onCancel = { sheet = Sheet.NONE },
                onPicked = { num, den ->
                    sheet = Sheet.NONE
                    actions.onFrequencyPicked(num, den)
                },
            )
        }
        return
    }

    ThemeMMD {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                // The rule is drawn here, not by the bar's own showDivider,
                // which cannot produce the 3dp rule: TopAppBarMMD hands its
                // 3dp `dividerLineHeight` to a raw Material3 HorizontalDivider
                // as a *width* and never passes a thickness, so the line falls
                // back to Material's 1dp. HorizontalDividerMMD defaults to the
                // real DividerDefaultsMMD.Thickness of 3dp.
                Column {
                    TopAppBarMMD(
                        showDivider = false,
                        title = {
                            TextMMD(
                                text = stringResource(
                                    if (isEditing) R.string.edit_habit else R.string.create_habit
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = actions.onBack) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_back_eink),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        },
                        actions = {
                            // Primary MMD button: solid black, label knocked
                            // out in white. Save is the one committing action
                            // here, and MuditaOS labels its committing actions
                            // with a word.
                            ButtonMMD(
                                onClick = actions.onSave,
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                TextMMD(
                                    text = stringResource(R.string.save),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        },
                    )
                    HorizontalDividerMMD()
                }
            },
        ) { insets ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .padding(horizontal = 16.dp),
            ) {
                Field(
                    label = stringResource(R.string.name),
                    value = state.name,
                    onValueChange = actions.onName,
                    error = state.nameError,
                    singleLine = true,
                )
                Field(
                    label = stringResource(R.string.question),
                    value = state.question,
                    onValueChange = actions.onQuestion,
                    singleLine = true,
                )
                if (state.isNumerical) {
                    Field(
                        label = stringResource(R.string.target),
                        value = state.target,
                        onValueChange = actions.onTarget,
                        error = state.targetError,
                        singleLine = true,
                        keyboardType = KeyboardType.Number,
                    )
                    Field(
                        label = stringResource(R.string.unit),
                        value = state.unit,
                        onValueChange = actions.onUnit,
                        singleLine = true,
                    )
                    PickerRow(
                        label = stringResource(R.string.target_type),
                        value = state.targetTypeLabel,
                        onClick = actions.onPickTargetType,
                    )
                }
                PickerRow(
                    label = stringResource(R.string.frequency),
                    value = state.frequencyLabel,
                    // Numerical habits choose a period from a fixed list, so
                    // that stays a one-of-N sheet driven by the activity. A
                    // boolean habit's frequency is an arbitrary numerator and
                    // denominator, which needs its own form, not a list.
                    onClick = if (state.isNumerical) {
                        actions.onPickFrequency
                    } else {
                        { sheet = Sheet.FREQUENCY }
                    },
                )
                PickerRow(
                    label = stringResource(R.string.reminder),
                    value = state.reminderLabel,
                    onClick = { sheet = Sheet.TIME },
                )
                if (state.hasReminder) {
                    PickerRow(
                        label = stringResource(R.string.select_weekdays),
                        value = state.reminderDaysLabel,
                        onClick = { sheet = Sheet.WEEKDAYS },
                    )
                }
                Field(
                    label = stringResource(R.string.notes),
                    value = state.notes,
                    onValueChange = actions.onNotes,
                    singleLine = false,
                )
            }
        }

        when (sheet) {
            Sheet.TIME -> TimeSheet(
                onDismiss = { sheet = Sheet.NONE },
                onPicked = { h, m ->
                    sheet = Sheet.NONE
                    actions.onReminderPicked(h, m)
                },
                onCleared = {
                    sheet = Sheet.NONE
                    actions.onReminderCleared()
                },
            )
            Sheet.WEEKDAYS -> WeekdaySheet(
                initial = actions.weekdaysSelected(),
                onDismiss = { sheet = Sheet.NONE },
                onPicked = {
                    sheet = Sheet.NONE
                    actions.onWeekdaysPicked(it)
                },
            )
            // Handled above, as a page of its own.
            Sheet.FREQUENCY -> Unit
            Sheet.NONE -> Unit
        }

        if (choiceOptions != null) {
            ChoiceSheet(
                options = choiceOptions,
                onDismiss = onChoiceDismissed,
                onPicked = onChoicePicked,
            )
        }
    }
}

/**
 * A one-of-N choice as a sheet rather than an `AlertDialog` with a
 * `select_dialog_item` adapter, which is what the target-type and period
 * pickers used to raise. The Kompakt raises a sheet for this everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSheet(
    options: List<String>,
    onDismiss: () -> Unit,
    onPicked: (Int) -> Unit,
) {
    ModalBottomSheetMMD(
        onDismissRequest = onDismiss,
        // Without this the sheet stops at half height whenever its
        // content is taller than half the screen, which leaves Done
        // below the fold and needing a drag to reach.
        sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            options.forEachIndexed { index, option ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable { onPicked(index) }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    TextMMD(text = option, style = MaterialTheme.typography.bodyMedium)
                }
                if (index < options.lastIndex) HorizontalDividerMMD(thickness = 1.dp)
            }
        }
    }
}

private enum class Sheet { NONE, TIME, WEEKDAYS, FREQUENCY }

/** A labelled text field. The label is a real label, not a notched outline. */
@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        TextFieldMMD(
            value = value,
            onValueChange = onValueChange,
            label = { TextMMD(label, style = MaterialTheme.typography.bodySmall) },
            isError = error != null,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            TextMMD(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * A row that opens a chooser. The chevron is MMD's own, not the Material
 * spinner triangle the old screen used.
 */
@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClick = onClick)
                // Lines this row's text up with the text inside the fields
                // above it. `TextFieldMMD` insets its own label and value by
                // FIELD_CONTENT_INSET, so without this a picker's label starts
                // one whole inset to the left of every field's label and the
                // form reads as two columns that do not agree. The divider is
                // a sibling below, so it stays full width like a field's own
                // indicator line does.
                .padding(horizontal = FIELD_CONTENT_INSET, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.padding(end = 16.dp)) {
                TextMMD(text = label, style = MaterialTheme.typography.bodySmall)
                TextMMD(text = value, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(
                painter = painterResource(R.drawable.mmd_chevron_filled_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp),
            )
        }
        HorizontalDividerMMD(thickness = 1.dp)
    }
}

/**
 * The reminder time, as `TimeInputMMD` in a sheet.
 *
 * "Off" is offered as an explicit action rather than as a cleared field: the
 * old radial dialog hid clearing behind a long-press on the dial.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSheet(
    onDismiss: () -> Unit,
    onPicked: (Int, Int) -> Unit,
    onCleared: () -> Unit,
) {
    val state = rememberTimeInputMMDState(initialHour = 8, initialMinute = 0)
    ModalBottomSheetMMD(
        onDismissRequest = onDismiss,
        // Without this the sheet stops at half height whenever its
        // content is taller than half the screen, which leaves Done
        // below the fold and needing a drag to reach.
        sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TextMMD(
                text = stringResource(R.string.reminder),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            TimeInputMMD(state = state)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(modifier = Modifier.clickable(onClick = onCleared).padding(12.dp)) {
                    TextMMD(
                        text = stringResource(R.string.reminder_off),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ButtonMMD(onClick = { onPicked(state.hour, state.minute) }) {
                    TextMMD(
                        text = stringResource(R.string.done_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** Which of the five frequency shapes is being edited. */
private enum class FreqOption { EVERY_DAY, EVERY_X_DAYS, PER_WEEK, PER_MONTH, PER_Y_DAYS }

/**
 * A boolean habit's frequency, as a page of its own.
 *
 * This replaces the one `AlertDialog` the edit screen still raised. That
 * dialog was the last thing on this screen that was not MMD: plain AppCompat
 * radio buttons, `EditText`s in a boxed drawable, and, most visibly, the
 * window's own **grey dim scrim** over everything behind it, which was the
 * only grey left on the screen.
 *
 * **It is a page, not a sheet.** A sheet was tried first and is the right
 * answer for a list -- Sound and Bluetooth both raise one -- but this is the
 * only picker here that is a *form*: five mutually exclusive shapes, two of
 * them carrying numbers. MuditaOS answers a form by drilling down into a page
 * with its own header, which is also what Settings does, and a page has room
 * to lay the fields out properly instead of pushing them under a keyboard.
 *
 * **The numbers are edited below the list, not inside it.** Writing the field
 * into the sentence, "Every [3] days", is what the old dialog did, and it
 * cannot be made to line up: `TextFieldMMD` is 56dp tall with its own content
 * padding and an indicator line along its bottom edge, so its digits never
 * share a baseline with the words beside them, and because each sentence puts
 * its `%d` in a different place the numbers land in a different column on
 * every row. Here every row is a plain sentence, and only the selected row
 * opens a field, full width and indented to the text.
 *
 * **An unfilled number reads as `X`, not as a default.** Only the shape the
 * habit already uses arrives with a value in it; the others start empty and
 * say "X times per week", so no row claims a choice that was never made.
 *
 * The five options are the same five, and so is the clamp when it commits: a
 * numerator that is not smaller than its denominator, or below 1, falls back
 * to "every day", exactly as the old dialog did. An empty field lands there
 * too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrequencyPage(
    initialNum: Int,
    initialDen: Int,
    onCancel: () -> Unit,
    onPicked: (num: Int, den: Int) -> Unit,
) {
    val initialOption = remember(initialNum, initialDen) {
        when {
            initialDen == 30 || initialDen == 31 -> FreqOption.PER_MONTH
            initialNum == 1 && initialDen == 1 -> FreqOption.EVERY_DAY
            initialNum == 1 -> FreqOption.EVERY_X_DAYS
            initialDen == 7 -> FreqOption.PER_WEEK
            else -> FreqOption.PER_Y_DAYS
        }
    }
    var option by remember { mutableStateOf(initialOption) }
    // Only the shape the habit is already on is seeded. Every other row starts
    // empty so it reads as a template rather than as a choice.
    var everyXDays by remember {
        mutableStateOf(if (initialOption == FreqOption.EVERY_X_DAYS) "$initialDen" else "")
    }
    var perWeek by remember {
        mutableStateOf(if (initialOption == FreqOption.PER_WEEK) "$initialNum" else "")
    }
    var perMonth by remember {
        mutableStateOf(if (initialOption == FreqOption.PER_MONTH) "$initialNum" else "")
    }
    var perYDaysNum by remember {
        mutableStateOf(if (initialOption == FreqOption.PER_Y_DAYS) "$initialNum" else "")
    }
    var perYDaysDen by remember {
        mutableStateOf(if (initialOption == FreqOption.PER_Y_DAYS) "$initialDen" else "")
    }

    BackHandler(onBack = onCancel)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBarMMD(
                    showDivider = false,
                    title = {
                        TextMMD(
                            text = stringResource(R.string.frequency),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back_eink),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    },
                    actions = {
                        ButtonMMD(
                            onClick = {
                                var num = 1
                                var den = 1
                                when (option) {
                                    FreqOption.EVERY_DAY -> Unit
                                    FreqOption.EVERY_X_DAYS ->
                                        everyXDays.toIntOrNull()?.let { den = it }
                                    FreqOption.PER_WEEK -> {
                                        perWeek.toIntOrNull()?.let { num = it }
                                        den = 7
                                    }
                                    FreqOption.PER_MONTH -> {
                                        perMonth.toIntOrNull()?.let { num = it }
                                        den = 30
                                    }
                                    FreqOption.PER_Y_DAYS -> {
                                        perYDaysNum.toIntOrNull()?.let { num = it }
                                        perYDaysDen.toIntOrNull()?.let { den = it }
                                    }
                                }
                                // The old dialog's guard, kept: anything that
                                // is not a sensible "n times in m days"
                                // collapses to every day.
                                if (num >= den || num < 1) {
                                    num = 1
                                    den = 1
                                }
                                onPicked(num, den)
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            TextMMD(
                                text = stringResource(R.string.done_label),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    },
                )
                HorizontalDividerMMD()
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = SHEET_PADDING)
                .selectableGroup(),
        ) {
            FrequencyRow(
                selected = option == FreqOption.EVERY_DAY,
                onSelect = { option = FreqOption.EVERY_DAY },
                label = stringResource(R.string.every_day),
            )

            FrequencyRow(
                selected = option == FreqOption.EVERY_X_DAYS,
                onSelect = { option = FreqOption.EVERY_X_DAYS },
                label = freqLabel(R.string.every_x_days, everyXDays),
            )
            if (option == FreqOption.EVERY_X_DAYS) {
                NumberField(
                    value = everyXDays,
                    onValueChange = { everyXDays = it },
                    label = stringResource(R.string.frequency_days_label),
                )
            }

            FrequencyRow(
                selected = option == FreqOption.PER_WEEK,
                onSelect = { option = FreqOption.PER_WEEK },
                label = freqLabel(R.string.x_times_per_week, perWeek),
            )
            if (option == FreqOption.PER_WEEK) {
                NumberField(
                    value = perWeek,
                    onValueChange = { perWeek = it },
                    label = stringResource(R.string.frequency_times_label),
                )
            }

            FrequencyRow(
                selected = option == FreqOption.PER_MONTH,
                onSelect = { option = FreqOption.PER_MONTH },
                label = freqLabel(R.string.x_times_per_month, perMonth),
            )
            if (option == FreqOption.PER_MONTH) {
                NumberField(
                    value = perMonth,
                    onValueChange = { perMonth = it },
                    label = stringResource(R.string.frequency_times_label),
                )
            }

            FrequencyRow(
                selected = option == FreqOption.PER_Y_DAYS,
                onSelect = { option = FreqOption.PER_Y_DAYS },
                label = freqLabel(
                    R.string.x_times_per_y_days,
                    perYDaysNum,
                    perYDaysDen,
                    placeholders = listOf("X", "Y"),
                ),
            )
            if (option == FreqOption.PER_Y_DAYS) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = ROW_TEXT_INSET),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    NumberField(
                        value = perYDaysNum,
                        onValueChange = { perYDaysNum = it },
                        label = stringResource(R.string.frequency_times_label),
                        modifier = Modifier.weight(1f),
                        inset = false,
                    )
                    NumberField(
                        value = perYDaysDen,
                        onValueChange = { perYDaysDen = it },
                        label = stringResource(R.string.frequency_days_label),
                        modifier = Modifier.weight(1f),
                        inset = false,
                    )
                }
            }
        }
    }
}

/**
 * One option's sentence, with each `%d` filled by the value typed for it, or
 * by its placeholder letter while that is still empty.
 *
 * The string is not formatted through `stringResource`'s own arguments because
 * a `%d` cannot be handed an "X"; each slot is substituted in turn instead,
 * which keeps "Every %d days" one translatable sentence either way.
 */
@Composable
private fun freqLabel(
    templateRes: Int,
    vararg values: String,
    placeholders: List<String> = listOf("X"),
): String {
    val template = stringResource(templateRes)
    var out = template
    values.forEachIndexed { index, value ->
        val slot = value.ifBlank { placeholders.getOrElse(index) { "X" } }
        out = out.replaceFirst("%d", slot)
    }
    return out
}

/**
 * One frequency choice.
 *
 * The shape is MMD's own, copied from the usage sample in `RadioButtonMMD.kt`:
 * a 56dp row that carries the selection itself, an inert `RadioButtonMMD`
 * inside it, and the label held off the button by 16dp.
 */
@Composable
private fun FrequencyRow(
    selected: Boolean,
    onSelect: () -> Unit,
    label: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row already handles the click, so the button takes no handler of
        // its own, which is what MMD's sample does and what keeps the whole
        // row a single target.
        RadioButtonMMD(selected = selected, onClick = null)
        TextMMD(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = RADIO_LABEL_GAP),
        )
    }
}

/**
 * A number, entered the way every other value on this screen is.
 *
 * It filters to digits as it goes rather than trusting `KeyboardType.Number`,
 * which only says which keyboard to raise and does not stop anything from
 * arriving: the target field on this same screen hands whatever it is given
 * to `toDouble()` and crashes on "1.2.3".
 */
@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    inset: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    TextFieldMMD(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() }.take(3)) },
        label = { TextMMD(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        // The keyboard covers this sheet's own Done button while it is up, so
        // its action key has to be the way out of it.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (inset) ROW_TEXT_INSET else 0.dp, bottom = 8.dp),
    )
}

/** A sheet's own heading. */
@Composable
private fun SheetTitle(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * How far `TextFieldMMD` insets its own label and value.
 *
 * `TextFieldDefaultsMMD.contentPaddingWithLabel` is `TextFieldPadding` on all
 * four sides, and `TextFieldPadding` is 16dp. Anything that has to line up
 * with a field's text — rather than with the field's outer edge — carries the
 * same inset.
 */
private val FIELD_CONTENT_INSET = 16.dp

/** MMD's list row height, from the `RadioButtonMMD` and `CheckboxMMD` samples. */
private val ROW_HEIGHT = 56.dp

/** The gap MMD's own samples put between a leading control and its label. */
private val RADIO_LABEL_GAP = 16.dp

/**
 * How far a row's text sits from the edge, for anything that has to line up
 * under the text rather than under the button.
 *
 * `RadioButtonMMD` is a 20dp circle inside 2dp of its own padding, and it only
 * takes Material's 48dp interactive minimum when it is given an `onClick` of
 * its own. Ours is inert -- the row carries the click -- so it measures 24dp,
 * not 48dp.
 */
private val ROW_TEXT_INSET = 24.dp + RADIO_LABEL_GAP

/**
 * The sheets' own side padding. `ModalBottomSheetMMD` applies none of its own,
 * so this is 16dp to match the horizontal inset MMD's own row samples use.
 */
private val SHEET_PADDING = 16.dp

/** Which days the reminder fires, as `CheckboxMMD` rows in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekdaySheet(
    initial: BooleanArray,
    onDismiss: () -> Unit,
    onPicked: (BooleanArray) -> Unit,
) {
    val checked = remember { initial.copyOf().toTypedArray().toMutableList() }
    var revision by remember { mutableStateOf(0) }
    // Saturday-first, matching WeekdayList's internal order.
    val dayNames = remember { DateUtils.getLongWeekdayNames(Calendar.SATURDAY) }

    ModalBottomSheetMMD(
        onDismissRequest = onDismiss,
        // Without this the sheet stops at half height whenever its
        // content is taller than half the screen, which leaves Done
        // below the fold and needing a drag to reach.
        sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            TextMMD(
                text = stringResource(R.string.select_weekdays),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            repeat(7) { i ->
                @Suppress("UNUSED_EXPRESSION") revision
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable {
                            checked[i] = !checked[i]
                            revision++
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckboxMMD(
                        checked = checked[i],
                        onCheckedChange = {
                            checked[i] = it
                            revision++
                        },
                    )
                    TextMMD(
                        text = dayNames.getOrElse(i) { "" }.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            ButtonMMD(
                onClick = { onPicked(checked.toBooleanArray()) },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                TextMMD(
                    text = stringResource(R.string.done_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
