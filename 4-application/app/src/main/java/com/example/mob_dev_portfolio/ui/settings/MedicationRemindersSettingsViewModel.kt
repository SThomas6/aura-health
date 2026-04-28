package com.example.mob_dev_portfolio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.medication.MedicationRepository
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import com.example.mob_dev_portfolio.reminders.MedicationReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the global "medication reminders" switch on Settings
 * (FR-MR-08).
 *
 * Flipping off cancels every armed AlarmManager alarm so no stale
 * fire-times linger — the schedules themselves stay intact in Room
 * so flipping back on restores behaviour without data loss. This
 * ViewModel mirrors the biometric-lock VM's shape so the two sit
 * side-by-side in SettingsScreen without bespoke plumbing.
 */
class MedicationRemindersSettingsViewModel(
    private val prefs: UiPreferencesRepository,
    private val repository: MedicationRepository,
    private val scheduler: MedicationReminderScheduler,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = prefs.medicationRemindersEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), true)

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setMedicationRemindersEnabled(enabled)
            val reminders = repository.listAll()
            if (enabled) scheduler.rescheduleAll(reminders)
            else scheduler.cancelAll(reminders.map { it.id })
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return MedicationRemindersSettingsViewModel(
                    prefs = app.container.uiPreferencesRepository,
                    repository = app.container.medicationRepository,
                    scheduler = app.container.medicationReminderScheduler,
                ) as T
            }
        }
    }
}
