package com.example.mob_dev_portfolio.ui.log

/**
 * In-memory representation of the symptom-log form while the user is
 * authoring or editing it.
 *
 * Distinct from `SymptomLog` (the persistence entity) because the draft
 * carries form-only state — the unchecked "has ended" toggle, the
 * "attach location" intent, the editor's notion of `severity` as a
 * mid-range default — none of which belong on the database row. The
 * ViewModel translates a validated draft into a `SymptomLog` only at
 * save time.
 *
 * Environment fields ([weatherCode], [temperatureCelsius] etc.) are
 * carried through unchanged when editing an existing log so the original
 * "weather at the time of the symptom" snapshot is never overwritten by
 * a re-fetch — see [com.example.mob_dev_portfolio.ui.log.LogSymptomViewModel].
 */
data class LogDraft(
    val symptomName: String = "",
    val description: String = "",
    val startEpochMillis: Long = System.currentTimeMillis(),
    val hasEnded: Boolean = false,
    val endEpochMillis: Long? = null,
    val severity: Int = 5,
    val medication: String = "",
    val contextTags: Set<String> = emptySet(),
    val notes: String = "",
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    /** Whether the user has opted to attach their approximate location on save. */
    val attachLocation: Boolean = false,
    /**
     * Rounded latitude captured during a previous save (present only when
     * editing an existing log). Never populated from live GPS in the draft —
     * location is fetched at save time.
     */
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    /**
     * Human-readable place string populated by reverse geocoding at save time.
     * Pre-populated from the DB when editing; never derived in the UI.
     */
    val locationName: String? = null,
    /**
     * Environmental metrics carried through the draft so edit flow can
     * preserve the original fetch verbatim. The VM enforces "never re-fetch
     * on edit" — these fields are populated from the DB on load and written
     * back unchanged on save.
     */
    val weatherCode: Int? = null,
    val weatherDescription: String? = null,
    val temperatureCelsius: Double? = null,
    val humidityPercent: Int? = null,
    val pressureHpa: Double? = null,
    val airQualityIndex: Int? = null,
)

/**
 * Identifies a single field on the log form. Used as the key for
 * per-field validation errors so the screen can place a message exactly
 * next to the offending control rather than dumping a generic banner.
 */
enum class LogField { SymptomName, Description, StartDateTime, EndDateTime, Severity }

/**
 * Outcome of running [LogValidator.validate] against a [LogDraft].
 * [errors] is empty on success; otherwise it maps each problematic field
 * to a user-readable string. [summary] is used by accessibility
 * announcements and by the offline test harness.
 */
data class LogValidationResult(
    val errors: Map<LogField, String>,
) {
    val isValid: Boolean get() = errors.isEmpty()

    fun summary(): String = errors.values.joinToString(separator = "\n") { "• $it" }
}

/**
 * Pure validation rules for [LogDraft]. Lives outside the ViewModel so it
 * can be exercised directly from unit tests without spinning up Compose
 * or coroutines.
 *
 * The 60-second future-skew tolerance ([MAX_FUTURE_SKEW_MILLIS]) absorbs
 * the small drift between the user's clock and the device's epoch — the
 * "now" timestamp captured when the picker opens may already be slightly
 * stale by the time the user taps Save.
 */
object LogValidator {

    const val MIN_SEVERITY = 1
    const val MAX_SEVERITY = 10
    private const val MAX_FUTURE_SKEW_MILLIS: Long = 60_000L

    /**
     * Validate the draft. `now` is injectable so tests can pin time and
     * exercise the future-skew rule deterministically.
     */
    fun validate(
        draft: LogDraft,
        now: Long = System.currentTimeMillis(),
    ): LogValidationResult {
        val errors = mutableMapOf<LogField, String>()

        if (draft.symptomName.isBlank()) {
            errors[LogField.SymptomName] = "Symptom type is required"
        }
        if (draft.description.isBlank()) {
            errors[LogField.Description] = "Description is required"
        }
        if (draft.startEpochMillis <= 0L) {
            errors[LogField.StartDateTime] = "Start date/time is required"
        } else if (draft.startEpochMillis > now + MAX_FUTURE_SKEW_MILLIS) {
            errors[LogField.StartDateTime] = "Start date/time cannot be in the future"
        }
        if (draft.hasEnded) {
            val end = draft.endEpochMillis
            when {
                end == null -> errors[LogField.EndDateTime] = "End date/time is required when the symptom has ended"
                end < draft.startEpochMillis -> errors[LogField.EndDateTime] = "End date/time must be after the start"
                end > now + MAX_FUTURE_SKEW_MILLIS -> errors[LogField.EndDateTime] = "End date/time cannot be in the future"
            }
        }
        if (draft.severity !in MIN_SEVERITY..MAX_SEVERITY) {
            errors[LogField.Severity] = "Severity must be between $MIN_SEVERITY and $MAX_SEVERITY"
        }

        return LogValidationResult(errors)
    }
}
