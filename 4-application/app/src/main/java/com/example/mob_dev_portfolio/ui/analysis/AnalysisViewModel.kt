package com.example.mob_dev_portfolio.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.work.WorkInfo
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.ai.AnalysisGuidance
import com.example.mob_dev_portfolio.data.ai.AnalysisResultStore
import com.example.mob_dev_portfolio.data.ai.NetworkConnectivity
import com.example.mob_dev_portfolio.data.ai.StoredAnalysis
import com.example.mob_dev_portfolio.data.preferences.UserProfile
import com.example.mob_dev_portfolio.data.preferences.UserProfileRepository
import com.example.mob_dev_portfolio.work.AnalysisScheduler
import com.example.mob_dev_portfolio.work.AnalysisWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * States the analysis screen can be in.
 *
 * A sealed hierarchy (rather than a bag of nullable fields) so the UI pattern
 * matches once and Compose recomposes only on meaningful transitions.
 */
sealed interface AnalysisPhase {
    data object Idle : AnalysisPhase
    data object Loading : AnalysisPhase
    data class Success(
        val summaryText: String,
        val guidance: AnalysisGuidance,
        val completedAtEpochMillis: Long,
    ) : AnalysisPhase
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

/**
 * ViewModel for the AI analysis "run" form.
 *
 * Coordinates three async sources behind a single [AnalysisUiState]:
 *   1. [UserProfileRepository] — read once at init to seed the form, then
 *      written through with fire-and-forget setters on every keystroke.
 *      We deliberately don't subscribe to the repo flow afterwards (see
 *      [AnalysisUiState.fullName]) to keep the field cursor stable.
 *   2. [AnalysisResultStore] — the latest stored Gemini result. Drives the
 *      Success phase so a user returning from a notification tap (or
 *      simply re-opening the app) sees the most recent summary without
 *      having to re-run.
 *   3. [AnalysisScheduler] (`WorkManager`) — emits per-attempt `WorkInfo`
 *      so we can render Loading while a background worker is in flight,
 *      and surface failure messages without polling.
 */
class AnalysisViewModel(
    private val profileRepository: UserProfileRepository,
    private val resultStore: AnalysisResultStore,
    private val scheduler: AnalysisScheduler,
    private val connectivity: NetworkConnectivity,
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

        // Observe the result store for analyses that LAND DURING THIS
        // VM session — i.e. the worker we just kicked off (or one
        // already in flight when the screen opened) finishing and
        // writing its summary.
        //
        // `drop(1)` skips the initial emission, which is the cached
        // result from prior runs. Without that drop, opening the
        // screen would paint the most recent past analysis straight
        // into the Success phase — making "Run AI analysis" feel like
        // a results page rather than a fresh-start surface. Users who
        // want to revisit prior results go to the analysis history
        // screen; this surface is just for kicking off a new run.
        //
        // When the worker completes mid-session, the second emission
        // (the new result) flows through as usual: if we're already
        // Loading, the WorkInfo SUCCEEDED branch below paints Success
        // from the same store; if we're somehow Idle (race recovery,
        // weekly background worker landing while the user idles on the
        // screen), the else branch here paints it directly.
        viewModelScope.launch {
            resultStore.latest.drop(1).collect { stored ->
                if (stored != null) {
                    _state.update { current ->
                        // Don't stomp Loading: if a worker is still running,
                        // the previous stored summary is stale; let
                        // the WorkInfo observer own the phase until the
                        // worker completes and re-writes the store.
                        if (current.phase is AnalysisPhase.Loading) current
                        else current.copy(phase = stored.toSuccess())
                    }
                }
            }
        }

        // Observe the unique worker's state stream. RUNNING / ENQUEUED map
        // to Loading; terminal states are handled by the store observer
        // above (success) or by the failure mapping here (failure).
        viewModelScope.launch {
            scheduler.currentWorkInfos().collect { infos ->
                val latest = infos.maxByOrNull { it.runAttemptCount } ?: return@collect
                when (latest.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED,
                    -> _state.update { it.copy(phase = AnalysisPhase.Loading) }

                    WorkInfo.State.FAILED -> {
                        val message = failureMessageFrom(latest)
                        _state.update { current ->
                            current.copy(
                                phase = if (current.phase is AnalysisPhase.Loading) AnalysisPhase.Idle else current.phase,
                                transientError = message,
                            )
                        }
                    }

                    WorkInfo.State.SUCCEEDED,
                    WorkInfo.State.CANCELLED,
                    -> {
                        // SUCCEEDED is handled by the store observer; we
                        // only care to drop Loading if we're still showing
                        // it (edge case: store emission arrived first but
                        // we were in Loading and got blocked).
                        if (_state.value.phase is AnalysisPhase.Loading) {
                            val stored = runCatching { resultStore.latest.first() }.getOrNull()
                            _state.update {
                                it.copy(
                                    phase = stored?.toSuccess() ?: AnalysisPhase.Idle,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Updates the free-text "additional context" field — purely in-memory. */
    fun onUserContextChange(value: String) {
        _state.update { it.copy(userContext = value) }
    }

    /**
     * Updates the in-memory full-name field and writes through to the
     * persisted profile. The synchronous state update keeps the cursor
     * stable; the DataStore write is fire-and-forget on viewModelScope.
     */
    fun onFullNameChange(value: String) {
        // Synchronous UI update first — the field re-renders with the user's
        // actual typed value without waiting for DataStore.
        _state.update { it.copy(fullName = value) }
        viewModelScope.launch { profileRepository.setFullName(value) }
    }

    /**
     * Stores the picked DOB as epoch millis. Same write-through pattern as
     * the name field — sync state update, async persistence write.
     */
    fun onDateOfBirthChange(epochMillis: Long?) {
        _state.update { it.copy(dobEpochMillis = epochMillis) }
        viewModelScope.launch { profileRepository.setDateOfBirth(epochMillis) }
    }

    /** Clears the snackbar one-shot once the host has displayed the message. */
    fun onTransientErrorShown() {
        _state.update { it.copy(transientError = null) }
    }

    /**
     * Enqueues a background [AnalysisWorker].
     *
     * **Offline guard (AC):** we check connectivity BEFORE scheduling so
     * the "no network" path is free of any WorkManager activity. The worker
     * itself re-checks at start — if the user came back online between tap
     * and worker start, the retry still runs — but this fast-path gives us
     * an immediate snackbar without waiting for the worker to spin up.
     *
     * The actual analysis happens in a OS-managed coroutine and survives
     * the app being backgrounded or killed.
     */
    fun triggerAnalysis() {
        if (_state.value.phase is AnalysisPhase.Loading) {
            // Double-tap guard — don't kick off two in parallel. REPLACE
            // policy on the scheduler would handle this safely anyway, but
            // suppressing here keeps the Loading indicator steady.
            return
        }
        if (!connectivity.isOnline()) {
            _state.update {
                it.copy(transientError = "No internet — connect and try again.")
            }
            return
        }
        // Paint Loading immediately. The WorkInfo observer will reconcile
        // once WorkManager picks up our request.
        _state.update { it.copy(phase = AnalysisPhase.Loading, transientError = null) }
        scheduler.enqueue(_state.value.userContext)
    }

    private fun failureMessageFrom(info: WorkInfo): String {
        val kind = info.outputData.getString(AnalysisWorker.KEY_FAILURE_KIND)
        val message = info.outputData.getString(AnalysisWorker.KEY_FAILURE_MESSAGE)
        return when (kind) {
            AnalysisWorker.FAILURE_TIMEOUT -> "AI took too long to respond — try again."
            AnalysisWorker.FAILURE_NO_NETWORK -> "Lost connection before we heard back — try again."
            AnalysisWorker.FAILURE_API_ERROR -> message ?: "AI analysis failed — try again."
            else -> "Analysis couldn't finish — try again."
        }
    }

    private fun StoredAnalysis.toSuccess(): AnalysisPhase.Success =
        AnalysisPhase.Success(
            summaryText = summaryText,
            guidance = guidance,
            completedAtEpochMillis = completedAtEpochMillis,
        )

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return AnalysisViewModel(
                    profileRepository = app.container.userProfileRepository,
                    resultStore = app.container.analysisResultStore,
                    scheduler = app.container.analysisScheduler,
                    connectivity = app.container.networkConnectivity,
                ) as T
            }
        }
    }
}
