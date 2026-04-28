package com.example.mob_dev_portfolio.ui.doctor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.doctor.DoctorDiagnosis
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only view of a single visit. Mirrors the editor sections so the
 * user can scan what's stored before deciding whether to edit or
 * delete. Tapping a symptom row deep-links to the log detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorVisitDetailScreen(
    visitId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenLog: (Long) -> Unit,
    onDeleted: () -> Unit,
    viewModel: DoctorVisitDetailViewModel = viewModel(factory = DoctorVisitDetailViewModel.factory(visitId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    state.detail?.let { detail ->
                        IconButton(
                            onClick = { onEdit(detail.visit.id) },
                            modifier = Modifier.testTag("doctor_detail_edit"),
                        ) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                        IconButton(
                            onClick = { pendingDelete = true },
                            modifier = Modifier.testTag("doctor_detail_delete"),
                        ) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        when {
            !state.loaded -> {
                Box(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            state.detail == null -> {
                Box(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text("Visit not found.") }
            }
            else -> {
                val detail = state.detail!!
                VisitDetailBody(
                    detail = detail,
                    logsById = state.logsById,
                    onOpenLog = onOpenLog,
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("doctor_detail_body"),
                )
            }
        }
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete this visit?") },
            text = {
                Text(
                    "Linked symptoms will be un-cleared and their diagnosis tags will be removed. " +
                        "The AI will start considering those logs again on the next analysis.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = false
                        viewModel.delete(onDeleted)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun VisitDetailBody(
    detail: DoctorVisitDetail,
    logsById: Map<Long, SymptomLog>,
    onOpenLog: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        VisitHeader(detail)
        HorizontalDivider()
        ClearedLogsSection(detail.coveredLogIds, logsById, onOpenLog)
        HorizontalDivider()
        DiagnosesSection(detail.diagnoses, logsById, onOpenLog)
    }
}

@Composable
private fun VisitHeader(detail: DoctorVisitDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = detail.visit.doctorName.ifBlank { "Unknown doctor" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = formatDate(detail.visit.visitDateEpochMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (detail.visit.summary.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail.visit.summary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ClearedLogsSection(
    ids: List<Long>,
    logsById: Map<Long, SymptomLog>,
    onOpenLog: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Reviewed & cleared",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "The AI fully ignores these in future analyses.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ids.isEmpty()) {
            Text(
                text = "No symptoms marked as cleared.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            ids.mapNotNull(logsById::get).forEach { log ->
                SymptomRow(log = log, onClick = { onOpenLog(log.id) })
            }
        }
    }
}

@Composable
private fun DiagnosesSection(
    diagnoses: List<DoctorDiagnosis>,
    logsById: Map<Long, SymptomLog>,
    onOpenLog: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Issues raised",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "The AI treats linked symptoms as already-explained context.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (diagnoses.isEmpty()) {
            Text(
                text = "No issues flagged.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            diagnoses.forEach { diagnosis ->
                DiagnosisCard(diagnosis, logsById, onOpenLog)
            }
        }
    }
}

@Composable
private fun DiagnosisCard(
    diagnosis: DoctorDiagnosis,
    logsById: Map<Long, SymptomLog>,
    onOpenLog: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = diagnosis.label.ifBlank { "(unlabelled issue)" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (diagnosis.notes.isNotBlank()) {
                Text(
                    text = diagnosis.notes,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val linked = diagnosis.linkedLogIds.mapNotNull(logsById::get)
            if (linked.isEmpty()) {
                Text(
                    text = "No related symptoms linked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                linked.forEach { log ->
                    SymptomRow(log = log, onClick = { onOpenLog(log.id) })
                }
            }
        }
    }
}

@Composable
private fun SymptomRow(log: SymptomLog, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
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
    }
}

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DATE_FMT)
