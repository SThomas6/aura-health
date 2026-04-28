package com.example.mob_dev_portfolio.ui.doctor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import com.example.mob_dev_portfolio.data.doctor.DoctorVisit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Doctor Visits top-level screen. Lists every logged visit (newest
 * first) and offers a FAB to add a new one. Tapping a card opens the
 * detail screen; the inline delete icon shows a confirmation dialog
 * before cascading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorListScreen(
    onAddVisit: () -> Unit,
    onOpenVisit: (Long) -> Unit,
    viewModel: DoctorListViewModel = viewModel(factory = DoctorListViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<DoctorVisit?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Doctor visits") },
                modifier = Modifier.testTag("doctor_list_top_bar"),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddVisit,
                modifier = Modifier.testTag("doctor_list_fab_add"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Log a doctor visit")
            }
        },
    ) { padding ->
        when {
            !state.loaded -> {
                Box(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            state.visits.isEmpty() -> {
                EmptyDoctorListState(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .testTag("doctor_list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.visits, key = { "visit-${it.id}" }) { visit ->
                        DoctorVisitCard(
                            visit = visit,
                            onClick = { onOpenVisit(visit.id) },
                            onDelete = { pendingDelete = visit },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete visit with ${target.doctorName}?") },
            text = {
                Text(
                    "Linked symptoms will be un-cleared and their diagnosis tags will be removed. " +
                        "The AI will start considering those logs again on the next analysis.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteVisit(target.id)
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DoctorVisitCard(
    visit: DoctorVisit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("doctor_visit_card_${visit.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = visit.doctorName.ifBlank { "Unknown doctor" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = formatVisitDate(visit.visitDateEpochMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (visit.summary.isNotBlank()) {
                    Text(
                        text = visit.summary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("doctor_visit_delete_${visit.id}"),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete visit",
                )
            }
        }
    }
}

@Composable
private fun EmptyDoctorListState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.MedicalServices,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "No visits logged yet",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "Log a doctor visit to tell the AI which symptoms have already been reviewed, " +
                "and to pin any diagnoses your clinician flagged.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private val VISIT_DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy")

private fun formatVisitDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(VISIT_DATE_FMT)
