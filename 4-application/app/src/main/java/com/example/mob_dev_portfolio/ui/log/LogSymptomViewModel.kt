package com.example.mob_dev_portfolio.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface EditLoadState {
    data object NotEditing : EditLoadState
    data object Loading : EditLoadState
    data object Loaded : EditLoadState
    data object NotFound : EditLoadState
    data class Failed(val message: String) : EditLoadState
}

data class LogSymptomUiState(
    val draft: LogDraft = LogDraft(),
    val errors: Map<LogField, String> = emptyMap(),
    val showErrorBanner: Boolean = false,
    val isSaving: Boolean = false,
    val savedConfirmation: String? = null,
    val transientError: String? = null,
    val editingId: Long = 0L,
    val isLoading: Boolean = false,
    val editLoadState: EditLoadState = EditLoadState.NotEditing,
)

class LogSymptomViewModel(
    private val repository: SymptomLogRepository,
    private val editingId: Long = 0L,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _state = MutableStateFlow(
        LogSymptomUiState(
            draft = LogDraft(startEpochMillis = nowProvider()),
            editingId = editingId,
            isLoading = editingId != 0L,
            editLoadState = if (editingId != 0L) EditLoadState.Loading else EditLoadState.NotEditing,
        ),
    )
    val state: StateFlow<LogSymptomUiState> = _state.asStateFlow()

    init {
        if (editingId != 0L) {
            loadForEditing()
        }
    }

    private fun loadForEditing() {
        _state.update {
            it.copy(
                isLoading = true,
                editLoadState = EditLoadState.Loading,
            )
        }
        viewModelScope.launch {
            val outcome = runCatching { repository.observeById(editingId).first() }
            outcome.fold(
                onSuccess = { existing ->
                    if (existing != null) {
                        _state.update {
                            it.copy(
                                draft = existing.toDraft(),
                                isLoading = false,
                                editLoadState = EditLoadState.Loaded,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                editLoadState = EditLoadState.NotFound,
                            )
                        }
                    }
                },
                onFailure = { throwable ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            editLoadState = EditLoadState.Failed(
                                throwable.message ?: "Couldn't load this log. Check your connection and try again.",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun retryLoadForEditing() {
        if (editingId != 0L) loadForEditing()
    }

    fun onSymptomNameChange(value: String) = updateDraft(LogField.SymptomName) { copy(symptomName = value) }
    fun onDescriptionChange(value: String) = updateDraft(LogField.Description) { copy(description = value) }
    fun onStartDateTimeChange(epochMillis: Long) = updateDraft(LogField.StartDateTime) { copy(startEpochMillis = epochMillis) }
    fun onHasEndedChange(hasEnded: Boolean) = updateDraft(LogField.EndDateTime) {
        copy(
            hasEnded = hasEnded,
            endEpochMillis = if (hasEnded) (endEpochMillis ?: nowProvider()) else null,
        )
    }
    fun onEndDateTimeChange(epochMillis: Long) = updateDraft(LogField.EndDateTime) { copy(endEpochMillis = epochMillis) }
    fun onSeverityChange(value: Int) = updateDraft(LogField.Severity) { copy(severity = value.coerceIn(LogValidator.MIN_SEVERITY, LogValidator.MAX_SEVERITY)) }
    fun onMedicationChange(value: String) = updateDraft(null) { copy(medication = value) }
    fun onNotesChange(value: String) = updateDraft(null) { copy(notes = value) }
    fun onToggleTag(tag: String) = updateDraft(null) {
        copy(contextTags = if (contextTags.contains(tag)) contextTags - tag else contextTags + tag)
    }

    fun onConfirmationShown() {
        _state.update { it.copy(savedConfirmation = null) }
    }

    fun onTransientErrorShown() {
        _state.update { it.copy(transientError = null) }
    }

    fun onErrorBannerDismissed() {
        _state.update { it.copy(showErrorBanner = false) }
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        val result = LogValidator.validate(current.draft, now = nowProvider())
        if (!result.isValid) {
            _state.update {
                it.copy(errors = result.errors, showErrorBanner = true)
            }
            return
        }

        _state.update { it.copy(isSaving = true, errors = emptyMap(), showErrorBanner = false) }
        viewModelScope.launch {
            val draft = current.draft
            val isEditing = current.editingId != 0L
            val log = SymptomLog(
                id = current.editingId,
                symptomName = draft.symptomName.trim(),
                description = draft.description.trim(),
                startEpochMillis = draft.startEpochMillis,
                endEpochMillis = if (draft.hasEnded) draft.endEpochMillis else null,
                severity = draft.severity,
                medication = draft.medication.trim(),
                contextTags = draft.contextTags.toList().sorted(),
                notes = draft.notes.trim(),
                createdAtEpochMillis = if (isEditing) draft.createdAtEpochMillis else nowProvider(),
            )
            val saveOutcome = runCatching {
                if (isEditing) {
                    val rowsUpdated = repository.update(log)
                    rowsUpdated > 0
                } else {
                    repository.save(log)
                    true
                }
            }
            if (saveOutcome.isFailure) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        transientError = saveOutcome.exceptionOrNull()?.message
                            ?: "Couldn't save this log. Please try again.",
                    )
                }
                return@launch
            }
            if (saveOutcome.getOrNull() == false) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        transientError = "This log no longer exists — changes couldn't be saved.",
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    isSaving = false,
                    draft = if (isEditing) draft else LogDraft(startEpochMillis = nowProvider()),
                    savedConfirmation = if (isEditing) "Log updated" else "Log saved",
                )
            }
            onSaved()
        }
    }

    private inline fun updateDraft(field: LogField?, transform: LogDraft.() -> LogDraft) {
        _state.update { current ->
            val newDraft = current.draft.transform()
            val newErrors = if (field != null) current.errors - field else current.errors
            current.copy(draft = newDraft, errors = newErrors)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return LogSymptomViewModel(app.container.symptomLogRepository) as T
            }
        }

        fun editFactory(id: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return LogSymptomViewModel(app.container.symptomLogRepository, editingId = id) as T
            }
        }
    }
}

private fun SymptomLog.toDraft(): LogDraft = LogDraft(
    symptomName = symptomName,
    description = description,
    startEpochMillis = startEpochMillis,
    hasEnded = endEpochMillis != null,
    endEpochMillis = endEpochMillis,
    severity = severity,
    medication = medication,
    contextTags = contextTags.toSet(),
    notes = notes,
    createdAtEpochMillis = createdAtEpochMillis,
)
