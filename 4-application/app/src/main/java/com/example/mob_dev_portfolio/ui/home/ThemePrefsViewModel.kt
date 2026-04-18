package com.example.mob_dev_portfolio.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.preferences.ThemeMode
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ThemeWriteError(
    val attempted: ThemeMode,
    val message: String,
)

class ThemePrefsViewModel(
    private val repository: UiPreferencesRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = repository.preferences
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ThemeMode.System)

    private val _error = MutableStateFlow<ThemeWriteError?>(null)
    val error: StateFlow<ThemeWriteError?> = _error.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            runCatching { repository.setThemeMode(mode) }
                .onFailure { throwable ->
                    _error.update {
                        ThemeWriteError(
                            attempted = mode,
                            message = throwable.message ?: "Couldn't save appearance preference.",
                        )
                    }
                }
        }
    }

    fun retryLastError() {
        _error.value?.attempted?.let { setThemeMode(it) }
    }

    fun dismissError() {
        _error.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return ThemePrefsViewModel(app.container.uiPreferencesRepository) as T
            }
        }
    }
}
