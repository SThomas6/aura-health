package com.example.mob_dev_portfolio.data.ai

/**
 * The already-sanitized payload that the Gemini client sees.
 *
 * Construction of this object is the single choke point for PII — once a
 * request has been reduced to an [AnalysisRequest] there are no names and
 * no exact DOB anywhere in the tree. Anything downstream (the serialization
 * layer, the OkHttp client, the test doubles) can assume the data is safe
 * to transmit.
 *
 * [biologicalSex] and [healthSummary] were added for the Health Connect
 * integration story. They are already-bucketed / already-aggregated values,
 * so they also uphold the "no raw PII" invariant — the model sees "Steps
 * (7d): 54,210" rather than per-minute step readings with timestamps.
 */
data class AnalysisRequest(
    val ageRange: String,
    val biologicalSex: String?,
    val userContext: String,
    val healthSummary: HealthSummary?,
    val logs: List<SanitizedLog>,
    /**
     * Conditions a clinician has already told the user about. The prompt
     * surfaces these as known context so the model treats linked symptoms
     * as "part of an already-diagnosed pattern" rather than a fresh
     * signal. Logs the user has marked as "reviewed & cleared" are not
     * present in this object at all — they've been dropped upstream in
     * [AnalysisService.buildRequest].
     */
    val knownDiagnoses: List<KnownDiagnosis> = emptyList(),
    /**
     * User-declared standing health conditions (e.g. "Type 2 Diabetes",
     * "Asthma"). These are *not* tied to a specific doctor visit — the
     * user added them in onboarding or via the Conditions settings
     * screen. The prompt builder emits them as a parallel "user has
     * told us they have" context block alongside [knownDiagnoses] so
     * the model treats linked symptoms as already-explained without
     * conflating them with visit-specific diagnoses.
     */
    val userDeclaredConditions: List<UserDeclaredCondition> = emptyList(),
) {
    data class SanitizedLog(
        val symptomName: String,
        val severity: Int,
        val startIsoDate: String,
        val description: String,
        val notes: String,
        val contextTags: List<String>,
        val weatherDescription: String?,
        val temperatureCelsius: Double?,
        val humidityPercent: Int?,
        val pressureHpa: Double?,
        val airQualityIndex: Int?,
        val locationName: String?,
        /**
         * The 24h-preceding aggregate for this symptom, or null if the
         * user hasn't connected Health Connect / hasn't granted the
         * relevant read permissions / the window was empty. Held as a
         * map of short labels → pre-formatted values so the prompt
         * builder doesn't have to branch on missing fields.
         */
        val healthAggregate24h: Map<String, String>?,
        /**
         * If the user has linked this symptom to a clinician-flagged
         * diagnosis, the diagnosis label (e.g. "Chronic migraine") is
         * echoed here. The prompt builder appends it as
         * `[known: migraine]` so the model treats the symptom as
         * already-explained instead of a fresh pattern-detection
         * target. Null for unlinked logs.
         */
        val diagnosisLabel: String? = null,
        /**
         * If the user has grouped this symptom under one of their
         * standing health conditions (e.g. "Type 2 Diabetes"), the
         * condition name is echoed here. Distinct from
         * [diagnosisLabel] so the prompt can separate "doctor said you
         * have X" from "user told us they have X". Null for ungrouped
         * logs.
         */
        val userConditionLabel: String? = null,
    )

    /**
     * A single diagnosis the user has pinned from a doctor visit, plus
     * the brief history that grounds it. Kept intentionally sparse:
     * only symptom name + date for each related log, never description
     * or notes — the user's wording ("brief history") was explicit.
     */
    data class KnownDiagnosis(
        val label: String,
        val history: List<HistoryEntry>,
    ) {
        data class HistoryEntry(
            val symptomName: String,
            val startIsoDate: String,
        )
    }

    /**
     * Mirror of [KnownDiagnosis] for user-declared standing conditions.
     * Same shape (label + brief history) so prompt construction is
     * symmetric, but a separate type to keep the "doctor confirmed" /
     * "user declared" distinction explicit through the whole pipeline.
     */
    data class UserDeclaredCondition(
        val label: String,
        val history: List<KnownDiagnosis.HistoryEntry>,
    )

    /**
     * The 7-day health aggregate + body measurements that frame the
     * per-log context. Flattened into two simple maps for the same
     * "no branching in the prompt" reason as [SanitizedLog.healthAggregate24h].
     *
     * [includedMetrics] surfaces the short labels of the metrics that
     * actually contributed data to this snapshot so the analysis
     * detail screen can render a "considered: Steps, Sleep, …" chip
     * row when the run is opened later.
     */
    data class HealthSummary(
        val includedMetrics: List<String>,
        val rolling7Day: Map<String, String>,
        val bodyMeasurements: Map<String, String>,
        val derivedBmi: Double?,
    )
}

/** Terminal states of a call to the Gemini client. */
sealed interface AnalysisResult {
    data class Success(val summaryText: String) : AnalysisResult

    /** Device is offline / DNS failed / socket refused. */
    data object NoNetwork : AnalysisResult

    /** Outer timeout or server took too long to respond. */
    data object Timeout : AnalysisResult

    /** HTTP 4xx/5xx, malformed JSON, or a parse issue. User-visible [message]. */
    data class ApiError(val message: String) : AnalysisResult
}
