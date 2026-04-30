package com.example.mob_dev_portfolio.ui.doctor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.doctor.DoctorDiagnosisDraft
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitDraft
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Create/edit state for [DoctorVisitEditorScreen].
 *
 * A single-state data class instead of a fine-grained viewmodel-with-handlers
 * because the form has a predictable, linear shape: one visit, a flat set of
 * covered-log ids, and a list of diagnoses each with its own linked-log set.
 * The "add diagnosis" / "remove diagnosis" actions operate on the list at
 * the top of [diagnoses] via copy-update semantics; nothing is persisted
 * until [DoctorVisitEditorViewModel.save] fires.
 */
data class DoctorVisitEditorUiState(
    val id: Long = 0L,
    val doctorName: String = "",
    val visitDateEpochMillis: Long = System.currentTimeMillis(),
    val summary: String = "",
    val coveredLogIds: Set<Long> = emptySet(),
    val diagnoses: List<DiagnosisFormRow> = emptyList(),
    val allLogs: List<SymptomLog> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean
        get() = doctorName.isNotBlank() && diagnoses.all { it.label.isNotBlank() }
}

/**
 * Editor row for a single in-flight diagnosis. [rowKey] is a monotonic
 * client-side id (separate from the database id) so the UI can render a
 * stable LazyColumn key even before the row has been persisted — the
 * zero-id fallback from the entity would collide across multiple new
 * diagnoses in the same session.
 */
data class DiagnosisFormRow(
    val rowKey: Long,
    val id: Long = 0L,
    val label: String = "",
    val notes: String = "",
    val linkedLogIds: Set<Long> = emptySet(),
)

