package com.example.mob_dev_portfolio.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.ai.AnalysisResult
import com.example.mob_dev_portfolio.data.ai.AnalysisService
import com.example.mob_dev_portfolio.data.ai.NetworkConnectivity
import com.example.mob_dev_portfolio.data.preferences.UserProfile
import com.example.mob_dev_portfolio.data.preferences.UserProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * States the analysis screen can be in.
 *
 * A sealed hierarchy (rather than a bag of nullable fields) so the UI pattern
 * matches once and Compose recomposes only on meaningful transitions.
 */
sealed interface AnalysisPhase {
    data object Idle : AnalysisPhase
    data object Loading : AnalysisPhase
    data class Success(val summaryText: String) : AnalysisPhase
}

data class AnalysisUiState(
    /**
     * Source of truth for what's in the name TextField RIGHT NOW.
     *
     * We deliberately do NOT observe [UserProfileRepository.profile] and
     * mirror its emissions back into state — that would create an async
     * round-trip on every keystroke (field → DataStore write → flow emit →
     * state update → field re-render) and let a late emission race ahead of
     * the next keystroke, making the cursor jump and characters re-order
     * ("Steffan" becoming "teffanS"). We load once on init, edit synchronously
     * from here on, and fire-and-forget persistence writes.
     */
    val fullName: String = "",
    val dobEpochMillis: Long? = null,
    val userContext: String = "",
    val phase: AnalysisPhase = AnalysisPhase.Idle,
    /**
     * Non-blocking errors funneled into the Snackbar channel — we reuse the
     * same pattern as [com.example.mob_dev_portfolio.ui.log.LogSymptomViewModel]
     * so users get a consistent error experience across the app.
     */
    val transientError: String? = null,
)

class AnalysisViewModel(
    private val profileRepository: UserProfileRepository,
    private val logRepository: SymptomLogRepository,
    private val analysisService: AnalysisService,
    private val connectivity: NetworkConnectivity,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(AnalysisUiState())
    val state: StateFlow<AnalysisUiState> = _state.asStateFlow()

    init {
        // Prime the field once from DataStore, then never observe again. Any
        // future repo emissions are ignored on purpose — see the KDoc on
        // [AnalysisUiState.fullName] for the rationale.
        viewModelScope.launch {
            val initial = runCatching { profileRepository.profile.first() }.getOrNull()
                ?: UserProfile()
            _state.update {
                it.copy(
                    fullName = initial.fullName.orEmpty(),
                    dobEpochMillis = initial.dateOfBirthEpochMillis,
                )
            }
        }
    }

    fun onUserContextChange(value: String) {
        _state.update { it.copy(userContext = value) }
    }

    fun onFullNameChange(value: String) {
        // Synchronous UI update first — the field re-renders with the user's
        // actual typed value without waiting for DataStore.
        _state.update { it.copy(fullName = value) }
        viewModelScope.launch { profileRepository.setFullName(value) }
    }

    fun onDateOfBirthChange(epochMillis: Long?) {
        _state.update { it.copy(dobEpochMillis = epochMillis) }
        viewModelScope.launch { profileRepository.setDateOfBirth(epochMillis) }
    }

    fun onTransientErrorShown() {
        _state.update { it.copy(transientError = null) }
    }

    /**
     * Kicks off an analysis.
     *
     * **Offline guard (AC):** we check connectivity BEFORE touching OkHttp
     * so the "no network" path is free of any socket activity. This is the
     * path asserted by the ViewModel unit test — the fake client's call
     * counter must stay at zero when connectivity is false.
     *
     * Once the guard passes we flip to [AnalysisPhase.Loading] synchronously
     * (on the caller's dispatcher) so the UI's loading indicator appears in
     * the same frame as the tap. The actual analysis work happens on
     * [ioDispatcher].
     */
    fun triggerAnalysis() {
        if (_state.value.phase is AnalysisPhase.Loading) {
            // Double-tap guard — don't kick off two in parallel.
            return
        }
        if (!connectivity.isOnline()) {
            _state.update {
                it.copy(transientError = "No internet — connect and try again.")
            }
            return
        }
        _state.update { it.copy(phase = AnalysisPhase.Loading, transientError = null) }
        viewModelScope.launch {
            val snapshot = _state.value
            val profile = UserProfile(
                fullName = snapshot.fullName.takeIf { it.isNotBlank() },
                dateOfBirthEpochMillis = snapshot.dobEpochMillis,
            )
            val logs = runCatching { logRepository.observeAll().first() }.getOrDefault(emptyList())
            val result = withContext(ioDispatcher) {
                analysisService.analyze(profile, snapshot.userContext, logs)
            }
            _state.update { current ->
                when (result) {
                    is AnalysisResult.Success -> current.copy(
                        phase = AnalysisPhase.Success(result.summaryText),
                        transientError = null,
                    )
                    is AnalysisResult.NoNetwork -> current.copy(
                        phase = AnalysisPhase.Idle,
                        transientError = "Lost connection before we heard back — try again.",
                    )
                    is AnalysisResult.Timeout -> current.copy(
                        phase = AnalysisPhase.Idle,
                        transientError = "AI took too long to respond — try again.",
                    )
                    is AnalysisResult.ApiError -> current.copy(
                        phase = AnalysisPhase.Idle,
                        transientError = result.message,
                    )
                }
            }
        }
    }

    private inline fun MutableStateFlow<AnalysisUiState>.update(transform: (AnalysisUiState) -> AnalysisUiState) {
        value = transform(value)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return AnalysisViewModel(
                    profileRepository = app.container.userProfileRepository,
                    logRepository = app.container.symptomLogRepository,
                    analysisService = app.container.analysisService,
                    connectivity = app.container.networkConnectivity,
                ) as T
            }
        }
    }
}
