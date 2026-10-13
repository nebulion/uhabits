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
package org.isoron.uhabits.notifications

import android.content.ContentUris
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.isoron.uhabits.mmd.MmdTheme
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.bottom_sheet.rememberModalBottomSheetMMDState
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.time.TimeInputMMD
import com.mudita.mmd.components.time.rememberTimeInputMMDState
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.receivers.ReminderController
import org.isoron.uhabits.utils.SystemUtils
import java.util.Calendar

/**
 * "Later" on a reminder: how long to put it off for.
 *
 * This was the last screen in the app still raising Material chrome, and the
 * worst of what was left. An `AlertDialog.setItems` list drew Material's own
 * rows over Android's grey scrim, and "Custom time" opened the third-party
 * `com.android.datetimepicker` radial dial -- a clock face whose numerals are
 * literally `#8c8c8c` in `pickers.xml`, driven by a drag gesture, on a panel
 * that repaints slowly and cannot render grey. It survived this long only
 * because nobody follows a notification into it.
 *
 * It is `ModalBottomSheetMMD` now, which is what MuditaOS raises for a choice,
 * with `RadioButtonMMD` rows in MMD's own shape. "Custom time" swaps the sheet
 * for `TimeInputMMD` -- the same component that replaced the radial dial on
 * the edit screen -- so the two places in this app that ask for a time now ask
 * the same way.
 *
 * The activity stays translucent and finishes on dismissal, so a reminder
 * still snoozes without the app appearing to open.
 */
class SnoozeDelayPickerActivity : FragmentActivity() {

    private lateinit var habit: Habit
    private lateinit var reminderController: ReminderController

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)

        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        val component = (applicationContext as HabitsApplication).component
        val found = component.habitList.getById(ContentUris.parseId(data))
        if (found == null) {
            finish()
            return
        }
        habit = found
        reminderController = component.reminderController

        setContent {
            MmdTheme {
                SnoozeSheet(
                    onDelayPicked = {
                        reminderController.onSnoozeDelayPicked(habit, it)
                        finish()
                    },
                    onTimePicked = { hour, minute ->
                        reminderController.onSnoozeTimePicked(habit, hour, minute)
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
        SystemUtils.unlockScreen(this)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}

/** One snooze option: how long to wait, and what to call it. */
private data class SnoozeOption(val minutes: Int, val labelRes: Int)

/**
 * The fixed delays, and the escape hatch.
 *
 * These were two parallel resource arrays -- names in one, minutes in another,
 * kept in step by position and by nothing else, with `-1` in the values array
 * standing for "ask for a time instead". Pairing them here means the sentinel
 * cannot drift away from the label that explains it.
 */
private val SNOOZE_OPTIONS = listOf(
    SnoozeOption(15, R.string.interval_15_minutes),
    SnoozeOption(30, R.string.interval_30_minutes),
    SnoozeOption(60, R.string.interval_1_hour),
    SnoozeOption(120, R.string.interval_2_hour),
    SnoozeOption(240, R.string.interval_4_hour),
    SnoozeOption(480, R.string.interval_8_hour),
    SnoozeOption(1440, R.string.interval_24_hour),
)

@Composable
private fun SnoozeSheet(
    onDelayPicked: (Int) -> Unit,
    onTimePicked: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickingTime by remember { mutableStateOf(false) }
    ModalBottomSheetMMD(
        onDismissRequest = onDismiss,
        // Without this a sheet taller than half the screen opens at half
        // height, which for eight options puts the last of them below the fold
        // behind a drag this panel handles badly.
        sheetState = rememberModalBottomSheetMMDState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        if (pickingTime) {
            CustomTime(onPicked = onTimePicked, onCancel = { pickingTime = false })
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                TextMMD(
                    text = stringResource(R.string.select_snooze_delay),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                HorizontalDividerMMD()
                SNOOZE_OPTIONS.forEach { option ->
                    SnoozeRow(option.labelRes) { onDelayPicked(option.minutes) }
                }
                SnoozeRow(R.string.interval_custom) { pickingTime = true }
            }
        }
    }
}

/**
 * One row of the list.
 *
 * MMD's own shape, from the usage sample in `RadioButtonMMD.kt`: a 56dp row
 * carrying the click itself, an inert button inside it, and the label held off
 * that button by 16dp. Nothing is ever shown as selected -- picking a delay
 * closes the sheet -- but the button keeps these rows the same object as every
 * other one-of-N list in the app rather than a list of bare labels.
 */
@Composable
private fun SnoozeRow(labelRes: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = false, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButtonMMD(selected = false, onClick = null)
        TextMMD(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/**
 * "Custom time", as `TimeInputMMD`.
 *
 * The container colour is stated rather than defaulted. MMD leaves six
 * `surface*` roles `Color.Unspecified` in `eInkColorScheme`, and this
 * component's unselected hour/minute box resolves to one of them -- so by
 * default it renders with no fill at all, as bare digits beside a solid black
 * field.
 */
@Composable
private fun CustomTime(onPicked: (Int, Int) -> Unit, onCancel: () -> Unit) {
    val now = remember { Calendar.getInstance() }
    val state = rememberTimeInputMMDState(
        initialHour = now[Calendar.HOUR_OF_DAY],
        initialMinute = now[Calendar.MINUTE],
        is24Hour = true,
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextMMD(
            text = stringResource(R.string.select_snooze_delay),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        TimeInputMMD(state = state)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButtonMMD(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                TextMMD(text = stringResource(android.R.string.cancel))
            }
            ButtonMMD(
                onClick = { onPicked(state.hour, state.minute) },
                modifier = Modifier.weight(1f),
            ) {
                TextMMD(text = stringResource(android.R.string.ok))
            }
        }
    }
}
