package com.example.mob_dev_portfolio.ui.doctor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitDetail
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Read-only detail screen for a single doctor visit. Drives the UI
 * that shows cleared logs, diagnoses + their linked symptoms, and
 * offers edit/delete actions.
 *
 * Combines the visit Flow with the symptom-log list so every cleared /
 * linked id the detail references can be rendered with its human
 * readable symptom name and date — the ids alone aren't useful to the
 * user.
 */
data class DoctorVisitDetailUiState(
    val detail: DoctorVisitDetail? = null,
    val logsById: Map<Long, SymptomLog> = emptyMap(),
    val loaded: Boolean = false,
)

class DoctorVisitDetailViewModel(
    private val visitId: Long,
    private val repository: DoctorVisitRepository,
    symptomLogRepository: SymptomLogRepository,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DoctorVisitDetailUiState> = combine(
        repository.observeVisitDetail(visitId),
        symptomLogRepository.observeAll(),
    ) { detail, logs ->
        DoctorVisitDetailUiState(
            detail = detail,
            logsById = logs.associateBy { it.id },
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DoctorVisitDetailUiState(),
    )

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteVisit(visitId)
            onDone()
        }
    }

    companion object {
        fun factory(visitId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return DoctorVisitDetailViewModel(
                    visitId = visitId,
                    repository = app.container.doctorVisitRepository,
                    symptomLogRepository = app.container.symptomLogRepository,
                ) as T
            }
        }
    }
}
