package com.example.mob_dev_portfolio.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.preferences.BiologicalSex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Dedicated profile screen for the demographic inputs the AI analysis
 * consumes: date of birth (→ age bracket) and biological sex.
 *
 * Name editing intentionally lives elsewhere — the analysis screen
 * already edits it, and duplicating the field here would multiply the
 * "am I on the right profile screen?" confusion without adding value.
 * This screen is focused on the two fields that the Gemini prompt
 * actually reads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemographicProfileScreen(
    onBack: () -> Unit,
    viewModel: DemographicProfileViewModel = viewModel(factory = DemographicProfileViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDobPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("profile_back"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Date of birth", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Used to derive an age bracket (${state.ageRangePreview}). We never send your exact DOB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { showDobPicker = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_dob_button"),
                    ) {
                        Text(
                            text = state.dateOfBirthEpochMillis
                                ?.let { dateLabelFormatter().format(Date(it)) }
                                ?: "Set date of birth",
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Biological sex", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "A coarse bucket the AI uses to contextualise vitals (e.g. typical resting heart rates differ). \"Prefer not to say\" drops the field from the prompt entirely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Column(Modifier.selectableGroup()) {
                        BiologicalSex.entries.forEach { option ->
                            SexOptionRow(
                                option = option,
                                selected = state.biologicalSex == option,
                                onSelected = { viewModel.onBiologicalSexChange(option) },
                            )
                        }
                    }
                }
            }
        }

        if (showDobPicker) {
            val dpState = rememberDatePickerState(
                initialSelectedDateMillis = state.dateOfBirthEpochMillis,
            )
            DatePickerDialog(
                onDismissRequest = { showDobPicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.onDobChange(dpState.selectedDateMillis)
                            showDobPicker = false
                        },
                    ) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showDobPicker = false }) { Text("Cancel") }
                },
            ) { DatePicker(state = dpState) }
        }
    }
}

@Composable
private fun SexOptionRow(
    option: BiologicalSex,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelected,
                role = Role.RadioButton,
            )
            .padding(vertical = 8.dp)
            .testTag("profile_sex_${option.storageKey}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = option.displayLabel,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

// Built on demand rather than as a top-level `val` so the formatter picks up
// the *current* system locale (lint's ConstantLocale warning — a user who
// changes their locale while the app is running would otherwise keep seeing
// the pre-change format).
// DOB is stored at UTC midnight by the picker, so format in UTC to avoid a
// zone flip from a device across the date line.
private fun dateLabelFormatter(): SimpleDateFormat =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
