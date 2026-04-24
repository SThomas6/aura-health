package com.example.mob_dev_portfolio.ui.medication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.medication.ReminderFrequency
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Create / edit screen for a single medication reminder.
 *
 * Field-by-field validation is light on purpose — the model enforces
 * "name required" + "weekly mask non-empty" + "one-off in the future"
 * via [MedicationEditorUiState.canSave] and the Save button is simply
 * disabled when the state isn't valid. That keeps the form forgiving
 * (no red shouting at the user on every keystroke) while still making
 * it impossible to save a meaningless reminder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationEditorScreen(
    reminderId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: MedicationEditorViewModel = viewModel(factory = MedicationEditorViewModel.factory(reminderId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (reminderId == null) "New reminder" else "Edit reminder") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::updateName,
                label = { Text("Medication name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.dosage,
                onValueChange = viewModel::updateDosage,
                label = { Text("Dosage (e.g. 10 mg)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Time-of-day picker — irrelevant for a one-off (which has
            // a full datetime), but kept visible so switching between
            // kinds doesn't wipe the user's earlier time pick.
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Time of day",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { showTimePicker = true }) {
                        Text(formatTime(state.timeMinutes))
                    }
                }
            }

            FrequencySection(
                state = state,
                onKindChange = viewModel::updateFrequency,
                onToggleDay = viewModel::toggleWeekday,
                onPickDate = { showDatePicker = true },
            )

            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Enabled",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Pausing a reminder keeps the schedule without firing it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.enabled, onCheckedChange = viewModel::updateEnabled)
                }
            }

            if (state.error != null) {
                Text(
                    state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = state.canSave && !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.saving) "Saving…" else "Save reminder")
            }

            if (reminderId != null) {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete reminder")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialMinutes = state.timeMinutes,
            onDismiss = { showTimePicker = false },
            onConfirm = { minutes ->
                viewModel.updateTime(minutes)
                showTimePicker = false
            },
        )
    }

    if (showDatePicker) {
        DatePickerDialog(
            initialEpochMillis = state.oneOffAtEpochMillis,
            timeMinutes = state.timeMinutes,
            onDismiss = { showDatePicker = false },
            onConfirm = { millis ->
                viewModel.updateOneOff(millis)
                showDatePicker = false
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete reminder?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete(onDeleted)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrequencySection(
    state: MedicationEditorUiState,
    onKindChange: (FrequencyKind) -> Unit,
    onToggleDay: (Int) -> Unit,
    onPickDate: () -> Unit,
) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Frequency",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            val kinds = FrequencyKind.values()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { index, kind ->
                    SegmentedButton(
                        selected = state.frequencyKind == kind,
                        onClick = { onKindChange(kind) },
                        shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                    ) { Text(kind.label) }
                }
            }
            Spacer(Modifier.height(10.dp))
            when (state.frequencyKind) {
                FrequencyKind.Daily -> {
                    Text(
                        "Fires every day at the selected time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FrequencyKind.Weekly -> WeekdayChips(state.weeklyMask, onToggleDay)
                FrequencyKind.OneOff -> {
                    val label = state.oneOffAtEpochMillis?.let {
                        Instant.ofEpochMilli(it)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault()))
                    } ?: "Pick a date"
                    OutlinedButton(onClick = onPickDate) { Text(label) }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Fires once at the picked date + time and then retires.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val FrequencyKind.label: String
    get() = when (this) {
        FrequencyKind.Daily -> "Daily"
        FrequencyKind.Weekly -> "Weekly"
        FrequencyKind.OneOff -> "One-off"
    }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChips(mask: Int, onToggle: (Int) -> Unit) {
    val days = listOf(
        "Mon" to ReminderFrequency.MASK_MON,
        "Tue" to ReminderFrequency.MASK_TUE,
        "Wed" to ReminderFrequency.MASK_WED,
        "Thu" to ReminderFrequency.MASK_THU,
        "Fri" to ReminderFrequency.MASK_FRI,
        "Sat" to ReminderFrequency.MASK_SAT,
        "Sun" to ReminderFrequency.MASK_SUN,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        days.forEach { (label, bit) ->
            FilterChip(
                selected = (mask and bit) != 0,
                onClick = { onToggle(bit) },
                label = { Text(label) },
            )
        }
    }
    if (mask == 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Pick at least one day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick time") },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialog(
    initialEpochMillis: Long?,
    timeMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initialDate = initialEpochMillis
        ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        ?: LocalDate.now(zone).plusDays(1)
    val datePickerState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(zone).toInstant().toEpochMilli(),
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = datePickerState.selectedDateMillis
                if (picked != null) {
                    val day = Instant.ofEpochMilli(picked).atZone(zone).toLocalDate()
                    val combined = LocalDateTime.of(
                        day,
                        LocalTime.of(timeMinutes / 60, timeMinutes % 60),
                    ).atZone(zone).toInstant().toEpochMilli()
                    onConfirm(combined)
                } else {
                    onDismiss()
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        androidx.compose.material3.DatePicker(state = datePickerState)
    }
}
