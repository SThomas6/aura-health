package com.example.mob_dev_portfolio.data.health

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

/**
 * Thin wrapper around the [HealthConnectClient] that the rest of the app
 * talks to. The service hides three pieces of friction:
 *
 *   1. **SDK availability** — on devices where Health Connect is missing,
 *      the client constructor throws. [status] returns a typed state so
 *      callers can branch without try/catch noise, and [buildInstallIntent]
 *      hands back a Play Store intent for the "missing" case.
 *
 *   2. **Grant inspection** — Health Connect grants are per-permission,
 *      which lines up with the settings screen's per-toggle model but
 *      means "is feature X usable?" is a two-step answer (toggle on AND
 *      permission granted). [grantedPermissions] surfaces the current
 *      grant set so a ViewModel can reconcile in one place.
 *
 *   3. **Snapshot assembly** — [buildSnapshot] composes the per-log and
 *      7-day aggregates, translating a heterogeneous set of record types
 *      into the flat [HealthSnapshot] the Gemini prompt understands.
 */
open class HealthConnectService(
    private val context: Context,
    private val nowProvider: () -> Instant = { Instant.now() },
) {

    /** Lazy — constructing the client hits the system service, so only pay for it when asked. */
    private val clientOrNull: HealthConnectClient? by lazy {
        if (sdkStatus() == HealthConnectClient.SDK_AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }
                .onFailure { Log.w(TAG, "Failed to create HealthConnectClient", it) }
                .getOrNull()
        } else {
            null
        }
    }

    /** Available statuses the UI cares about. */
    enum class Status {
        /** Health Connect is installed and ready; the client is usable. */
        Available,

        /** The provider APK is missing — prompt the user to install via Play. */
        ProviderMissing,

        /** The provider is installed but needs an update before use. */
        ProviderUpdateRequired,

        /**
         * Health Connect is not supported on this device at all (e.g. very
         * old Android versions). Users see "not supported" copy; no install
         * CTA is offered because there's no APK that would help.
         */
        Unsupported,
    }

    open fun status(): Status = when (sdkStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> Status.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Status.ProviderUpdateRequired
        HealthConnectClient.SDK_UNAVAILABLE -> Status.ProviderMissing
        else -> Status.Unsupported
    }

    private fun sdkStatus(): Int =
        HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PACKAGE)

    /**
     * Intent that opens Health Connect's Play listing so the user can
     * install (or update) the provider APK. Returns null if the current
     * status wouldn't benefit from a Play launch — saves the caller a
     * branching step.
     */
    open fun buildInstallIntent(): Intent? {
        val needsPlay = when (status()) {
            Status.ProviderMissing, Status.ProviderUpdateRequired -> true
            Status.Available, Status.Unsupported -> false
        }
        if (!needsPlay) return null
        val uri = (
            "market://details?id=$HEALTH_CONNECT_PACKAGE" +
                "&url=healthconnect%3A%2F%2Fonboarding"
            ).toUri()
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.android.vending")
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
        }
    }

    /**
     * The set of permission strings the user has currently granted. An
     * empty set is a valid return when Health Connect is unavailable —
     * callers treat "no grants" the same as "no metrics available".
     */
    open suspend fun grantedPermissions(): Set<String> {
        val client = clientOrNull ?: return emptySet()
        return runCatching {
            client.permissionController.getGrantedPermissions()
        }.onFailure { Log.w(TAG, "getGrantedPermissions failed", it) }
            .getOrDefault(emptySet())
    }

    /**
     * Revoke every permission the user has granted us. Used by the
     * "Disconnect" action so a subsequent Connect re-prompts from a
     * clean state — without this the HC permission contract would see
     * the existing grant set and return immediately with no dialog,
     * making reconnect feel like a no-op.
     *
     * Silently no-ops if HC is unavailable — nothing to revoke.
     */
    open suspend fun revokeAllPermissions() {
        val client = clientOrNull ?: return
        runCatching { client.permissionController.revokeAllPermissions() }
            .onFailure { Log.w(TAG, "revokeAllPermissions failed", it) }
    }

    /**
     * Convenience: given a set of metric toggles a user has enabled, which
     * subset is actually usable right now (permission + toggle both on)?
     */
    open suspend fun readableMetrics(enabled: Set<HealthConnectMetric>): Set<HealthConnectMetric> {
        if (enabled.isEmpty()) return emptySet()
        val granted = grantedPermissions()
        return enabled.filter { it.readPermission in granted }.toSet()
    }

    /**
     * Materialises a [HealthSnapshot] for the given symptom-log window.
     *
     *   - The 7-day window ends at "now".
     *   - Each log's 24h window is the 24 hours **ending** at the log's
     *     start time — "what was happening in the day leading up to
     *     this symptom".
     *
     * If Health Connect is unavailable, returns [HealthSnapshot.Empty]
     * and logs a warning; the caller can send the prompt without health
     * data. If the user has enabled metrics but not granted them, those
     * metrics simply don't appear in [HealthSnapshot.includedMetrics].
     */
    open suspend fun buildSnapshot(
        enabledMetrics: Set<HealthConnectMetric>,
        logStartTimes: Map<Long, Instant>,
    ): HealthSnapshot {
        val client = clientOrNull ?: return HealthSnapshot.Empty
        val now = nowProvider()
        val readable = readableMetrics(enabledMetrics)
        if (readable.isEmpty()) {
            return HealthSnapshot.Empty.copy(generatedAt = now)
        }

        val sevenDayRange = TimeRangeFilter.between(now.minus(Duration.ofDays(7)), now)
        val sevenDay = aggregateFor(client, readable, sevenDayRange)

        val perLog: Map<Long, HealthWindowAggregate> = logStartTimes
            .mapValues { (_, start) ->
                val range = TimeRangeFilter.between(start.minus(Duration.ofHours(24)), start)
                aggregateFor(client, readable, range)
            }
            .filterValues { !it.isEmpty }

        val latestHeight = if (HealthConnectMetric.Height in readable) {
            latestHeightMeters(client, now)
        } else null
        val latestWeight = if (HealthConnectMetric.Weight in readable) {
            latestWeightKg(client, now)
        } else null
        val latestBodyFat = if (HealthConnectMetric.BodyFat in readable) {
            latestBodyFatPercent(client, now)
        } else null

        return HealthSnapshot(
            generatedAt = now,
            includedMetrics = readable,
            aggregate7Day = sevenDay,
            perLogWindows = perLog,
            latestHeightMeters = latestHeight,
            latestWeightKg = latestWeight,
            latestBodyFatPercent = latestBodyFat,
        )
    }

    /**
     * Runs all the record-type reads for a single time window and folds
     * them into a [HealthWindowAggregate]. Each read is isolated in its
     * own `runCatching` block so one failing read (e.g. a temporarily
     * unavailable data source) doesn't wipe out the whole aggregate.
     */
    private suspend fun aggregateFor(
        client: HealthConnectClient,
        readable: Set<HealthConnectMetric>,
        range: TimeRangeFilter,
    ): HealthWindowAggregate {
        // An empty `records` list returns `0L` from `sumOf` — but "zero" in
        // this context is indistinguishable from "no data at all". We want
        // the AI prompt to omit fields the user hasn't recorded rather than
        // say "you walked 0 steps", so every aggregate here nulls out when
        // the underlying record list is empty.
        val steps: Long? = if (HealthConnectMetric.Steps in readable) {
            runCatching {
                val records = client.readRecords(ReadRecordsRequest(StepsRecord::class, range))
                    .records.excludingDemoSeedInProduction()
                if (records.isEmpty()) null else records.sumOf { it.count }
            }.getOrNull()
        } else null

        val restingAvg: Double? = if (HealthConnectMetric.RestingHeartRate in readable) {
            runCatching {
                val records = client.readRecords(
                    ReadRecordsRequest(RestingHeartRateRecord::class, range),
                ).records.excludingDemoSeedInProduction()
                if (records.isEmpty()) null else records.sumOf { it.beatsPerMinute }.toDouble() / records.size
            }.getOrNull()
        } else null

        val hrSummary: HeartRateSummary = if (HealthConnectMetric.HeartRate in readable) {
            runCatching {
                val samples = client.readRecords(
                    ReadRecordsRequest(HeartRateRecord::class, range),
                ).records.excludingDemoSeedInProduction()
                    .flatMap { record -> record.samples.map { it.beatsPerMinute } }
                HeartRateSummary(
                    avg = if (samples.isEmpty()) null else samples.sum().toDouble() / samples.size,
                    min = samples.minOrNull(),
                    max = samples.maxOrNull(),
                )
            }.getOrDefault(HeartRateSummary.Empty)
        } else HeartRateSummary.Empty

        val sleepMinutes: Long? = if (HealthConnectMetric.SleepSession in readable) {
            runCatching {
                val records = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range))
                    .records.excludingDemoSeedInProduction()
                if (records.isEmpty()) null
                else records.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
            }.getOrNull()
        } else null

        val oxygenAvg: Double? = if (HealthConnectMetric.OxygenSaturation in readable) {
            runCatching {
                val records = client.readRecords(
                    ReadRecordsRequest(OxygenSaturationRecord::class, range),
                ).records.excludingDemoSeedInProduction()
                if (records.isEmpty()) null else records.sumOf { it.percentage.value } / records.size
            }.getOrNull()
        } else null

        val respRateAvg: Double? = if (HealthConnectMetric.RespiratoryRate in readable) {
            runCatching {
                val records = client.readRecords(
                    ReadRecordsRequest(RespiratoryRateRecord::class, range),
                ).records.excludingDemoSeedInProduction()
                if (records.isEmpty()) null else records.sumOf { it.rate } / records.size
            }.getOrNull()
        } else null

        val activeKcal: Double? = if (HealthConnectMetric.ActiveCaloriesBurned in readable) {
            runCatching {
                val records = client.readRecords(
                    ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, range),
                ).records.excludingDemoSeedInProduction()
                if (records.isEmpty()) null else records.sumOf { it.energy.inKilocalories }
            }.getOrNull()
        } else null

        val exerciseCount: Int? = if (HealthConnectMetric.ExerciseSession in readable) {
            runCatching {
                val size = client.readRecords(
                    ReadRecordsRequest(ExerciseSessionRecord::class, range),
                ).records.excludingDemoSeedInProduction().size
                if (size == 0) null else size
            }.getOrNull()
        } else null

        return HealthWindowAggregate(
            totalSteps = steps,
            averageRestingHeartRateBpm = restingAvg,
            averageHeartRateBpm = hrSummary.avg,
            maxHeartRateBpm = hrSummary.max,
            minHeartRateBpm = hrSummary.min,
            totalSleepMinutes = sleepMinutes,
            averageOxygenSaturationPercent = oxygenAvg,
            averageRespiratoryRateRpm = respRateAvg,
            totalActiveCaloriesKcal = activeKcal,
            exerciseSessionCount = exerciseCount,
        )
    }

    /**
     * "Latest" reads use a 365-day look-back because body measurements are
     * entered far less often than vitals. A user who last weighed in six
     * months ago still wants that reading propagated into BMI; if we
     * scoped it to the 7-day window we'd miss it.
     */
    private suspend fun latestHeightMeters(client: HealthConnectClient, now: Instant): Double? =
        runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    HeightRecord::class,
                    TimeRangeFilter.between(now.minus(Duration.ofDays(365)), now),
                ),
            ).records.excludingDemoSeedInProduction().maxByOrNull { it.time }?.height?.inMeters
        }.getOrNull()

    private suspend fun latestWeightKg(client: HealthConnectClient, now: Instant): Double? =
        runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    WeightRecord::class,
                    TimeRangeFilter.between(now.minus(Duration.ofDays(365)), now),
                ),
            ).records.excludingDemoSeedInProduction().maxByOrNull { it.time }?.weight?.inKilograms
        }.getOrNull()

    private suspend fun latestBodyFatPercent(client: HealthConnectClient, now: Instant): Double? =
        runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    BodyFatRecord::class,
                    TimeRangeFilter.between(now.minus(Duration.ofDays(365)), now),
                ),
            ).records.excludingDemoSeedInProduction().maxByOrNull { it.time }?.percentage?.value
        }.getOrNull()

    private data class HeartRateSummary(
        val avg: Double?,
        val min: Long?,
        val max: Long?,
    ) {
        companion object {
            val Empty = HeartRateSummary(avg = null, min = null, max = null)
        }
    }

    companion object {
        private const val TAG = "HealthConnectService"
        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
    }
}
