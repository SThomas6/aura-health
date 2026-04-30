package com.example.mob_dev_portfolio.ui.medication

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.medication.DoseStatus
import com.example.mob_dev_portfolio.data.medication.MedicationReminder
import com.example.mob_dev_portfolio.reminders.MedicationReminderScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Medication reminders list + 30-day dose history.
 *
 * Layout is a single LazyColumn split into two logical sections:
 *   - Upcoming reminders (sorted by next-fire; disabled items sink).
 *   - Dose history (newest first, 30-day window).
 * The sections share a column so the user can scroll the whole page as
 * one surface rather than juggling two independently-scrolling lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationListScreen(
    onBack: () -> Unit,
    onAddReminder: () -> Unit,
    onEditReminder: (Long) -> Unit,
    viewModel: MedicationListViewModel = viewModel(factory = MedicationListViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<MedicationReminder?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Medication reminders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddReminder) {
                Icon(Icons.Filled.Add, contentDescription = "Add reminder")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 88.dp, // room for the FAB
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.globalEnabled) {
                item { GlobalDisabledBanner() }
            }

            item {
                SectionHeader(
                    title = "Upcoming",
                    subtitle = if (state.reminders.isEmpty()) "No reminders yet. Tap + to add one."
                    else null,
                )
            }

            items(state.reminders, key = { "reminder-${it.reminder.id}" }) { row ->
                ReminderCard(
                    row = row,
                    onToggle = { viewModel.setEnabled(row.reminder, it) },
                    onEdit = { onEditReminder(row.reminder.id) },
                    onDelete = { pendingDelete = row.reminder },
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
            item {
                SectionHeader(
                    title = "History (30 days)",
                    subtitle = if (state.history.isEmpty())
                        "Taken / snoozed / missed doses appear here."
                    else null,
                )
            }
            items(state.history, key = { "event-${it.event.id}" }) { row ->
                HistoryEventRow(
                    row = row,
                    onMarkTaken = { viewModel.markTaken(row.event) },
                    onSnooze = { viewModel.snooze(row.event) },
                )
            }
        }
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${target.name}?") },
            text = {
                Text(
                    "The reminder and its 30-day dose history will be removed. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun GlobalDisabledBanner() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Reminders are paused",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Turn them back on from Settings → Medication reminders.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReminderCard(
    row: ReminderRow,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.reminder.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (row.reminder.dosage.isNotBlank()) {
                        Text(
                            row.reminder.dosage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = row.reminder.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics {
                        contentDescription = if (row.reminder.enabled) {
                            "${row.reminder.name} reminder is active. Double-tap to pause."
                        } else {
                            "${row.reminder.name} reminder is paused. Double-tap to re-enable."
                        }
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                formatNextFire(row),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${row.frequencyLabel} · ${formatTime(row.reminder.timeOfDayMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun HistoryEventRow(
    row: HistoryRow,
    onMarkTaken: () -> Unit,
    onSnooze: () -> Unit,
) {
    Column {
        Row(modifier = Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Column {
                    Text(
                        row.medicationName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        formatHistoryTimestamp(row.event.scheduledAtEpochMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // For snoozed doses, surface the exact moment the
                    // fresh alarm will refire so the user isn't left
                    // guessing. Derived from actedAt + SNOOZE_MILLIS
                    // rather than a separate stored field.
                    if (row.event.status == DoseStatus.Snoozed) {
                        val until = row.event.actedAtEpochMillis +
                            MedicationReminderScheduler.SNOOZE_MILLIS
                        Text(
                            "Snoozed until ${formatClockTime(until)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            StatusPill(row.event.status)
        }
        // Action buttons are only offered for PENDING events — taken /
        // snoozed / missed rows are terminal from the user's POV.
        if (row.event.status == DoseStatus.Pending) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSnooze) { Text("Snooze 15m") }
                Spacer(Modifier.width(4.dp))
                androidx.compose.material3.FilledTonalButton(onClick = onMarkTaken) {
                    Text("Taken")
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun StatusPill(status: DoseStatus) {
    val (label, container, content) = when (status) {
        DoseStatus.Taken -> Triple(
            "Taken",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        DoseStatus.Snoozed -> Triple(
            "Snoozed",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        DoseStatus.Missed -> Triple(
            "Missed",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        DoseStatus.Pending -> Triple(
            "Pending",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

// ── formatters ──────────────────────────────────────────────────────────

internal fun formatTime(minutesOfDay: Int): String {
    val h = minutesOfDay / 60
    val m = minutesOfDay % 60
    val suffix = if (h >= 12) "PM" else "AM"
    val h12 = ((h + 11) % 12) + 1
    return String.format(Locale.getDefault(), "%d:%02d %s", h12, m, suffix)
}

internal fun formatNextFire(row: ReminderRow): String {
    val next = row.nextFire ?: return if (row.reminder.enabled) "No upcoming fire" else "Paused"
    val nextLocal = next.atZone(ZoneId.systemDefault())
    val today = LocalDate.now(ZoneId.systemDefault())
    val dayLabel = when (nextLocal.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> nextLocal.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
    val timeLabel = nextLocal.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    return "Next: $dayLabel at $timeLabel"
}

private fun formatHistoryTimestamp(epoch: Long): String {
    val local = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())
    return local.format(DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", Locale.getDefault()))
}

private fun formatClockTime(epoch: Long): String {
    val local = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())
    return local.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
}
