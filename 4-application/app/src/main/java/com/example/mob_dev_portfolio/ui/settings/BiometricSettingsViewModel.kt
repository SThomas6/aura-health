package com.example.mob_dev_portfolio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import com.example.mob_dev_portfolio.security.AppLockController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the biometric-lock toggle on the Settings screen.
 *
 * The DataStore-backed flag is already a Flow, so the ViewModel's main
 * job is the *toggle-on side-effect*: when the user switches the gate
 * on from inside an already-running session, we call
 * [AppLockController.markUnlocked] so the lock screen doesn't
 * immediately take over their UI (they already proved presence by
 * being able to flip the toggle). When they switch it off, the
 * controller stays in whatever state it's in — the gate composable
 * isn't going to be shown anyway, since the pref is false.
 */
class BiometricSettingsViewModel(
    private val repository: UiPreferencesRepository,
    private val lockController: AppLockController,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = repository.biometricLockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setBiometricLockEnabled(enabled)
            if (enabled) {
                // User is present — skip the lock-screen bounce that
                // would otherwise fire on the next recomposition.
                lockController.markUnlocked()
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return BiometricSettingsViewModel(
                    repository = app.container.uiPreferencesRepository,
                    lockController = app.container.appLockController,
                ) as T
            }
        }
    }
}
