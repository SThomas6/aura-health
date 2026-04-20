package com.example.mob_dev_portfolio.data.ai

/**
 * The already-sanitized payload that the Gemini client sees.
 *
 * Construction of this object is the single choke point for PII — once a
 * request has been reduced to an [AnalysisRequest] there are no names and
 * no exact DOB anywhere in the tree. Anything downstream (the serialization
 * layer, the OkHttp client, the test doubles) can assume the data is safe
 * to transmit.
 */
data class AnalysisRequest(
    val ageRange: String,
    val userContext: String,
    val logs: List<SanitizedLog>,
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
