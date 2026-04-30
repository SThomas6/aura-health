package com.example.mob_dev_portfolio.ui.log

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitRepository
import com.example.mob_dev_portfolio.data.doctor.LogDoctorAnnotation
import com.example.mob_dev_portfolio.data.environment.EnvironmentalFetchResult
import com.example.mob_dev_portfolio.data.environment.EnvironmentalService
import com.example.mob_dev_portfolio.data.environment.EnvironmentalSnapshot
import com.example.mob_dev_portfolio.data.location.CoordinateRounding
import com.example.mob_dev_portfolio.data.location.LocationProvider
import com.example.mob_dev_portfolio.data.location.LocationResult
import com.example.mob_dev_portfolio.data.location.ReverseGeocoder
import com.example.mob_dev_portfolio.data.photo.SymptomPhoto
import com.example.mob_dev_portfolio.data.photo.SymptomPhotoRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * State machine for the edit-mode load lifecycle.
 *
 * The composable uses this to decide which empty-state to render
 * (`Loading` spinner, `NotFound` "deleted" panel, `Failed` retryable
 * banner) without juggling a flat set of nullable booleans. `NotEditing`
 * is the initial state when the screen is opened in create mode and the
 * loader never runs.
 */
sealed interface EditLoadState {
    data object NotEditing : EditLoadState
    data object Loading : EditLoadState
    data object Loaded : EditLoadState
    data object NotFound : EditLoadState
    data class Failed(val message: String) : EditLoadState
}

/**
 * Single-source-of-truth UI state for the symptom editor.
 *
 * Bundling everything into one [data class] (rather than a soup of
 * separate StateFlows) keeps the StateFlow contract tight: every render
 * sees a coherent snapshot of draft + errors + permission flags + photo
 * lists, with no chance of one half of the screen showing pre-update
 * data while the other half is post-update.
 *
 * One-shot signals like [savedConfirmation], [transientError] and
 * [shouldRequestLocationPermission] are nulled out by the screen after
 * being consumed (via the `on*Shown`/`on*Consumed` handlers) so they
 * can't fire twice across recompositions.
 */
data class LogSymptomUiState(
    val draft: LogDraft = LogDraft(),
    val errors: Map<LogField, String> = emptyMap(),
    val showErrorBanner: Boolean = false,
    val isSaving: Boolean = false,
    val savedConfirmation: String? = null,
    val transientError: String? = null,
    val editingId: Long = 0L,
    val isLoading: Boolean = false,
    val editLoadState: EditLoadState = EditLoadState.NotEditing,
    /** True once the user has granted the COARSE location permission this session. */
    val locationPermissionGranted: Boolean = false,
    /**
     * Set when the toggle is switched on while permission is not granted — the
     * screen observes this signal to launch the system permission dialog.
     */
    val shouldRequestLocationPermission: Boolean = false,
    /**
     * Photos already committed to the encrypted store and attached to the
     * log being edited. Empty in create-mode because the log has no id yet
     * — pending photos live in [pendingPhotoUris] until save flushes them
     * through the attach pipeline.
     */
    val attachedPhotos: List<SymptomPhoto> = emptyList(),
    /**
     * Photos the user picked this session (camera or gallery) that haven't
     * been committed yet. On save we iterate this list and call
     * [SymptomPhotoRepository.addFromUri] for each one; the resulting
     * [SymptomPhoto] rows show up via the observe subscription.
     */
    val pendingPhotoUris: List<Uri> = emptyList(),
    /** True when CAMERA has been granted this session. */
    val cameraPermissionGranted: Boolean = false,
    /**
     * Drives the rationale dialog shown after the user denies CAMERA —
     * FR-PA-08 says gallery must still work, so the rationale explains
     * the fallback rather than blocking them.
     */
    val showCameraDeniedRationale: Boolean = false,
    /**
     * Composite id of the [LogGrouping] this log is grouped under, or
     * null when unlinked. The picker treats user-declared conditions
     * and doctor-confirmed diagnoses as a single mutually-exclusive
     * dropdown — the underlying schema still keeps them in separate
     * tables, but the editor surfaces them as one field.
     *
     * Encoding: `"diag:<id>"` for a diagnosis, `"cond:<id>"` for a
     * condition, parsed by the helpers on [LogGrouping].
     *
     * On edit it's hydrated from whichever link exists, with the
     * diagnosis taking precedence if (defensively) both are present.
     */
    val selectedGroupingId: String? = null,
)

