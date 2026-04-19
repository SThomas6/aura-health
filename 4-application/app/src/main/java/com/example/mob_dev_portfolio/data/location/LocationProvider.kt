package com.example.mob_dev_portfolio.data.location

/**
 * Abstraction over device location capture so the ViewModel can be tested
 * without Google Play Services, and so policy (no background polling, single
 * shot only) is enforced at one layer.
 *
 * Implementations MUST be single-shot: no background polling, no location
 * updates subscription. The coroutine is cancellable — if the caller cancels,
 * the underlying request MUST be cancelled too.
 */
interface LocationProvider {
    /**
     * Fetches a fresh coordinate *right now*. Returns [LocationResult] rather
     * than throwing, so the ViewModel can decide how to surface each failure
     * mode without exception wrangling.
     */
    suspend fun fetchCurrentLocation(): LocationResult
}

sealed interface LocationResult {
    /** Successfully obtained coordinates. Values are raw — round before persisting. */
    data class Coordinates(val latitude: Double, val longitude: Double) : LocationResult

    /** User has not granted location permission. */
    data object PermissionDenied : LocationResult

    /** Location services disabled or no position available within the budget. */
    data object Unavailable : LocationResult

    /** Unexpected platform error. */
    data class Failed(val message: String) : LocationResult
}
