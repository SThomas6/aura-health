package com.example.mob_dev_portfolio.data.location

/**
 * Turns a *rounded* coordinate pair into a human-readable place string.
 *
 * Runs exactly once per symptom — at save time — and the result is persisted
 * with the row. We never re-geocode on UI bind: the DB column is the source
 * of truth.
 *
 * The rounding contract is enforced by callers: we take [Double] here but
 * every production call passes values already squashed through
 * [CoordinateRounding.roundCoordinate], so the geocoder never sees full GPS
 * accuracy.
 */
interface ReverseGeocoder {
    /**
     * Returns a non-null display string. Fallback chain for missing fields:
     * `"{locality}, {adminArea}"` → `subAdminArea` → `countryName` →
     * [UNAVAILABLE]. Network / IO failures also return [UNAVAILABLE].
     */
    suspend fun reverseGeocode(latitude: Double, longitude: Double): String

    /**
     * Plain-data projection of the fields we care about from
     * `android.location.Address`. Decoupled from the platform class so the
     * fallback logic is unit-testable without Robolectric.
     */
    data class AddressParts(
        val locality: String? = null,
        val adminArea: String? = null,
        val subAdminArea: String? = null,
        val countryName: String? = null,
    )

    companion object {
        const val UNAVAILABLE: String = "Location unavailable"

        /**
         * Shared formatter so tests and the real implementation agree on the
         * fallback chain exactly.
         */
        fun format(parts: AddressParts?): String {
            if (parts == null) return UNAVAILABLE
            val locality = parts.locality?.takeIf { it.isNotBlank() }
            val adminArea = parts.adminArea?.takeIf { it.isNotBlank() }
            if (locality != null && adminArea != null) {
                return "$locality, $adminArea"
            }
            parts.subAdminArea?.takeIf { it.isNotBlank() }?.let { return it }
            parts.countryName?.takeIf { it.isNotBlank() }?.let { return it }
            // A single remaining field is better than nothing.
            locality?.let { return it }
            adminArea?.let { return it }
            return UNAVAILABLE
        }
    }
}
