package com.example.mob_dev_portfolio.ui.condition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.condition.HealthCondition

/**
 * Manage user-declared health conditions.
 *
 * Conditions live alongside doctor-issued diagnoses in the AI's
 * already-explained context bundle but are conceptually distinct:
 * conditions are *standing facts* about the user (chronic illness,
 * known allergy, pre-existing diagnosis), where a `DoctorDiagnosis`
 * is a *visit-specific* finding from a particular consultation.
 *
 * Visually a flat LazyColumn of cards with a FAB for "Add condition";
 * tapping a row opens an edit dialog, swipe-replaced with an icon
 * button for accessibility (screen-reader users can't reliably swipe).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthConditionsScreen(
    onBack: () -> Unit,
    viewModel: HealthConditionsViewModel = viewModel(factory = HealthConditionsViewModel.Factory),
) {
    val conditions by viewModel.conditions.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health conditions") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("conditions_back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openAdd,
                modifier = Modifier.testTag("conditions_add"),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add condition") },
            )
        },
    ) { padding ->
        if (conditions.isEmpty()) {
            EmptyConditions(modifier = Modifier.padding(padding).fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .testTag("conditions_list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    HelperCard()
                }
                items(items = conditions, key = { it.id }) { condition ->
                    ConditionRow(
                        condition = condition,
                        onEdit = { viewModel.openEdit(condition) },
                        onDelete = { viewModel.requestDelete(condition) },
                    )
                }
            }
        }
    }

    if (ui.editorOpen) {
        EditorDialog(
            isEditing = ui.editingId != null,
            name = ui.nameDraft,
            notes = ui.notesDraft,
            onNameChange = viewModel::onNameChange,
            onNotesChange = viewModel::onNotesChange,
            onDismiss = viewModel::closeEditor,
            onSave = viewModel::saveEditor,
        )
    }

    ui.showDeleteFor?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete condition?") },
            text = {
                Text(
                    "\"${target.name}\" will be removed and any symptom logs " +
                        "linked to it will go back to ungrouped. The logs themselves " +
                        "are kept.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDelete,
                    modifier = Modifier.testTag("conditions_confirm_delete"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun HelperCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "What goes here?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "Add any pre-existing diagnoses or chronic health issues " +
                    "you have — like \"Type 2 Diabetes\", \"Asthma\", or " +
                    "\"Hypothyroidism\". The AI uses these as background " +
                    "context, and you'll be able to group symptom logs under them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConditionRow(
    condition: HealthCondition,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("conditions_row_${condition.id}"),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    text = condition.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (condition.notes.isNotBlank()) {
                    Text(
                        text = condition.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.testTag("conditions_edit_${condition.id}"),
            ) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("conditions_delete_${condition.id}"),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EmptyConditions(modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("conditions_empty"), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "No conditions yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Tap \"Add condition\" to record any chronic or pre-existing " +
                    "health issues you have. They'll give the AI useful context " +
                    "and let you group symptom logs by condition.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditorDialog(
    isEditing: Boolean,
    name: String,
    notes: String,
    onNameChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.testTag("conditions_editor_dialog")) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (isEditing) "Edit condition" else "Add condition",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Condition name") },
                    placeholder = { Text("e.g. Type 2 Diabetes") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("conditions_editor_name"),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = onNotesChange,
                    label = { Text("Notes (optional)") },
                    placeholder = { Text("Any extra detail — diagnosed when, medications, etc.") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .testTag("conditions_editor_notes"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        onClick = onSave,
                        enabled = name.isNotBlank(),
                        modifier = Modifier.testTag("conditions_editor_save"),
                    ) { Text(if (isEditing) "Save" else "Add") }
                }
            }
        }
    }
}
