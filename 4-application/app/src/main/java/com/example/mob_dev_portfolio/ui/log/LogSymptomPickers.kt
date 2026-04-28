package com.example.mob_dev_portfolio.ui.log

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.mob_dev_portfolio.data.condition.HealthCondition
import com.example.mob_dev_portfolio.data.doctor.DoctorDiagnosis

/**
 * Two thin presentational dropdown pickers split out from
 * [LogSymptomScreen] to keep the screen file under control. They have
 * no Flow / VM dependencies — purely "given a list and a selection,
 * render an exposed dropdown" composables.
 *
 * Kept in the same package as the screen (`ui.log`) and marked
 * `internal` so the screen can keep referring to them by short name
 * without exposing them more broadly.
 */

/**
 * Picker that lets the user link a symptom log to one of the
 * doctor-confirmed diagnoses they've recorded. "None" leaves the log
 * un-linked. Used by the Symptom Editor form when there's at least
 * one diagnosis on file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosisPicker(
    diagnoses: List<DoctorDiagnosis>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = remember(selectedId, diagnoses) {
        diagnoses.firstOrNull { it.id == selectedId }?.label
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel?.ifBlank { "(unlabelled issue)" } ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text("Diagnosis link") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth()
                .testTag("field_diagnosis_link"),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
                modifier = Modifier.testTag("diagnosis_option_none"),
            )
            diagnoses.forEach { diagnosis ->
                DropdownMenuItem(
                    text = { Text(diagnosis.label.ifBlank { "(unlabelled issue)" }) },
                    onClick = {
                        expanded = false
                        onSelect(diagnosis.id)
                    },
                    modifier = Modifier.testTag("diagnosis_option_${diagnosis.id}"),
                )
            }
        }
    }
}

/**
 * Mirror of [DiagnosisPicker] for user-declared health conditions —
 * chronic / pre-existing issues like "Type 2 Diabetes" the user added
 * during onboarding or via the Conditions settings screen.
 *
 * Two separate composables (rather than a generic one) because the
 * domain models live in different modules and the test tags differ —
 * keeping them parallel makes the rendering trivial to read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthConditionPicker(
    conditions: List<HealthCondition>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = remember(selectedId, conditions) {
        conditions.firstOrNull { it.id == selectedId }?.name
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text("Health condition") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth()
                .testTag("field_condition_link"),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
                modifier = Modifier.testTag("condition_option_none"),
            )
            conditions.forEach { condition ->
                DropdownMenuItem(
                    text = { Text(condition.name) },
                    onClick = {
                        expanded = false
                        onSelect(condition.id)
                    },
                    modifier = Modifier.testTag("condition_option_${condition.id}"),
                )
            }
        }
    }
}
