package com.example.mob_dev_portfolio.ui.condition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.condition.HealthCondition
import com.example.mob_dev_portfolio.data.condition.HealthConditionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lightweight VM for the conditions list. Hosts a small editor sheet
 * state so add/edit can share the same Compose dialog without nav
 * churn — entering this screen is already a settings drill-down, we
 * don't want a second push for adding a 2-field condition.
 */
data class HealthConditionsUiState(
    val editorOpen: Boolean = false,
    /** Non-null when editing an existing row; null for "add new". */
    val editingId: Long? = null,
    val nameDraft: String = "",
    val notesDraft: String = "",
    val showDeleteFor: HealthCondition? = null,
)

class HealthConditionsViewModel(
    private val repository: HealthConditionRepository,
) : ViewModel() {

    val conditions: StateFlow<List<HealthCondition>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _ui = MutableStateFlow(HealthConditionsUiState())
    val ui: StateFlow<HealthConditionsUiState> = _ui.asStateFlow()

    /** Opens the editor in "create" mode — no row id, blank fields. */
    fun openAdd() {
        _ui.value = HealthConditionsUiState(editorOpen = true)
    }

    /**
     * Opens the editor seeded with [condition]'s current values. The
     * `editingId` discriminates create vs update at save time so the
     * dialog doesn't need separate codepaths for each mode.
     */
    fun openEdit(condition: HealthCondition) {
        _ui.value = HealthConditionsUiState(
            editorOpen = true,
            editingId = condition.id,
            nameDraft = condition.name,
            notesDraft = condition.notes,
        )
    }

    fun closeEditor() {
        _ui.update { it.copy(editorOpen = false) }
    }

    fun onNameChange(value: String) {
        _ui.update { it.copy(nameDraft = value) }
    }

    fun onNotesChange(value: String) {
        _ui.update { it.copy(notesDraft = value) }
    }

    /**
     * Persists the current draft. A blank name is treated as a no-op rather
     * than an error — the Save button is also disabled in that case, so the
     * guard exists for completeness only. `editingId == null` triggers an
     * insert (we pass 0L to let Room auto-assign); a non-null id updates
     * the existing row in place.
     */
    fun saveEditor() {
        val current = _ui.value
        if (current.nameDraft.isBlank()) return
        viewModelScope.launch {
            repository.upsert(
                id = current.editingId ?: 0L,
                name = current.nameDraft,
                notes = current.notesDraft,
            )
            _ui.value = HealthConditionsUiState()
        }
    }

    fun requestDelete(condition: HealthCondition) {
        _ui.update { it.copy(showDeleteFor = condition) }
    }

    fun cancelDelete() {
        _ui.update { it.copy(showDeleteFor = null) }
    }

    fun confirmDelete() {
        val target = _ui.value.showDeleteFor ?: return
        viewModelScope.launch {
            repository.delete(target.id)
            _ui.update { it.copy(showDeleteFor = null) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as AuraApplication
                return HealthConditionsViewModel(
                    repository = app.container.healthConditionRepository,
                ) as T
            }
        }
    }
}
