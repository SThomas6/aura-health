package com.example.mob_dev_portfolio.data.ai

import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.preferences.UserProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wraps the sanitization + dispatch pipeline behind a single suspend call.
 *
 * The ViewModel asks this layer: "given these raw logs, this user's raw
 * profile, and their free-text context, what does the AI think?" — and gets
 * back an [AnalysisResult]. Crucially, the ViewModel never handles the raw
 * PII fields directly: they enter this object and leave as a stripped
 * [AnalysisRequest], so the redaction step is impossible to bypass by
 * accident.
 *
 * [maxLogs] caps the number of rows we send so the prompt stays short and
 * predictable even when a user has hundreds of logs — the story is about
 * pattern detection, not bulk upload.
 */
open class AnalysisService(
    private val client: GeminiClient,
    private val maxLogs: Int = DEFAULT_MAX_LOGS,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Builds the sanitized request and sends it. Returns whatever the client
     * returned — the ViewModel maps that to UI state.
     */
    open suspend fun analyze(
        profile: UserProfile,
        userContext: String,
        logs: List<SymptomLog>,
    ): AnalysisResult {
        val request = buildRequest(profile, userContext, logs)
        return client.analyze(request)
    }

    /**
     * Exposed so tests can inspect exactly what would be sent, without
     * actually calling the network. Keeps the "no PII in the payload"
     * acceptance criterion cheap to verify.
     */
    fun buildRequest(
        profile: UserProfile,
        userContext: String,
        logs: List<SymptomLog>,
    ): AnalysisRequest {
        val now = nowProvider()
        val ageRange = AnalysisSanitizer.ageRange(profile.dateOfBirthEpochMillis, now)
        val sanitizedContext = AnalysisSanitizer.stripProfileNames(userContext, profile.fullName)
        val sanitizedLogs = logs
            .sortedByDescending { it.startEpochMillis }
            .take(maxLogs)
            .map { it.sanitize(profile.fullName) }
        return AnalysisRequest(
            ageRange = ageRange,
            userContext = sanitizedContext,
            logs = sanitizedLogs,
        )
    }

    private fun SymptomLog.sanitize(fullName: String?): AnalysisRequest.SanitizedLog =
        AnalysisRequest.SanitizedLog(
            symptomName = AnalysisSanitizer.stripProfileNames(symptomName, fullName),
            severity = severity,
            startIsoDate = ISO_DATE.format(Date(startEpochMillis)),
            description = AnalysisSanitizer.stripProfileNames(description, fullName),
            notes = AnalysisSanitizer.stripProfileNames(notes, fullName),
            contextTags = contextTags,
            weatherDescription = weatherDescription,
            temperatureCelsius = temperatureCelsius,
            humidityPercent = humidityPercent,
            pressureHpa = pressureHpa,
            airQualityIndex = airQualityIndex,
            locationName = locationName,
        )

    companion object {
        const val DEFAULT_MAX_LOGS: Int = 30

        /**
         * ISO-8601 date-only format in UTC so the model sees a stable
         * representation regardless of the device's locale.
         */
        private val ISO_DATE: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