class DoctorVisitEditorViewModel(
    private val visitId: Long?,
    private val repository: DoctorVisitRepository,
    private val symptomLogRepository: SymptomLogRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DoctorVisitEditorUiState(loading = visitId != null))
    val state: StateFlow<DoctorVisitEditorUiState> = _state.asStateFlow()

    private var nextRowKey = 1L

    /**
     * Stream of every saved symptom log, lifted as a StateFlow so the
     * picker bottom-sheets can render immediately when opened. The
     * reactive update is a nice-to-have — logs don't change mid-edit
     * under normal use — but it means a log inserted in a parallel
     * flow would appear without re-entering the editor.
     */
    private val logsFlow: StateFlow<List<SymptomLog>> = symptomLogRepository
        .observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    init {
        // Keep the form's log list synced with the repository without
        // making every derived computation re-stateIn.
        viewModelScope.launch {
            logsFlow.collect { logs ->
                _state.value = _state.value.copy(allLogs = logs)
            }
        }

        if (visitId != null) {
            viewModelScope.launch {
                // First emission is the current DB row; that's all we
                // need to hydrate the form. Subsequent edits are driven
                // by the user, not a background writer.
                val detail = repository.observeVisitDetail(visitId).first()
                if (detail == null) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "Visit not found.",
                    )
                    return@launch
                }
                val rows = detail.diagnoses.map { d ->
                    DiagnosisFormRow(
                        rowKey = nextRowKey++,
                        id = d.id,
                        label = d.label,
                        notes = d.notes,
                        linkedLogIds = d.linkedLogIds.toSet(),
                    )
                }
                _state.value = _state.value.copy(
                    id = detail.visit.id,
                    doctorName = detail.visit.doctorName,
                    visitDateEpochMillis = detail.visit.visitDateEpochMillis,
                    summary = detail.visit.summary,
                    coveredLogIds = detail.coveredLogIds.toSet(),
                    diagnoses = rows,
                    loading = false,
                )
            }
        }
    }

    /** Updates the doctor-name field and clears any previous save-error banner. */
    fun updateDoctorName(value: String) {
        _state.value = _state.value.copy(doctorName = value, error = null)
    }

    /** Updates the visit date — set from the DatePickerDialog. */
    fun updateVisitDate(epochMillis: Long) {
        _state.value = _state.value.copy(visitDateEpochMillis = epochMillis)
    }

    /** Updates the free-text summary the user can paste from the consult. */
    fun updateSummary(value: String) {
        _state.value = _state.value.copy(summary = value)
    }

    fun toggleCoveredLog(logId: Long) {
        val current = _state.value
        val next = if (current.coveredLogIds.contains(logId)) {
            current.coveredLogIds - logId
        } else {
            // If the log was previously linked to a diagnosis in this
            // form, remove it from that list too. The backend will
            // resolve conflicts cleared-wins on read, but keeping the
            // form internally consistent stops confusing UI states.
            current.coveredLogIds + logId
        }
        _state.value = current.copy(
            coveredLogIds = next,
            diagnoses = if (!current.coveredLogIds.contains(logId) && next.contains(logId)) {
                current.diagnoses.map { row ->
                    if (row.linkedLogIds.contains(logId)) {
                        row.copy(linkedLogIds = row.linkedLogIds - logId)
                    } else row
                }
            } else current.diagnoses,
        )
    }

    /** Appends a fresh diagnosis row with a monotonic row key. */
    fun addDiagnosis() {
        val current = _state.value
        _state.value = current.copy(
            diagnoses = current.diagnoses + DiagnosisFormRow(rowKey = nextRowKey++),
        )
    }

    /** Drops the diagnosis row identified by [rowKey] from the in-memory list. */
    fun removeDiagnosis(rowKey: Long) {
        val current = _state.value
        _state.value = current.copy(
            diagnoses = current.diagnoses.filterNot { it.rowKey == rowKey },
        )
    }

    /** Updates the label field on a specific diagnosis row. */
    fun updateDiagnosisLabel(rowKey: Long, value: String) {
        mutateDiagnosis(rowKey) { it.copy(label = value) }
    }

    /** Updates the doctor's-notes field on a specific diagnosis row. */
    fun updateDiagnosisNotes(rowKey: Long, value: String) {
        mutateDiagnosis(rowKey) { it.copy(notes = value) }
    }

    fun toggleDiagnosisLog(rowKey: Long, logId: Long) {
        val current = _state.value
        // If the log is currently in the cleared set, flip it out —
        // same "clearest user intent" logic as toggleCoveredLog: a
        // log can only be in one bucket at a time within the form.
        val nextCovered = if (current.coveredLogIds.contains(logId)) {
            current.coveredLogIds - logId
        } else current.coveredLogIds
        _state.value = current.copy(
            coveredLogIds = nextCovered,
            diagnoses = current.diagnoses.map { row ->
                if (row.rowKey != rowKey) row
                else if (row.linkedLogIds.contains(logId))
                    row.copy(linkedLogIds = row.linkedLogIds - logId)
                else
                    row.copy(linkedLogIds = row.linkedLogIds + logId)
            },
        )
    }

    private fun mutateDiagnosis(rowKey: Long, block: (DiagnosisFormRow) -> DiagnosisFormRow) {
        val current = _state.value
        _state.value = current.copy(
            diagnoses = current.diagnoses.map { if (it.rowKey == rowKey) block(it) else it },
        )
    }

    /**
     * Persists the current draft as a [DoctorVisitDraft] via the
     * repository. Guarded by [DoctorVisitEditorUiState.canSave] (doctor
     * name + every diagnosis label non-blank) and the in-flight `saving`
     * flag so a rapid double-tap can't race two writes against the same
     * row. On success the screen pops via the `saved` flag observed by
     * the host composable.
     */
    fun save() {
        val current = _state.value
        if (!current.canSave || current.saving) return
        _state.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            val draft = DoctorVisitDraft(
                id = current.id,
                doctorName = current.doctorName.trim(),
                visitDateEpochMillis = current.visitDateEpochMillis,
                summary = current.summary.trim(),
                coveredLogIds = current.coveredLogIds,
                diagnoses = current.diagnoses.map { row ->
                    DoctorDiagnosisDraft(
                        id = row.id,
                        label = row.label.trim(),
                        notes = row.notes.trim(),
                        linkedLogIds = row.linkedLogIds,
                    )
                },
            )
            runCatching { repository.saveVisit(draft) }
                .onSuccess { _state.value = _state.value.copy(saving = false, saved = true) }
                .onFailure { t ->
                    _state.value = _state.value.copy(
                        saving = false,
                        error = "Could not save: ${t.message ?: "unexpected error"}",
                    )
                }
        }
    }

    companion object {
        fun factory(visitId: Long?): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return DoctorVisitEditorViewModel(
                    visitId = visitId,
                    repository = app.container.doctorVisitRepository,
                    symptomLogRepository = app.container.symptomLogRepository,
                ) as T
            }
        }
    }
}
