package com.example.mob_dev_portfolio.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DetailLogState {
    data object Loading : DetailLogState
    data object NotFound : DetailLogState
    data class Loaded(val log: SymptomLog) : DetailLogState
}

data class LogDetailUiState(
    val showDeleteConfirm: Boolean = false,
    val isDeleting: Boolean = false,
    val deleted: Boolean = false,
    val deleteError: String? = null,
)

class LogDetailViewModel(
    private val id: Long,
    private val repository: SymptomLogRepository,
) : ViewModel() {

    val log: StateFlow<DetailLogState> = repository.observeById(id)
        .map<SymptomLog?, DetailLogState> { loaded ->
            if (loaded == null) DetailLogState.NotFound else DetailLogState.Loaded(loaded)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DetailLogState.Loading)

    private val _state = MutableStateFlow(LogDetailUiState())
    val state: StateFlow<LogDetailUiState> = _state.asStateFlow()

    fun requestDelete() {
        _state.update { it.copy(showDeleteConfirm = true, deleteError = null) }
    }

    fun cancelDelete() {
        _state.update { it.copy(showDeleteConfirm = false) }
    }

    fun confirmDelete() {
        if (_state.value.isDeleting) return
        _state.update { it.copy(isDeleting = true, showDeleteConfirm = false, deleteError = null) }
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onSuccess { _state.update { it.copy(isDeleting = false, deleted = true) } }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            isDeleting = false,
                            deleteError = throwable.message ?: "Couldn't delete this log. Please try again.",
                        )
                    }
                }
        }
    }

    fun retryDelete() {
        _state.update { it.copy(deleteError = null) }
        confirmDelete()
    }

    fun dismissDeleteError() {
        _state.update { it.copy(deleteError = null) }
    }

    companion object {
        fun factory(id: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return LogDetailViewModel(id, app.container.symptomLogRepository) as T
            }
        }
    }
}
