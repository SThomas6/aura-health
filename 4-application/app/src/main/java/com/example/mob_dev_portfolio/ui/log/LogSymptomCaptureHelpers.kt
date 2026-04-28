package com.example.mob_dev_portfolio.ui.log

import com.example.mob_dev_portfolio.data.environment.EnvironmentalFetchResult
import com.example.mob_dev_portfolio.data.environment.EnvironmentalService
import com.example.mob_dev_portfolio.data.environment.EnvironmentalSnapshot
import com.example.mob_dev_portfolio.data.location.CoordinateRounding
import com.example.mob_dev_portfolio.data.location.LocationProvider
import com.example.mob_dev_portfolio.data.location.LocationResult
import com.example.mob_dev_portfolio.data.location.ReverseGeocoder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Save-time location + environmental capture helpers, lifted out of
 * [LogSymptomViewModel] so the VM file isn't fighting both UI state
 * orchestration and the network/permission/timeout dance for two
 * separate subsystems in the same place.
 *
 * Both functions are pure orchestration over their injected
 * dependencies — no Flow wiring, no `viewModelScope`, no UI state.
 * That keeps them straightforward to unit-test on the JVM by passing
 * fakes / fixtures for the four interfaces involved.
 *
 * Every failure mode is funnelled into a non-blocking warning string
 * (NFR-RE-05) — neither helper ever throws into the save flow. The
 * outer save pipeline persists whatever fields came back, even on
 * partial success, without crashing.
 */

internal data class EnvironmentalOutcome(
    val snapshot: EnvironmentalSnapshot,
    val warning: String?,
)

internal data class CaptureOutcome(
    val latitude: Double?,
    val longitude: Double?,
    val name: String? = null,
    val warning: String? = null,
)

/**
 * Run the 5-second environmental fetch (NFR-PE-01 / NFR-RE-05).
 * Skipped when there is no location (nothing to look up) or no
 * service bound (tests, headless builds).
 *
 * Every failure mode — no network, timeout, API error, unexpected
 * exception — translates into a non-blocking warning. The outer save
 * flow persists whatever snapshot was returned (possibly empty).
 */
internal suspend fun fetchEnvironmental(
    service: EnvironmentalService?,
    timeoutMillis: Long,
    lat: Double?,
    lng: Double?,
): EnvironmentalOutcome {
    if (lat == null || lng == null) {
        return EnvironmentalOutcome(EnvironmentalSnapshot.EMPTY, warning = null)
    }
    val bound = service
        ?: return EnvironmentalOutcome(EnvironmentalSnapshot.EMPTY, warning = null)

    return try {
        val result = withTimeout(timeoutMillis) { bound.fetch(lat, lng) }
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
                EnvironmentalOutcome(EnvironmentalSnapshot.EMPTY, warning = result.message)
        }
    } catch (_: TimeoutCancellationException) {
        // withTimeout fired before the service finished — authoritative
        // 5 s SLA enforcement. The request is cancelled cooperatively;
        // we map it to the same Timeout UX as a service-level timeout.
        EnvironmentalOutcome(
            EnvironmentalSnapshot.EMPTY,
            warning = "Weather service timed out — saved without it.",
        )
    } catch (error: Exception) {
        // Defensive catch-all — a mis-implemented service could throw
        // anything. We MUST NOT crash the save flow.
        EnvironmentalOutcome(
            EnvironmentalSnapshot.EMPTY,
            warning = "Weather unavailable: ${error.message ?: "unexpected error"}",
        )
    }
}

/**
 * Capture a save-time location fix and reverse-geocode it. Falls
 * back to whatever the existing log already had (when editing) so an
 * un-grantable permission doesn't blank a previously-good record.
 *
 * Coordinates are rounded BEFORE geocoding (NFR-SE-02) so every
 * downstream consumer — DB, geocoder, UI — sees the same ~1 km grid.
 */
internal suspend fun captureLocation(
    provider: LocationProvider?,
    reverseGeocoder: ReverseGeocoder?,
    attach: Boolean,
    permissionGranted: Boolean,
    fallbackLat: Double?,
    fallbackLng: Double?,
    fallbackName: String?,
): CaptureOutcome {
    if (!attach) return CaptureOutcome(latitude = null, longitude = null)
    val bound = provider
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
    return when (val result = bound.fetchCurrentLocation()) {
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
            CaptureOutcome(latitude = roundedLat, longitude = roundedLng, name = name)
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
