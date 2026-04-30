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
 * Unified picker for the symptom editor's "Group under condition" field.
 *
 * Originally we had two separate pickers — one for doctor-confirmed
 * diagnoses, one for user-declared health conditions — because the
 * underlying domain models live in different packages. Users found the
 * pair confusing: from their perspective both fields ask the same
 * question ("which of my standing health things does this log belong
 * to?"), even though the underlying data is sourced differently.
 *
 * The two are merged into a single dropdown here. The schema-level
 * distinction is preserved (each entry carries its origin) so the
 * AI pipeline can still annotate logs with the more specific
 * "doctor-confirmed diagnosis" semantic where applicable, but the
 * editor never asks the user to pick between two near-identical fields.
 */

/**
 * Sealed unified-grouping model surfaced to the editor. Each arm
 * carries enough metadata for the picker to render itself (label) and
 * for the save path to resolve back to the right repository call.
 *
 * The composite [id] is stable across recompositions and unique across
 * the diagnosis/condition union, so it can drive a single
 * `selectedGroupingId: String?` field on the editor's UI state without
 * the schema-level `Long` ids of the two underlying tables ever
 * colliding.
 */
sealed interface LogGrouping {
    /** Stable composite id ("diag:<dbId>" or "cond:<dbId>"). */
    val id: String
    /** What the user sees in the dropdown. */
    val displayName: String

    data class Diagnosis(
        val diagnosisId: Long,
        val label: String,
    ) : LogGrouping {
        override val id: String = "diag:$diagnosisId"
        override val displayName: String = label.ifBlank { "(unlabelled issue)" }
    }

    data class Condition(
        val conditionId: Long,
        val name: String,
    ) : LogGrouping {
        override val id: String = "cond:$conditionId"
        override val displayName: String = name
    }

    companion object {
        /** Compose the merged picker list — conditions first, then diagnoses. */
        fun build(
            conditions: List<HealthCondition>,
            diagnoses: List<DoctorDiagnosis>,
        ): List<LogGrouping> = buildList {
            conditions.forEach { add(Condition(conditionId = it.id, name = it.name)) }
            diagnoses.forEach { add(Diagnosis(diagnosisId = it.id, label = it.label)) }
        }

        /** Resolve a picker id back to the source diagnosis db id, if any. */
        fun diagnosisIdFor(groupingId: String?): Long? =
            groupingId?.takeIf { it.startsWith("diag:") }
                ?.substringAfter("diag:")
                ?.toLongOrNull()

        /** Resolve a picker id back to the source condition db id, if any. */
        fun conditionIdFor(groupingId: String?): Long? =
            groupingId?.takeIf { it.startsWith("cond:") }
                ?.substringAfter("cond:")
                ?.toLongOrNull()
    }
}

/**
 * Single flat dropdown that reads from the merged [LogGrouping] list.
 *
 * Originally this picker had two sub-sections ("Conditions" and
 * "Doctor diagnoses") with sub-headers, on the theory that surfacing
 * provenance helps the user. In practice the user wanted one true
 * field — diagnoses and conditions belong to the same mental concept
 * ("which standing health thing does this log relate to?") and the
 * sub-headers re-introduced the very split the merge was supposed to
 * remove. So we render a single flat list. Conditions first, then
 * diagnoses (the order [LogGrouping.build] produces).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupingPicker(
    groupings: List<LogGrouping>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = remember(selectedId, groupings) {
        groupings.firstOrNull { it.id == selectedId }?.displayName
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text("Condition") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth()
                .testTag("field_grouping_link"),
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
                modifier = Modifier.testTag("grouping_option_none"),
            )
            groupings.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.displayName) },
                    onClick = {
                        expanded = false
                        onSelect(item.id)
                    },
                    modifier = Modifier.testTag("grouping_option_${item.id}"),
                )
            }
        }
    }
}
