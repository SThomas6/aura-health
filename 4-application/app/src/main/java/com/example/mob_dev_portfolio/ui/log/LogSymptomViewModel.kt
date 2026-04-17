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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LogSymptomUiState(
    val draft: LogDraft = LogDraft(),
    val errors: Map<LogField, String> = emptyMap(),
    val showErrorBanner: Boolean = false,
    val isSaving: Boolean = false,
    val savedConfirmation: String? = null,
)

class LogSymptomViewModel(
    private val repository: SymptomLogRepository,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _state = MutableStateFlow(LogSymptomUiState(draft = LogDraft(startEpochMillis = nowProvider())))
    val state: StateFlow<LogSymptomUiState> = _state.asStateFlow()

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
            repository.save(
                SymptomLog(
                    symptomName = draft.symptomName.trim(),
                    description = draft.description.trim(),
                    startEpochMillis = draft.startEpochMillis,
                    endEpochMillis = if (draft.hasEnded) draft.endEpochMillis else null,
                    severity = draft.severity,
                    medication = draft.medication.trim(),
                    contextTags = draft.contextTags.toList().sorted(),
                    notes = draft.notes.trim(),
                    createdAtEpochMillis = nowProvider(),
                )
            )
            _state.update {
                it.copy(
                    isSaving = false,
                    draft = LogDraft(startEpochMillis = nowProvider()),
                    savedConfirmation = "Log saved",
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
    }
}
