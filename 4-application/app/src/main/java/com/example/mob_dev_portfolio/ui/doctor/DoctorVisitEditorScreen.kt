package com.example.mob_dev_portfolio.ui.doctor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.SymptomLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorVisitEditorScreen(
    visitId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: DoctorVisitEditorViewModel = viewModel(factory = DoctorVisitEditorViewModel.factory(visitId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf<LogPickerTarget?>(null) }

    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (visitId == null) "New visit" else "Edit visit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.doctorName,
                onValueChange = viewModel::updateDoctorName,
                label = { Text("Doctor's name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("doctor_editor_name"),
            )

            DatePickerField(
                valueEpochMillis = state.visitDateEpochMillis,
                onClick = { showDatePicker = true },
            )

            OutlinedTextField(
                value = state.summary,
                onValueChange = viewModel::updateSummary,
                label = { Text("What did the doctor say?") },
                placeholder = { Text("e.g. Reviewed headaches; no cause for concern.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .testTag("doctor_editor_summary"),
            )

            SectionHeader(
                title = "Symptoms the doctor has cleared",
                subtitle = "The AI will completely ignore these.",
            )
            CoveredLogsPanel(
                state = state,
                onAdd = { pickerTarget = LogPickerTarget.Cleared },
                onRemove = { viewModel.toggleCoveredLog(it) },
            )

            HorizontalDivider()

            SectionHeader(
                title = "Issues the doctor flagged",
                subtitle = "Link the symptoms that relate to each issue. The AI will treat linked " +
                    "symptoms as already-explained.",
            )

            state.diagnoses.forEach { row ->
                DiagnosisEditor(
                    row = row,
                    logs = state.allLogs.filterNot { it.id in state.coveredLogIds },
                    linkedLogIds = row.linkedLogIds,
                    onLabelChange = { viewModel.updateDiagnosisLabel(row.rowKey, it) },
                    onNotesChange = { viewModel.updateDiagnosisNotes(row.rowKey, it) },
                    onPickLogs = { pickerTarget = LogPickerTarget.Diagnosis(row.rowKey) },
                    onUnlinkLog = { viewModel.toggleDiagnosisLog(row.rowKey, it) },
                    onRemove = { viewModel.removeDiagnosis(row.rowKey) },
                )
            }

            TextButton(
                onClick = viewModel::addDiagnosis,
                modifier = Modifier.testTag("doctor_editor_add_diagnosis"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add another issue")
            }

            state.error?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = state.canSave && !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("doctor_editor_save"),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                } else {
                    Text(if (state.id == 0L) "Save visit" else "Save changes")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.visitDateEpochMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let(viewModel::updateVisitDate)
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    pickerTarget?.let { target ->
        LogPickerSheet(
            target = target,
            state = state,
            onDismiss = { pickerTarget = null },
            onToggle = { logId ->
                when (target) {
                    LogPickerTarget.Cleared -> viewModel.toggleCoveredLog(logId)
                    is LogPickerTarget.Diagnosis -> viewModel.toggleDiagnosisLog(target.rowKey, logId)
                }
            },
        )
    }
}

@Composable
private fun DatePickerField(
    valueEpochMillis: Long,
    onClick: () -> Unit,
) {
    OutlinedTextField(
        value = formatDate(valueEpochMillis),
        onValueChange = {},
        label = { Text("Visit date") },
        readOnly = true,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("doctor_editor_visit_date"),
        trailingIcon = {
            TextButton(onClick = onClick) { Text("Change") }
        },
    )
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoveredLogsPanel(
    state: DoctorVisitEditorUiState,
    onAdd: () -> Unit,
    onRemove: (Long) -> Unit,
) {
    val coveredLogs = state.allLogs.filter { it.id in state.coveredLogIds }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("doctor_editor_cleared_panel"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (coveredLogs.isEmpty()) {
                Text(
                    text = "None yet. Tap \"Pick symptoms\" to add any the doctor reviewed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                coveredLogs.forEach { log ->
                    LogLinkRow(log = log, onRemove = { onRemove(log.id) })
                }
            }
            TextButton(
                onClick = onAdd,
                modifier = Modifier.testTag("doctor_editor_pick_cleared"),
            ) { Text("Pick symptoms") }
        }
    }
}

@Composable
private fun DiagnosisEditor(
    row: DiagnosisFormRow,
    logs: List<SymptomLog>,
    linkedLogIds: Set<Long>,
    onLabelChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onPickLogs: () -> Unit,
    onUnlinkLog: (Long) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("doctor_editor_diagnosis_${row.rowKey}"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Issue",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove issue")
                }
            }
            OutlinedTextField(
                value = row.label,
                onValueChange = onLabelChange,
                label = { Text("Label") },
                placeholder = { Text("e.g. Chronic migraine") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = row.notes,
                onValueChange = onNotesChange,
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
            )
            Text(
                text = "Related symptoms",
                style = MaterialTheme.typography.labelLarge,
            )
            val linked = logs.filter { it.id in linkedLogIds }
            if (linked.isEmpty()) {
                Text(
                    text = "None linked yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                linked.forEach { log ->
                    LogLinkRow(log = log, onRemove = { onUnlinkLog(log.id) })
                }
            }
            TextButton(onClick = onPickLogs) { Text("Link symptoms") }
        }
    }
}

@Composable
private fun LogLinkRow(log: SymptomLog, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = log.symptomName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = formatDate(log.startEpochMillis) + " · severity ${log.severity}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogPickerSheet(
    target: LogPickerTarget,
    state: DoctorVisitEditorUiState,
    onDismiss: () -> Unit,
    onToggle: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title = when (target) {
        LogPickerTarget.Cleared -> "Select cleared symptoms"
        is LogPickerTarget.Diagnosis -> "Select related symptoms"
    }
    // For the diagnosis picker, exclude logs that are already in the
    // cleared set — the form's invariant is "a log lives in one
    // bucket at a time", and showing a cleared log under the link
    // picker invites the user to create a conflicting selection.
    val selectable = when (target) {
        LogPickerTarget.Cleared -> state.allLogs
        is LogPickerTarget.Diagnosis -> state.allLogs.filterNot { it.id in state.coveredLogIds }
    }
    val selectedIds: Set<Long> = when (target) {
        LogPickerTarget.Cleared -> state.coveredLogIds
        is LogPickerTarget.Diagnosis ->
            state.diagnoses.firstOrNull { it.rowKey == target.rowKey }?.linkedLogIds.orEmpty()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (selectable.isEmpty()) {
                Text(
                    text = "You haven't logged any symptoms yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 500.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(selectable, key = { it.id }) { log ->
                        SelectableLogRow(
                            log = log,
                            selected = log.id in selectedIds,
                            onToggle = { onToggle(log.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) { Text("Done") }
        }
    }
}

/**
 * Picker row with a leading square checkbox. The whole row is tappable —
 * the checkbox mirrors the row state (onCheckedChange = null) so taps are
 * handled once at the Surface level, which also gives us the ripple and
 * accessibility role for free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectableLogRow(
    log: SymptomLog,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val subtextColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.symptomName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = formatDate(log.startEpochMillis) + " · severity ${log.severity}",
                    style = MaterialTheme.typography.bodySmall,
                    color = subtextColor,
                )
            }
        }
    }
}

private sealed interface LogPickerTarget {
    data object Cleared : LogPickerTarget
    data class Diagnosis(val rowKey: Long) : LogPickerTarget
}

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DATE_FMT)