/**
 * ViewModel powering the Symptom Editor (`LogSymptomScreen`) for both
 * "create new" and "edit existing" flows.
 *
 * Two factories ([Factory] and [editFactory]) cover the two entry
 * points; `editingId == 0L` distinguishes create from edit so the same
 * class can host both paths without duplicating the form state.
 *
 * Most external dependencies are nullable so the existing test doubles
 * compile without wiring every subsystem — the production [Factory]
 * supplies the full set, but unit tests can omit, e.g., the doctor-visit
 * repository and assert the form path in isolation.
 *
 * The [save] orchestration is the heart of this class: it captures
 * location at save time (never earlier — see save-time-only contract in
 * NFR-PR-04), kicks off a 5-second environmental fetch with an
 * authoritative timeout, persists the row, then flushes pending photos
 * and reconciles diagnosis/condition links. Each step degrades to a
 * non-blocking warning if it fails so the user's primary intent (saving
 * the log) is never blocked by an environmental hiccup.
 */
class LogSymptomViewModel(
    private val repository: SymptomLogRepository,
    private val locationProvider: LocationProvider? = null,
    private val reverseGeocoder: ReverseGeocoder? = null,
    private val environmentalService: EnvironmentalService? = null,
    private val symptomPhotoRepository: SymptomPhotoRepository? = null,
    /**
     * Optional so test doubles that don't exercise the doctor-visit
     * path compile without wiring a fake — the picker just renders
     * empty and [save] no-ops on the attach call.
     */
    private val doctorVisitRepository: DoctorVisitRepository? = null,
    /**
     * Optional user-declared health-condition repo. Same nullability
     * rationale as [doctorVisitRepository] — older test doubles don't
     * exercise the conditions feature, so an absent repo just means
     * the picker renders empty and [save] no-ops on the link call.
     */
    private val healthConditionRepository: com.example.mob_dev_portfolio.data.condition.HealthConditionRepository? = null,
    private val editingId: Long = 0L,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val environmentalTimeoutMillis: Long = ENVIRONMENTAL_FETCH_TIMEOUT_MILLIS,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LogSymptomUiState(
            draft = LogDraft(startEpochMillis = nowProvider()),
            editingId = editingId,
            isLoading = editingId != 0L,
            editLoadState = if (editingId != 0L) EditLoadState.Loading else EditLoadState.NotEditing,
        ),
    )
    val state: StateFlow<LogSymptomUiState> = _state.asStateFlow()

    /**
     * Merged "Group under condition" picker source — combines user-
     * declared conditions and doctor-confirmed diagnoses into a single
     * list the editor renders as one dropdown.
     *
     * The two underlying flows live in different repositories and the
     * domain models are deliberately distinct (one carries visit
     * provenance, the other doesn't), but from the user's perspective
     * the editor question is the same: "which standing health thing
     * does this log belong to?". Combining them client-side keeps the
     * schema honest while removing the duplicate UI.
     */
    val groupings: StateFlow<List<LogGrouping>> = combine(
        healthConditionRepository?.observeAll() ?: flowOf(emptyList()),
        doctorVisitRepository?.observeAllDiagnoses() ?: flowOf(emptyList()),
    ) { conditions, diagnoses ->
        LogGrouping.build(conditions = conditions, diagnoses = diagnoses)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    init {
        if (editingId != 0L) {
            loadForEditing()
            observeAttachedPhotos(editingId)
            loadExistingGrouping(editingId)
        }
    }

    /**
     * Subscribe to the encrypted-photo repo for this log so new attachments
     * (added mid-edit) show up in the editor strip immediately, and
     * remove actions reflect without a manual refresh. Silently no-ops
     * when the repo isn't wired (older test doubles, headless unit tests).
     */
    private fun observeAttachedPhotos(id: Long) {
        val repo = symptomPhotoRepository ?: return
        viewModelScope.launch {
            repo.observeForLog(id).collect { photos ->
                _state.update { it.copy(attachedPhotos = photos) }
            }
        }
    }

    private fun loadForEditing() {
        _state.update {
            it.copy(
                isLoading = true,
                editLoadState = EditLoadState.Loading,
            )
        }
        viewModelScope.launch {
            val outcome = runCatching { repository.observeById(editingId).first() }
            outcome.fold(
                onSuccess = { existing ->
                    if (existing != null) {
                        _state.update {
                            it.copy(
                                draft = existing.toDraft(),
                                isLoading = false,
                                editLoadState = EditLoadState.Loaded,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                editLoadState = EditLoadState.NotFound,
                            )
                        }
                    }
                },
                onFailure = { throwable ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            editLoadState = EditLoadState.Failed(
                                throwable.message ?: "Couldn't load this log. Check your connection and try again.",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun retryLoadForEditing() {
        if (editingId != 0L) loadForEditing()
    }

    /**
     * Read whichever grouping link the log being edited currently has
     * — diagnosis or condition — and seed [LogSymptomUiState.selectedGroupingId]
     * so the merged picker opens in sync with the DB.
     *
     * If (defensively) both links exist (the schema permits it; the
     * editor enforces mutual exclusivity from this commit forward, but
     * pre-merge data may have both set), the diagnosis wins because
     * doctor-confirmed provenance is the more specific signal. The
     * next save will normalise the row by clearing the loser.
     *
     * A cleared log counts as "not linked" for picker purposes —
     * clearing is an explicit signal the log is done, so we don't
     * resurface a tag that would invite reinterpretation.
     */
    private fun loadExistingGrouping(id: Long) {
        viewModelScope.launch {
            val diagnosisId = doctorVisitRepository?.let { repo ->
                val linked = runCatching { repo.observeLogAnnotation(id).first() }.getOrNull()
                (linked as? LogDoctorAnnotation.LinkedToDiagnosis)?.diagnosis?.id
            }
            val groupingId = if (diagnosisId != null) {
                "diag:$diagnosisId"
            } else {
                val conditionId = healthConditionRepository?.let { repo ->
                    runCatching { repo.observeConditionForLog(id).first() }.getOrNull()?.id
                }
                conditionId?.let { "cond:$it" }
            }
            _state.update { it.copy(selectedGroupingId = groupingId) }
        }
    }

    /**
     * Picker change handler for the merged grouping dropdown. Passing
     * null unlinks the log on save (clears any prior diagnosis AND any
     * prior condition link). Selecting a value swaps to the new link
     * and clears the other-side link — guaranteeing a log lives under
     * at most one grouping at a time.
     */
    fun onSelectGrouping(groupingId: String?) {
        _state.update { it.copy(selectedGroupingId = groupingId) }
    }

    fun onSymptomNameChange(value: String) = updateDraft(LogField.SymptomName) { copy(symptomName = value) }
    fun onDescriptionChange(value: String) = updateDraft(LogField.Description) { copy(description = value) }
    fun onStartDateTimeChange(epochMillis: Long) = updateDraft(LogField.StartDateTime) { copy(startEpochMillis = epochMillis) }
    fun onHasEndedChange(hasEnded: Boolean) = updateDraft(LogField.EndDateTime) {
        copy(
            hasEnded = hasEnded,
            endEpochMillis = if (hasEnded) (endEpochMillis ?: nowProvider()) else null,
        )
    }
    fun onEndDateTimeChange(epochMillis: Long) = updateDraft(LogField.EndDateTime) { copy(endEpochMillis = epochMillis) }
    fun onSeverityChange(value: Int) = updateDraft(LogField.Severity) { copy(severity = value.coerceIn(LogValidator.MIN_SEVERITY, LogValidator.MAX_SEVERITY)) }
    fun onMedicationChange(value: String) = updateDraft(null) { copy(medication = value) }
    fun onNotesChange(value: String) = updateDraft(null) { copy(notes = value) }
    fun onToggleTag(tag: String) = updateDraft(null) {
        copy(contextTags = if (contextTags.contains(tag)) contextTags - tag else contextTags + tag)
    }

    /**
     * Toggles the opt-in to attach location at save time. If the user is
     * turning it on and we don't yet hold COARSE permission, emit a signal
     * so the screen can show the system permission dialog.
     */
    fun onAttachLocationChange(attach: Boolean) {
        _state.update { current ->
            if (!attach) {
                // User opted out — clear any previously captured coords too.
                current.copy(
                    draft = current.draft.copy(
                        attachLocation = false,
                        locationLatitude = null,
                        locationLongitude = null,
                    ),
                    shouldRequestLocationPermission = false,
                )
            } else if (current.locationPermissionGranted) {
                current.copy(draft = current.draft.copy(attachLocation = true))
            } else {
                current.copy(
                    draft = current.draft.copy(attachLocation = true),
                    shouldRequestLocationPermission = true,
                )
            }
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _state.update { current ->
            current.copy(
                locationPermissionGranted = granted,
                shouldRequestLocationPermission = false,
                draft = if (granted) current.draft else current.draft.copy(attachLocation = false),
                transientError = if (!granted && current.draft.attachLocation) {
                    "Location permission denied — location won't be attached."
                } else {
                    current.transientError
                },
            )
        }
    }

    fun onLocationPermissionRequestConsumed() {
        _state.update { it.copy(shouldRequestLocationPermission = false) }
    }

    fun onConfirmationShown() {
        _state.update { it.copy(savedConfirmation = null) }
    }

    fun onTransientErrorShown() {
        _state.update { it.copy(transientError = null) }
    }

    fun onErrorBannerDismissed() {
        _state.update { it.copy(showErrorBanner = false) }
    }

    /**
     * Fold a newly-picked photo into the pending list. Silently clamps
     * to the 3-photo cap — the UI already disables the picker at the
     * cap, but this is the belt-and-braces so a race between picker
     * dismissal and state read can't slip a fourth photo through.
     */
    fun addPendingPhoto(uri: Uri) {
        _state.update { current ->
            val alreadyCommittedCount = current.attachedPhotos.size
            val alreadyPendingCount = current.pendingPhotoUris.size
            val cap = SymptomPhotoRepository.MAX_PHOTOS_PER_LOG
            if (alreadyCommittedCount + alreadyPendingCount >= cap) return@update current
            if (current.pendingPhotoUris.contains(uri)) return@update current
            current.copy(pendingPhotoUris = current.pendingPhotoUris + uri)
        }
    }

    fun removePendingPhoto(uri: Uri) {
        _state.update { current ->
            current.copy(pendingPhotoUris = current.pendingPhotoUris - uri)
        }
    }

    /**
     * Immediately commits a "remove" of an already-attached photo. We
     * drop the row + file through the repository and let the observe
     * subscription refresh the list — optimistic UI isn't worth the
     * divergence risk here.
     */
    fun removeAttachedPhoto(photo: SymptomPhoto) {
        val repo = symptomPhotoRepository ?: return
        viewModelScope.launch {
            runCatching { repo.delete(photo.id) }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            transientError = throwable.message
                                ?: "Couldn't remove photo — please try again.",
                        )
                    }
                }
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        _state.update {
            it.copy(
                cameraPermissionGranted = granted,
                showCameraDeniedRationale = if (!granted) true else it.showCameraDeniedRationale,
            )
        }
    }

    fun onCameraRationaleDismissed() {
        _state.update { it.copy(showCameraDeniedRationale = false) }
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        val result = LogValidator.validate(current.draft, now = nowProvider())
        if (!result.isValid) {
            _state.update {
                it.copy(errors = result.errors, showErrorBanner = true)
            }
            return
        }

        _state.update { it.copy(isSaving = true, errors = emptyMap(), showErrorBanner = false) }
        viewModelScope.launch {
            val draft = current.draft
            val isEditing = current.editingId != 0L

            // Location is captured **here, at save time**, never earlier.
            // If the user didn't opt in, or permission wasn't granted, or the
            // provider returns anything other than coordinates, we persist
            // null — saving always succeeds even when location fails.
            val capture = captureLocation(
                provider = locationProvider,
                reverseGeocoder = reverseGeocoder,
                attach = draft.attachLocation,
                permissionGranted = current.locationPermissionGranted,
                fallbackLat = draft.locationLatitude,
                fallbackLng = draft.locationLongitude,
                fallbackName = draft.locationName,
            )

            // Environmental data: fetched **only** on insert when a location was
            // captured. Edits preserve the original reading verbatim — the
            // network layer is not touched. This is the hard contract from the
            // "editing bypasses the network layer entirely" acceptance criterion.
            val envOutcome = if (isEditing) {
                EnvironmentalOutcome(snapshot = draft.toSnapshot(), warning = null)
            } else {
                fetchEnvironmental(
                    service = environmentalService,
                    timeoutMillis = environmentalTimeoutMillis,
                    lat = capture.latitude,
                    lng = capture.longitude,
                )
            }

            val log = SymptomLog(
                id = current.editingId,
                symptomName = draft.symptomName.trim(),
                description = draft.description.trim(),
                startEpochMillis = draft.startEpochMillis,
                endEpochMillis = if (draft.hasEnded) draft.endEpochMillis else null,
                severity = draft.severity,
                medication = draft.medication.trim(),
                contextTags = draft.contextTags.toList().sorted(),
                notes = draft.notes.trim(),
                createdAtEpochMillis = if (isEditing) draft.createdAtEpochMillis else nowProvider(),
                locationLatitude = capture.latitude,
                locationLongitude = capture.longitude,
                locationName = capture.name,
                weatherCode = envOutcome.snapshot.weatherCode,
                weatherDescription = envOutcome.snapshot.weatherDescription,
                temperatureCelsius = envOutcome.snapshot.temperatureCelsius,
                humidityPercent = envOutcome.snapshot.humidityPercent,
                pressureHpa = envOutcome.snapshot.pressureHpa,
                airQualityIndex = envOutcome.snapshot.airQualityIndex,
            )
            // We need the row id to attach photos to. On insert it's what
            // `save()` returns; on update it's the existing editingId.
            val saveOutcome = runCatching {
                if (isEditing) {
                    val rowsUpdated = repository.update(log)
                    if (rowsUpdated > 0) current.editingId else 0L
                } else {
                    repository.save(log)
                }
            }
            if (saveOutcome.isFailure) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        transientError = saveOutcome.exceptionOrNull()?.message
                            ?: "Couldn't save this log. Please try again.",
                    )
                }
                return@launch
            }
            val savedId = saveOutcome.getOrNull() ?: 0L
            if (savedId <= 0L) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        transientError = "This log no longer exists — changes couldn't be saved.",
                    )
                }
                return@launch
            }

            // Flush pending photos. We do this after the row is committed
            // so the FK target exists; failures are collected into a
            // single Snackbar-friendly message but the overall save is
            // NOT rolled back — a log with 2/3 photos is still more
            // useful than no log at all.
            val photoWarning = flushPendingPhotos(savedId, current.pendingPhotoUris)

            // Reconcile the grouping link. The merged picker enforces
            // mutual exclusivity — at most one of (diagnosis, condition)
            // is set, and "null" is a meaningful state (the user
            // unlinked it). The dispatcher below clears BOTH sides
            // before setting the chosen one so pre-merge data with
            // both links populated normalises on first save. Failures
            // are swallowed — the log itself is already saved and a
            // missing link is recoverable from the picker.
            applyGroupingLink(savedId, current.selectedGroupingId)
            // Pick the most important warning to surface. Location warnings
            // already exist from the previous story; the environmental layer
            // adds its own (timeout, offline, API error). Both go to the same
            // Snackbar channel so we don't stack two toasts on top of each
            // other — location warning wins when both fire, because it's the
            // gating one (no location means the env fetch was skipped).
            // Photo warning comes last (least gating) so it only surfaces
            // when nothing else did.
            val finalWarning = capture.warning ?: envOutcome.warning ?: photoWarning
            _state.update {
                it.copy(
                    isSaving = false,
                    draft = if (isEditing) draft else LogDraft(startEpochMillis = nowProvider()),
                    // Pending list is always cleared on a successful save —
                    // on create they've been flushed to the new row, on
                    // edit they've been flushed to the existing one.
                    pendingPhotoUris = emptyList(),
                    savedConfirmation = when {
                        isEditing -> "Log updated"
                        capture.latitude != null && !envOutcome.snapshot.isEmpty -> "Log saved with location & weather"
                        capture.latitude != null -> "Log saved with location"
                        draft.attachLocation && finalWarning != null -> "Log saved — $finalWarning"
                        else -> "Log saved"
                    },
                    transientError = finalWarning ?: it.transientError,
                )
            }
            onSaved()
        }
    }

    /**
     * Persist the merged grouping selection.
     *
     * The schema still has two link tables (`doctor_diagnosis_logs`
     * and `health_condition_logs`); the merged picker just makes them
     * mutually exclusive at the UX layer. To keep that invariant in
     * the database we always clear BOTH sides before applying the
     * chosen one, so a log that previously had both links populated
     * (possible on pre-merge data) is normalised on first save.
     *
     * Failures are swallowed — the log itself is already saved and
     * recovering a missing link only takes one tap on the picker.
     */
    private suspend fun applyGroupingLink(logId: Long, groupingId: String?) {
        val diagnosisId = LogGrouping.diagnosisIdFor(groupingId)
        val conditionId = LogGrouping.conditionIdFor(groupingId)

        // Clear the side that's NOT being set, even when both repos
        // are wired. This normalises pre-merge rows and is a no-op for
        // logs that only had one side linked. Failures are logged for
        // diagnosability but not surfaced — the primary save already
        // succeeded and the user can re-pick from the editor to retry.
        runCatching {
            if (diagnosisId == null) doctorVisitRepository?.detachLogFromAllDiagnoses(logId)
        }.onFailure { Log.w(TAG, "Detach diagnoses for log $logId failed", it) }
        runCatching {
            if (conditionId == null) healthConditionRepository?.setLogCondition(logId, null)
        }.onFailure { Log.w(TAG, "Clear condition link for log $logId failed", it) }

        // Apply the chosen side.
        runCatching {
            when {
                diagnosisId != null -> doctorVisitRepository?.attachLogToDiagnosis(logId, diagnosisId)
                conditionId != null -> healthConditionRepository?.setLogCondition(logId, conditionId)
                // null grouping → both sides cleared above; nothing further to do.
                else -> Unit
            }
        }.onFailure { Log.w(TAG, "Apply grouping link for log $logId failed", it) }
    }

    /**
     * Runs each pending URI through the photo repository. Returns a
     * warning message when one or more photos failed, or null if every
     * photo landed cleanly (including the zero-pending case).
     *
     * We deliberately swallow per-photo failures rather than short-
     * circuiting — a stream that returns a null bitmap shouldn't stop
     * the other two photos (and the log itself) from being saved.
     */
    private suspend fun flushPendingPhotos(logId: Long, uris: List<Uri>): String? {
        if (uris.isEmpty()) return null
        val repo = symptomPhotoRepository ?: return null
        var successCount = 0
        var failureCount = 0
        uris.forEach { uri ->
            val result = runCatching { repo.addFromUri(logId, uri) }.getOrNull()
            if (result != null) successCount++ else failureCount++
        }
        return when {
            failureCount == 0 -> null
            successCount == 0 -> "Couldn't attach $failureCount photo${if (failureCount == 1) "" else "s"}."
            else -> "Attached $successCount of ${successCount + failureCount} photos — the rest couldn't be processed."
        }
    }

    // EnvironmentalOutcome / CaptureOutcome data classes and the
    // `fetchEnvironmental` / `captureLocation` save-time helpers live
    // in LogSymptomCaptureHelpers.kt. They're pure orchestration over
    // injected dependencies, so lifting them out keeps the VM
    // focused on UI state.

    private inline fun updateDraft(field: LogField?, transform: LogDraft.() -> LogDraft) {
        _state.update { current ->
            val newDraft = current.draft.transform()
            val newErrors = if (field != null) current.errors - field else current.errors
            current.copy(draft = newDraft, errors = newErrors)
        }
    }

    companion object {
        private const val TAG = "LogSymptomViewModel"

        /**
         * Environmental fetch SLA enforced by `withTimeout(...)` in the save
         * pipeline. Kept as a constant so tests can parameterise it without
         * ambiguity about the production value.
         */
        const val ENVIRONMENTAL_FETCH_TIMEOUT_MILLIS: Long = 5_000L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return LogSymptomViewModel(
                    repository = app.container.symptomLogRepository,
                    locationProvider = app.container.locationProvider,
                    reverseGeocoder = app.container.reverseGeocoder,
                    environmentalService = app.container.environmentalService,
                    symptomPhotoRepository = app.container.symptomPhotoRepository,
                    doctorVisitRepository = app.container.doctorVisitRepository,
                    healthConditionRepository = app.container.healthConditionRepository,
                ) as T
            }
        }

        fun editFactory(id: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AuraApplication
                return LogSymptomViewModel(
                    repository = app.container.symptomLogRepository,
                    locationProvider = app.container.locationProvider,
                    reverseGeocoder = app.container.reverseGeocoder,
                    environmentalService = app.container.environmentalService,
                    symptomPhotoRepository = app.container.symptomPhotoRepository,
                    doctorVisitRepository = app.container.doctorVisitRepository,
                    healthConditionRepository = app.container.healthConditionRepository,
                    editingId = id,
                ) as T
            }
        }
    }
}

