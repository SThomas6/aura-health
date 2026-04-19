package com.example.mob_dev_portfolio.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

private val DateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailScreen(
    id: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onDeleted: () -> Unit,
    viewModel: LogDetailViewModel = viewModel(factory = LogDetailViewModel.factory(id)),
) {
    val log by viewModel.log.collectAsStateWithLifecycle()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(ui.deleted) {
        if (ui.deleted) onDeleted()
    }

    LaunchedEffect(ui.deleteError) {
        ui.deleteError?.let { message ->
            val result = snackbarHost.showSnackbar(
                message = message,
                actionLabel = "Retry",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.retryDelete()
            } else {
                viewModel.dismissDeleteError()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log details") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        when (val current = log) {
            is DetailLogState.Loading ->
                LoadingState(modifier = Modifier.padding(padding).fillMaxSize())
            is DetailLogState.NotFound ->
                if (ui.deleted) {
                    Spacer(Modifier.padding(padding))
                } else {
                    NotFoundState(
                        onBack = onBack,
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    )
                }
            is DetailLogState.Loaded -> DetailContent(
                log = current.log,
                isDeleting = ui.isDeleting,
                onEdit = { onEdit(id) },
                onDelete = viewModel::requestDelete,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (ui.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Delete this log?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDelete,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("btn_confirm_delete"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::cancelDelete,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("btn_cancel_delete"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("dialog_delete_confirm"),
        )
    }
}

@Composable
private fun LoadingState(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NotFoundState(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("detail_not_found"), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                "Log unavailable",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "This entry has been deleted or could not be found.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 48.dp).testTag("btn_not_found_back"),
            ) { Text("Go back") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    log: SymptomLog,
    isDeleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(log.startEpochMillis).atZone(zone).toLocalDateTime()
    val end = log.endEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("detail_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    log.symptomName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("detail_symptom"),
                )
                Text(
                    "Severity ${log.severity}/10",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        DetailRow(label = "Description", value = log.description, testTag = "detail_description")
        DetailRow(label = "Started", value = start.format(DateTimeFormat), testTag = "detail_start")
        end?.let {
            DetailRow(label = "Ended", value = it.format(DateTimeFormat), testTag = "detail_end")
        }
        if (log.medication.isNotBlank()) {
            DetailRow(label = "Medication", value = log.medication, testTag = "detail_medication")
        }
        if (log.contextTags.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Context",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    log.contextTags.forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag) }, enabled = false)
                    }
                }
            }
        }
        if (log.notes.isNotBlank()) {
            DetailRow(label = "Notes", value = log.notes, testTag = "detail_notes")
        }
        if (log.locationLatitude != null && log.locationLongitude != null) {
            // Raw coordinates are intentionally NOT rendered — we show only the
            // pre-computed place name. If the DB row is null (pre-migration or
            // failed geocoding), the "Location unavailable" fallback is shown.
            DetailRow(
                label = "Approximate location",
                value = log.locationName?.takeIf { it.isNotBlank() } ?: "Location unavailable",
                testTag = "detail_location",
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onEdit,
            enabled = !isDeleting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("btn_edit"),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Edit log")
        }

        OutlinedButton(
            onClick = onDelete,
            enabled = !isDeleting,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("btn_delete"),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isDeleting) "Deleting…" else "Delete log")
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String, testTag: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).testTag(testTag),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
