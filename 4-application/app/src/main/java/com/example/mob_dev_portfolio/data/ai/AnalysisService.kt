package com.example.mob_dev_portfolio.data.ai

import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.doctor.DoctorContextSnapshot
import com.example.mob_dev_portfolio.data.health.HealthSnapshot
import com.example.mob_dev_portfolio.data.health.HealthWindowAggregate
import com.example.mob_dev_portfolio.data.preferences.BiologicalSex
import com.example.mob_dev_portfolio.data.preferences.UserProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wraps the sanitization + dispatch pipeline behind a single suspend call.
 *
 * The ViewModel asks this layer: "given these raw logs, this user's raw
 * profile, their free-text context, and (optionally) a fresh Health
 * Connect snapshot, what does the AI think?" — and gets back an
 * [AnalysisResult]. Crucially, the ViewModel never handles the raw PII
 * fields directly: they enter this object and leave as a stripped
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
        healthSnapshot: HealthSnapshot = HealthSnapshot.Empty,
        doctorContext: DoctorContextSnapshot = DoctorContextSnapshot.Empty,
        userConditions: com.example.mob_dev_portfolio.data.condition.UserConditionsSnapshot =
            com.example.mob_dev_portfolio.data.condition.UserConditionsSnapshot.Empty,
    ): AnalysisResult {
        val request = buildRequest(profile, userContext, logs, healthSnapshot, doctorContext, userConditions)
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
        healthSnapshot: HealthSnapshot = HealthSnapshot.Empty,
        doctorContext: DoctorContextSnapshot = DoctorContextSnapshot.Empty,
        userConditions: com.example.mob_dev_portfolio.data.condition.UserConditionsSnapshot =
            com.example.mob_dev_portfolio.data.condition.UserConditionsSnapshot.Empty,
    ): AnalysisRequest {
        val now = nowProvider()
        val ageRange = AnalysisSanitizer.ageRange(profile.dateOfBirthEpochMillis, now)
        val sanitizedContext = AnalysisSanitizer.stripProfileNames(userContext, profile.fullName)
        // Step 1: drop logs the user has explicitly marked as reviewed +
        // cleared by a doctor — these should never influence the AI.
        // Step 2: annotate the remainder with their diagnosis label (if
        // any) so the model knows the symptom is already-explained.
        val filteredLogs = logs.filterNot { it.id in doctorContext.clearedLogIds }
        val orderedLogs = filteredLogs
            .sortedByDescending { it.startEpochMillis }
            .take(maxLogs)
        val sanitizedLogs = orderedLogs.map { log ->
            log.sanitize(
                fullName = profile.fullName,
                aggregate24h = healthSnapshot.perLogWindows[log.id],
                diagnosisLabel = doctorContext.diagnosisLabelByLogId[log.id],
                userConditionLabel = userConditions.conditionLabelByLogId[log.id],
            )
        }
        val knownDiagnoses = buildKnownDiagnoses(
            logsById = logs.associateBy { it.id },
            fullName = profile.fullName,
            doctorContext = doctorContext,
        )
        val userDeclaredConditions = buildUserDeclaredConditions(
            logsById = logs.associateBy { it.id },
            fullName = profile.fullName,
            snapshot = userConditions,
            clearedLogIds = doctorContext.clearedLogIds,
        )
        return AnalysisRequest(
            ageRange = ageRange,
            biologicalSex = profile.biologicalSex?.asPromptLabel(),
            userContext = sanitizedContext,
            healthSummary = healthSnapshot.asSummaryOrNull(),
            logs = sanitizedLogs,
            knownDiagnoses = knownDiagnoses,
            userDeclaredConditions = userDeclaredConditions,
        )
    }

    private fun SymptomLog.sanitize(
        fullName: String?,
        aggregate24h: HealthWindowAggregate?,
        diagnosisLabel: String?,
        userConditionLabel: String?,
    ): AnalysisRequest.SanitizedLog =
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
            healthAggregate24h = aggregate24h?.toLabelMap()?.takeIf { it.isNotEmpty() },
            diagnosisLabel = diagnosisLabel?.let {
                AnalysisSanitizer.stripProfileNames(it, fullName)
            },
            userConditionLabel = userConditionLabel?.let {
                AnalysisSanitizer.stripProfileNames(it, fullName)
            },
        )

    /**
     * Builds the per-diagnosis history block. Each diagnosis carries
     * only symptom name + start date for its linked logs — the user
     * explicitly asked for a *brief* history, not a re-dump of every
     * log's notes + environmentals. Diagnoses with no linked logs still
     * appear (the label alone is useful context for the model).
     */
    private fun buildKnownDiagnoses(
        logsById: Map<Long, SymptomLog>,
        fullName: String?,
        doctorContext: DoctorContextSnapshot,
    ): List<AnalysisRequest.KnownDiagnosis> {
        if (doctorContext.diagnosisLabels.isEmpty()) return emptyList()
        return doctorContext.diagnosisLabels.entries
            .sortedBy { it.value.lowercase(Locale.US) }
            .map { (diagnosisId, rawLabel) ->
                val linkedIds = doctorContext.linkedLogIdsByDiagnosis[diagnosisId].orEmpty()
                val history = linkedIds
                    // Keep the history stable and skip cleared logs too —
                    // the AI shouldn't see a cleared log even as a
                    // passing diagnosis mention.
                    .asSequence()
                    .filterNot { it in doctorContext.clearedLogIds }
                    .mapNotNull { logsById[it] }
                    .sortedByDescending { it.startEpochMillis }
                    .map { log ->
                        AnalysisRequest.KnownDiagnosis.HistoryEntry(
                            symptomName = AnalysisSanitizer.stripProfileNames(log.symptomName, fullName),
                            startIsoDate = ISO_DATE.format(Date(log.startEpochMillis)),
                        )
                    }
                    .toList()
                AnalysisRequest.KnownDiagnosis(
                    label = AnalysisSanitizer.stripProfileNames(rawLabel, fullName),
                    history = history,
                )
            }
    }

    /**
     * Mirror of [buildKnownDiagnoses] for user-declared standing
     * conditions. Sorted alphabetically by label so the prompt order is
     * deterministic and runs against the same diet of context regardless
     * of the order the user added them in.
     */
    private fun buildUserDeclaredConditions(
        logsById: Map<Long, SymptomLog>,
        fullName: String?,
        snapshot: com.example.mob_dev_portfolio.data.condition.UserConditionsSnapshot,
        clearedLogIds: Set<Long>,
    ): List<AnalysisRequest.UserDeclaredCondition> {
        if (snapshot.conditionLabels.isEmpty()) return emptyList()
        return snapshot.conditionLabels.entries
            .sortedBy { it.value.lowercase(Locale.US) }
            .map { (conditionId, rawLabel) ->
                val linkedIds = snapshot.linkedLogIdsByCondition[conditionId].orEmpty()
                val history = linkedIds
                    .asSequence()
                    .filterNot { it in clearedLogIds }
                    .mapNotNull { logsById[it] }
                    .sortedByDescending { it.startEpochMillis }
                    .map { log ->
                        AnalysisRequest.KnownDiagnosis.HistoryEntry(
                            symptomName = AnalysisSanitizer.stripProfileNames(log.symptomName, fullName),
                            startIsoDate = ISO_DATE.format(Date(log.startEpochMillis)),
                        )
                    }
                    .toList()
                AnalysisRequest.UserDeclaredCondition(
                    label = AnalysisSanitizer.stripProfileNames(rawLabel, fullName),
                    history = history,
                )
            }
    }

    /**
     * Folds the snapshot into a pair of pre-formatted maps the prompt
     * builder can render. Returns null when the snapshot has nothing
     * actionable — keeping the "Health data" section out of the prompt
     * entirely rather than printing "no data available" (which wastes
     * tokens and confuses the model).
     */
    private fun HealthSnapshot.asSummaryOrNull(): AnalysisRequest.HealthSummary? {
        if (isEmpty) return null
        val sevenDay = aggregate7Day.toLabelMap()
        val body = buildMap<String, String> {
            latestHeightMeters?.let { put("Height", "%.2f m".format(Locale.US, it)) }
            latestWeightKg?.let { put("Weight", "%.1f kg".format(Locale.US, it)) }
            latestBodyFatPercent?.let { put("Body fat", "%.1f %%".format(Locale.US, it)) }
            derivedBmi?.let { put("BMI", "%.1f".format(Locale.US, it)) }
        }
        if (sevenDay.isEmpty() && body.isEmpty()) return null
        return AnalysisRequest.HealthSummary(
            includedMetrics = includedMetrics.map { it.shortLabel },
            rolling7Day = sevenDay,
            bodyMeasurements = body,
            derivedBmi = derivedBmi,
        )
    }

    private fun HealthWindowAggregate.toLabelMap(): Map<String, String> = buildMap {
        // Belt-and-braces: even if the HC service ever returns a literal
        // zero instead of null for a cumulative metric, we skip it here so
        // the AI never sees "0 steps" / "0m sleep" / "0 kcal" — those
        // always mean "the user hasn't logged this" in practice, and the
        // model consistently misread them as real observations.
        totalSteps?.takeIf { it > 0 }?.let { put("Steps", formatCount(it)) }
        averageRestingHeartRateBpm?.takeIf { it > 0 }?.let { put("Resting HR", "%.0f bpm".format(Locale.US, it)) }
        averageHeartRateBpm?.takeIf { it > 0 }?.let { put("Avg HR", "%.0f bpm".format(Locale.US, it)) }
        maxHeartRateBpm?.takeIf { it > 0 }?.let { put("Peak HR", "$it bpm") }
        minHeartRateBpm?.takeIf { it > 0 }?.let { put("Low HR", "$it bpm") }
        totalSleepMinutes?.takeIf { it > 0 }?.let { put("Sleep", formatSleep(it)) }
        averageOxygenSaturationPercent?.takeIf { it > 0 }?.let { put("SpO₂", "%.0f %%".format(Locale.US, it)) }
        averageRespiratoryRateRpm?.takeIf { it > 0 }?.let { put("Resp rate", "%.0f /min".format(Locale.US, it)) }
        totalActiveCaloriesKcal?.takeIf { it > 0 }?.let { put("Active kcal", "%.0f".format(Locale.US, it)) }
        exerciseSessionCount?.takeIf { it > 0 }?.let { put("Exercises", it.toString()) }
    }

    private fun formatCount(value: Long): String =
        if (value >= 1_000) "%.1fk".format(Locale.US, value / 1_000.0)
        else value.toString()

    private fun formatSleep(minutes: Long): String {
        val hours = minutes / 60
        val rem = minutes % 60
        return if (hours > 0) "${hours}h ${rem}m" else "${rem}m"
    }

    private fun BiologicalSex.asPromptLabel(): String? = when (this) {
        BiologicalSex.Female -> "female"
        BiologicalSex.Male -> "male"
        BiologicalSex.Intersex -> "intersex"
        // Deliberate: map "prefer not to say" to null so the prompt
        // builder drops the field entirely — see kdoc on BiologicalSex.
        BiologicalSex.PreferNotToSay -> null
    }

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
