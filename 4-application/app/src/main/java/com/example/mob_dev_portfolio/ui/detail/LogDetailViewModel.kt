package com.example.mob_dev_portfolio.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitRepository
import com.example.mob_dev_portfolio.data.doctor.LogDoctorAnnotation
import com.example.mob_dev_portfolio.data.photo.SymptomPhoto
import com.example.mob_dev_portfolio.data.photo.SymptomPhotoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
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
    /**
     * One-shot signal telling the UI to surface an "Symptom ended — Undo"
     * snackbar after the user taps "End now". Carries the prior end-time
     * (null for the typical "was ongoing" case) so undo can restore it
     * exactly.
     */
    val justEnded: JustEndedSignal? = null,
)

data class JustEndedSignal(
    val previousEndEpochMillis: Long?,
)

class LogDetailViewModel(
    private val id: Long,
    private val repository: SymptomLogRepository,
    /**
     * Exposed so the detail screen can render the photo gallery +
     * fullscreen viewer. Nullable so tests that don't care about
     * photos (older doubles) still compile — we fall back to an empty
     * flow rather than spinning up a fake repo.
     */
    private val photoRepository: SymptomPhotoRepository? = null,
    /**
     * Doctor-visit lookup for the "cleared" / "linked to diagnosis"
     * badge shown at the top of the detail. Nullable so test doubles
     * that don't care about doctor-visit data still compile — the
     * screen falls back to rendering no badge when the repo is absent.
     */
    private val doctorVisitRepository: DoctorVisitRepository? = null,
) : ViewModel() {

    val log: StateFlow<DetailLogState> = repository.observeById(id)
        .map { loaded ->
            if (loaded == null) DetailLogState.NotFound else DetailLogState.Loaded(loaded)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), DetailLogState.Loading)

    /**
     * Photos attached to this log. Sorted by capture time (the repo's
     * DAO already returns ASC by createdAtEpochMillis) so the gallery
     * always shows oldest-first, matching the PDF report layout.
     */
    val photos: StateFlow<List<SymptomPhoto>> =
        (photoRepository?.observeForLog(id) ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Doctor-visit annotation for this log, or null. Emits
     * [LogDoctorAnnotation.Cleared] if the user has ticked this log
     * as reviewed, or [LogDoctorAnnotation.LinkedToDiagnosis] if it's
     * pinned to a diagnosis. Rendered as a tap-through badge at the
     * top of the detail body.
     */
    val doctorAnnotation: StateFlow<LogDoctorAnnotation?> =
        (doctorVisitRepository?.observeLogAnnotation(id) ?: flowOf(null))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

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

    /**
     * Quick-end action — sets `endEpochMillis` to "now" without taking
     * the user through the full editor. The previous end time (typically
     * null, since the button is only shown for ongoing logs) is carried
     * back via [JustEndedSignal] so the screen can offer Undo.
     *
     * No-op if the log isn't currently loaded or has already ended:
     * the button shouldn't be visible in those cases, but defensive
     * either way so a quick double-tap can't double-write.
     */
    fun endNow(now: Long = System.currentTimeMillis()) {
        val current = (log.value as? DetailLogState.Loaded)?.log ?: return
        if (current.endEpochMillis != null) return
        viewModelScope.launch {
            val updated = current.copy(endEpochMillis = now)
            runCatching { repository.update(updated) }
                .onSuccess {
                    _state.update {
                        it.copy(justEnded = JustEndedSignal(previousEndEpochMillis = null))
                    }
                }
        }
    }

    /**
     * Restore the symptom to its prior (ongoing) state. Wired to the
     * "Undo" action on the snackbar fired by [endNow].
     */
    fun undoEnd(signal: JustEndedSignal) {
        val current = (log.value as? DetailLogState.Loaded)?.log ?: return
        viewModelScope.launch {
            val restored = current.copy(endEpochMillis = signal.previousEndEpochMillis)
            runCatching { repository.update(restored) }
            _state.update { it.copy(justEnded = null) }
        }
    }

    fun consumeJustEnded() {
        _state.update { it.copy(justEnded = null) }
    }

    companion object {
        fun factory(id: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return LogDetailViewModel(
                    id = id,
                    repository = app.container.symptomLogRepository,
                    photoRepository = app.container.symptomPhotoRepository,
                    doctorVisitRepository = app.container.doctorVisitRepository,
                ) as T
            }
        }
    }
}
