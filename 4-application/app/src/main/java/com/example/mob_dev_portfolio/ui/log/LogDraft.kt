package com.example.mob_dev_portfolio.ui.log

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

enum class LogField { SymptomName, Description, StartDateTime, EndDateTime, Severity }

data class LogValidationResult(
    val errors: Map<LogField, String>,
) {
    val isValid: Boolean get() = errors.isEmpty()

    fun summary(): String = errors.values.joinToString(separator = "\n") { "• $it" }
}

object LogValidator {

    const val MIN_SEVERITY = 1
    const val MAX_SEVERITY = 10
    private const val MAX_FUTURE_SKEW_MILLIS: Long = 60_000L

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
