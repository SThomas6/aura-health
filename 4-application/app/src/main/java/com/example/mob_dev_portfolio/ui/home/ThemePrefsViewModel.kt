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

/**
 * Captures a failed attempt to persist a theme preference so the UI can
 * surface a retryable snackbar. Holds [attempted] (so retry can replay
 * the user's intent without re-prompting them) and a user-readable
 * [message] derived from the underlying DataStore exception.
 */
data class ThemeWriteError(
    val attempted: ThemeMode,
    val message: String,
)

/**
 * ViewModel for the appearance switcher exposed in Settings and shared
 * with any composable that needs to reflect the current [ThemeMode]
 * (e.g. the Home screen's preview tile).
 *
 * Lives separately from the larger SettingsViewModel because the theme
 * mode is also read by the Application-level theme provider — keeping
 * its surface narrow makes it cheap to scope to the activity without
 * pulling unrelated settings state along with it.
 */
class ThemePrefsViewModel(
    private val repository: UiPreferencesRepository,
) : ViewModel() {

    /**
     * Current theme mode. Falls back to [ThemeMode.System] until the
     * persisted preference has been read so the UI never paints with a
     * wrong-but-stale assumption on cold start.
     */
    val themeMode: StateFlow<ThemeMode> = repository.preferences
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ThemeMode.System)

    private val _error = MutableStateFlow<ThemeWriteError?>(null)
    /** `null` when no write has failed; latest failure otherwise. */
    val error: StateFlow<ThemeWriteError?> = _error.asStateFlow()

    /**
     * Persist a new theme mode. On failure (rare — DataStore is local)
     * the error is captured into [error] so the UI can show a retryable
     * snackbar rather than swallowing the problem silently.
     */
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

    /** Re-attempts the last failed write, if any. No-op when [error] is null. */
    fun retryLastError() {
        _error.value?.attempted?.let { setThemeMode(it) }
    }

    /** Clears any pending error without retrying — invoked when the user dismisses the snackbar. */
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
