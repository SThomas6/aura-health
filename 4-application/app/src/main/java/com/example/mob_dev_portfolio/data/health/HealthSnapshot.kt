package com.example.mob_dev_portfolio.data.health

import java.time.Instant

/**
 * A fully-materialised, **in-memory only** view of the Health Connect data
 * the Gemini prompt should see for a single analysis run.
 *
 * This is the NFR-SE-04 "ephemeral health data" boundary: nothing in this
 * file — no field, no nested type — is persisted to disk, written to Room,
 * serialised to a preferences store, or mirrored into the analysis result
 * archive. The only thing that survives the analysis pipeline is the
 * Gemini *text response*, which the model is explicitly prompted not to
 * echo raw readings back.
 *
 * The snapshot carries two temporal windows:
 *
 *   - [aggregate7Day] — a rolling 7-day summary ending at [generatedAt],
 *     used to give the model general context ("user averages 8k steps,
 *     7h sleep, resting HR 58");
 *
 *   - [perLogWindows] — per-symptom-log 24h-preceding aggregates keyed
 *     by the log's symptom id, so the model can reason "heart rate was
 *     elevated in the day leading up to this headache".
 *
 * [includedMetrics] records which metrics the user had both **toggled
 * on** AND **granted read permission for** when the snapshot was built,
 * so the analysis result screen can surface "the AI considered: Steps,
 * Sleep, Resting HR…" without re-querying grants after the fact.
 */
data class HealthSnapshot(
    val generatedAt: Instant,
    val includedMetrics: Set<HealthConnectMetric>,
    val aggregate7Day: HealthWindowAggregate,
    val perLogWindows: Map<Long, HealthWindowAggregate>,
    val latestHeightMeters: Double?,
    val latestWeightKg: Double?,
    val latestBodyFatPercent: Double?,
) {
    /**
     * Derived BMI. Only computed when the user has granted BOTH height
     * AND weight; otherwise null. Never persisted — if the user opens a
     * past run and wants a fresh BMI, they rerun the analysis.
     */
    val derivedBmi: Double?
        get() {
            val h = latestHeightMeters ?: return null
            val w = latestWeightKg ?: return null
            if (h <= 0.0 || w <= 0.0) return null
            return w / (h * h)
        }

    /** True if the snapshot carries no usable data (nothing to send). */
    val isEmpty: Boolean
        get() = includedMetrics.isEmpty() &&
            aggregate7Day.isEmpty &&
            perLogWindows.values.all { it.isEmpty } &&
            latestHeightMeters == null &&
            latestWeightKg == null &&
            latestBodyFatPercent == null

    companion object {
        /** Sentinel used when Health Connect is unavailable or fully opted-out. */
        val Empty: HealthSnapshot = HealthSnapshot(
            generatedAt = Instant.EPOCH,
            includedMetrics = emptySet(),
            aggregate7Day = HealthWindowAggregate.Empty,
            perLogWindows = emptyMap(),
            latestHeightMeters = null,
            latestWeightKg = null,
            latestBodyFatPercent = null,
        )
    }
}

/**
 * Aggregate statistics for a single time window. Fields are all nullable
 * because the user may have granted only some metrics — null means
 * "no data / no permission" and is rendered as "—" in the prompt rather
 * than fabricated with zeroes (which the model would misread as zero
 * steps, zero heart rate).
 */
data class HealthWindowAggregate(
    val totalSteps: Long?,
    val averageRestingHeartRateBpm: Double?,
    val averageHeartRateBpm: Double?,
    val maxHeartRateBpm: Long?,
    val minHeartRateBpm: Long?,
    val totalSleepMinutes: Long?,
    val averageOxygenSaturationPercent: Double?,
    val averageRespiratoryRateRpm: Double?,
    val totalActiveCaloriesKcal: Double?,
    val exerciseSessionCount: Int?,
) {
    val isEmpty: Boolean
        get() = totalSteps == null &&
            averageRestingHeartRateBpm == null &&
            averageHeartRateBpm == null &&
            maxHeartRateBpm == null &&
            minHeartRateBpm == null &&
            totalSleepMinutes == null &&
            averageOxygenSaturationPercent == null &&
            averageRespiratoryRateRpm == null &&
            totalActiveCaloriesKcal == null &&
            exerciseSessionCount == null

    companion object {
        val Empty: HealthWindowAggregate = HealthWindowAggregate(
            totalSteps = null,
            averageRestingHeartRateBpm = null,
            averageHeartRateBpm = null,
            maxHeartRateBpm = null,
            minHeartRateBpm = null,
            totalSleepMinutes = null,
            averageOxygenSaturationPercent = null,
            averageRespiratoryRateRpm = null,
            totalActiveCaloriesKcal = null,
            exerciseSessionCount = null,
        )
    }
}