private fun SymptomLog.toDraft(): LogDraft = LogDraft(
    symptomName = symptomName,
    description = description,
    startEpochMillis = startEpochMillis,
    hasEnded = endEpochMillis != null,
    endEpochMillis = endEpochMillis,
    severity = severity,
    medication = medication,
    contextTags = contextTags.toSet(),
    notes = notes,
    createdAtEpochMillis = createdAtEpochMillis,
    attachLocation = locationLatitude != null && locationLongitude != null,
    locationLatitude = locationLatitude,
    locationLongitude = locationLongitude,
    locationName = locationName,
    weatherCode = weatherCode,
    weatherDescription = weatherDescription,
    temperatureCelsius = temperatureCelsius,
    humidityPercent = humidityPercent,
    pressureHpa = pressureHpa,
    airQualityIndex = airQualityIndex,
)

/** Reconstruct the snapshot from a draft for edit-mode save, without re-fetching. */
private fun LogDraft.toSnapshot(): EnvironmentalSnapshot = EnvironmentalSnapshot(
    weatherCode = weatherCode,
    weatherDescription = weatherDescription,
    temperatureCelsius = temperatureCelsius,
    humidityPercent = humidityPercent,
    pressureHpa = pressureHpa,
    airQualityIndex = airQualityIndex,
)
