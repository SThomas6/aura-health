package com.example.mob_dev_portfolio.ui.doctor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.doctor.DoctorVisit
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Top-level Doctor-Visits list. The screen shows a card per visit
 * sorted newest-first; each card surfaces the doctor's name, the visit
 * date, and a one-line summary preview. Delete is handled here (rather
 * than on the detail screen) because row-level delete is the fastest
 * path for "clean up an accidental duplicate".
 *
 * [DoctorListUiState.loaded] distinguishes "still collecting" from
 * "collected, zero rows" so the screen shows a spinner on first frame
 * and the empty-state copy only after the Flow has emitted.
 */
data class DoctorListUiState(
    val visits: List<DoctorVisit> = emptyList(),
    val loaded: Boolean = false,
)

class DoctorListViewModel(
    private val repository: DoctorVisitRepository,
) : ViewModel() {

    val state: StateFlow<DoctorListUiState> = repository
        .observeVisits()
        .map { DoctorListUiState(visits = it, loaded = true) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = DoctorListUiState(),
        )

    /**
     * Fire-and-forget delete of a visit. The repository's underlying Room
     * cascade un-clears any linked symptom logs and removes diagnosis tags,
     * so the AI starts considering those logs again on the next analysis —
     * the confirm-dialog copy on the screen warns the user about exactly
     * that side effect.
     */
    fun deleteVisit(id: Long) {
        viewModelScope.launch {
            repository.deleteVisit(id)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return DoctorListViewModel(
                    repository = app.container.doctorVisitRepository,
                ) as T
            }
        }
    }
}
