package com.example.mob_dev_portfolio.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.environment.EnvironmentalFetchResult
import com.example.mob_dev_portfolio.data.environment.EnvironmentalService
import com.example.mob_dev_portfolio.data.environment.EnvironmentalSnapshot
import com.example.mob_dev_portfolio.data.location.CoordinateRounding
import com.example.mob_dev_portfolio.data.location.LocationProvider
import com.example.mob_dev_portfolio.data.location.LocationResult
import com.example.mob_dev_portfolio.data.location.ReverseGeocoder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface EditLoadState {
    data object NotEditing : EditLoadState
    data object Loading : EditLoadState
    data object Loaded : EditLoadState
    data object NotFound : EditLoadState
    data class Failed(val message: String) : EditLoadState
}

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
)

class LogSymptomViewModel(
    private val repository: SymptomLogRepository,
    private val locationProvider: LocationProvider? = null,
    private val reverseGeocoder: ReverseGeocoder? = null,
    private val environmentalService: EnvironmentalService? = null,
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

    init {
        if (editingId != 0L) {
            loadForEditing()
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
            val capture = maybeCaptureLocation(
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
                maybeFetchEnvironmental(
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
            val saveOutcome = runCatching {
                if (isEditing) {
                    val rowsUpdated = repository.update(log)
                    rowsUpdated > 0
                } else {
                    repository.save(log)
                    true
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
            if (saveOutcome.getOrNull() == false) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        transientError = "This log no longer exists — changes couldn't be saved.",
                    )
                }
                return@launch
            }
            // Pick the most important warning to surface. Location warnings
            // already exist from the previous story; the environmental layer
            // adds its own (timeout, offline, API error). Both go to the same
            // Snackbar channel so we don't stack two toasts on top of each
            // other — location warning wins when both fire, because it's the
            // gating one (no location means the env fetch was skipped).
            val finalWarning = capture.warning ?: envOutcome.warning
            _state.update {
                it.copy(
                    isSaving = false,
                    draft = if (isEditing) draft else LogDraft(startEpochMillis = nowProvider()),
                    savedConfirmation = when {
                        isEditing -> "Log updated"
                        capture.latitude != null && !envOutcome.snapshot.isEmpty -> "Log saved with location & weather"
                        capture.latitude != null -> "Log saved with location"
                        draft.attachLocation && finalWarning != null -> "Log saved — $finalWarning"
                        else -> "Log saved"
                    },
                    transientError = if (!isEditing && finalWarning != null) finalWarning else it.transientError,
                )
            }
            onSaved()
        }
    }

    private data class EnvironmentalOutcome(
        val snapshot: EnvironmentalSnapshot,
        val warning: String?,
    )

    /**
     * Runs the 5-second environmental fetch. Skipped when there is no location
     * (nothing to look up) or no service bound (tests, headless builds).
     *
     * Every failure mode is translated into a non-blocking Snackbar message
     * via [EnvironmentalOutcome.warning]. The snapshot is returned as-is —
     * the outer save flow persists whatever fields came back, even a partial
     * success, without ever crashing the save.
     */
    private suspend fun maybeFetchEnvironmental(
        lat: Double?,
        lng: Double?,
    ): EnvironmentalOutcome {
        if (lat == null || lng == null) {
            return EnvironmentalOutcome(EnvironmentalSnapshot.EMPTY, warning = null)
        }
        val service = environmentalService
            ?: return EnvironmentalOutcome(EnvironmentalSnapshot.EMPTY, warning = null)

        return try {
            val result = withTimeout(environmentalTimeoutMillis) {
                service.fetch(lat, lng)
            }
            when (result) {
                is EnvironmentalFetchResult.Success ->
                    EnvironmentalOutcome(result.snapshot, warning = null)
                is EnvironmentalFetchResult.NoNetwork ->
                    EnvironmentalOutcome(
                        EnvironmentalSnapshot.EMPTY,
                        warning = "No internet — saved without weather data.",
                    )
                is EnvironmentalFetchResult.Timeout ->
                    EnvironmentalOutcome(
                        EnvironmentalSnapshot.EMPTY,
                        warning = "Weather service timed out — saved without it.",
                    )
                is EnvironmentalFetchResult.ApiError ->
                    EnvironmentalOutcome(
                        EnvironmentalSnapshot.EMPTY,
                        warning = result.message,
                    )
            }
        } catch (_: TimeoutCancellationException) {
            // withTimeout fired before the service finished — this is the
            // authoritative 5s SLA enforcement. The request is cancelled
            // cooperatively; we map it to the same Timeout UX as a
            // service-level timeout.
            EnvironmentalOutcome(
                EnvironmentalSnapshot.EMPTY,
                warning = "Weather service timed out — saved without it.",
            )
        } catch (error: Exception) {
            // Defensive catch-all — a mis-implemented service could throw
            // something unexpected. We MUST NOT crash the save flow.
            EnvironmentalOutcome(
                EnvironmentalSnapshot.EMPTY,
                warning = "Weather unavailable: ${error.message ?: "unexpected error"}",
            )
        }
    }

    private data class CaptureOutcome(
        val latitude: Double?,
        val longitude: Double?,
        val name: String? = null,
        val warning: String? = null,
    )

    private suspend fun maybeCaptureLocation(
        attach: Boolean,
        permissionGranted: Boolean,
        fallbackLat: Double?,
        fallbackLng: Double?,
        fallbackName: String?,
    ): CaptureOutcome {
        if (!attach) return CaptureOutcome(latitude = null, longitude = null)
        val provider = locationProvider
            ?: return CaptureOutcome(
                latitude = fallbackLat,
                longitude = fallbackLng,
                name = fallbackName,
                warning = "Location services unavailable on this device.",
            )
        if (!permissionGranted) {
            return CaptureOutcome(
                latitude = fallbackLat,
                longitude = fallbackLng,
                name = fallbackName,
                warning = "Location permission not granted — location not attached.",
            )
        }
        return when (val result = provider.fetchCurrentLocation()) {
            is LocationResult.Coordinates -> {
                // Round FIRST — every downstream consumer (DB, geocoder, UI)
                // sees the same ~1 km grid.
                val roundedLat = CoordinateRounding.roundCoordinate(result.latitude)
                val roundedLng = CoordinateRounding.roundCoordinate(result.longitude)
                // Reverse geocode on the rounded pair — never the raw fix.
                // Runs once here at save time; the resulting string is
                // persisted and re-read, never recomputed on UI bind.
                val name = reverseGeocoder?.reverseGeocode(roundedLat, roundedLng)
                    ?: ReverseGeocoder.UNAVAILABLE
                CaptureOutcome(
                    latitude = roundedLat,
                    longitude = roundedLng,
                    name = name,
                )
            }
            is LocationResult.PermissionDenied -> CaptureOutcome(
                latitude = fallbackLat,
                longitude = fallbackLng,
                name = fallbackName,
                warning = "Location permission denied — location not attached.",
            )
            is LocationResult.Unavailable -> CaptureOutcome(
                latitude = fallbackLat,
                longitude = fallbackLng,
                name = fallbackName,
                warning = "Couldn't get a location fix — saved without it.",
            )
            is LocationResult.Failed -> CaptureOutcome(
                latitude = fallbackLat,
                longitude = fallbackLng,
                name = fallbackName,
                warning = "Location error: ${result.message}",
            )
        }
    }

    private inline fun updateDraft(field: LogField?, transform: LogDraft.() -> LogDraft) {
        _state.update { current ->
            val newDraft = current.draft.transform()
            val newErrors = if (field != null) current.errors - field else current.errors
            current.copy(draft = newDraft, errors = newErrors)
        }
    }

    companion object {
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
