package com.example.mob_dev_portfolio.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.reflect.KClass

/**
 * Developer tool that populates a user's Health Connect store with two
 * weeks of plausible fake readings the first time they Connect through
 * AuraHealth. This exists so the dashboard and AI analysis demo have
 * lived-in graphs to render rather than the flat "12 steps today" a
 * fresh install would show.
 *
 * ### Idempotency via clientRecordId
 * Every record is tagged with a deterministic `clientRecordId` of the
 * shape `"aura-seed-<record-kind>-<yyyy-MM-dd>[-<extra>]"`. Health Connect
 * treats inserts that collide on `clientRecordId` (same app data origin)
 * as upserts, so running the seeder twice on the same day produces the
 * same dataset — no duplicates, no unbounded growth. The ViewModel takes
 * advantage of this by re-seeding on every Connect: the first run
 * populates, the second is a no-op upsert, and the user never sees stale
 * demo data hanging around after a Disconnect → Connect cycle.
 *
 * ### "Override the user's real readings today"
 * Separate from idempotency, the seeder also needs today's seeded values
 * to dominate the dashboard — the user's phone may have already written
 * a partial-day StepsRecord (12 steps) or the user's Samsung Health may
 * have pushed a Weight/Height. For the point-in-time records (Weight,
 * Height) we pin today's reading at `now` so the repository's
 * "latest-by-time" query picks ours. For interval records (Steps,
 * ActiveCalories) the dashboard sums across the day, so our large seed
 * value swamps the small real value — the real 12-step record stays in
 * the store but isn't visually noticeable.
 *
 * ### Permissions
 * Writing to HC requires `android.permission.health.WRITE_*` entries in
 * the manifest AND runtime grants. The permission contract in the
 * settings screen asks for both READ + WRITE in a single dialog so the
 * user consents once. If WRITE grants are missing we log and return
 * gracefully rather than throwing — the dashboard degrades to whatever
 * real data the phone already has.
 */
class HealthSampleSeeder(
    private val context: Context,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val random: Random = Random(SEED),
) {
    /**
     * Insert ~14 days of fake readings covering every metric the app
     * consumes. Safe to call on every Connect — the deterministic
     * [CLIENT_ID_PREFIX] makes re-runs upsert rather than duplicate. If
     * the client isn't available or no WRITE permissions are held,
     * returns a failure [SeedResult] without throwing.
     */
    suspend fun seedTwoWeeks(): SeedResult {
        val client = runCatching { HealthConnectClient.getOrCreate(context) }
            .onFailure { Log.w(TAG, "HC client not available", it) }
            .getOrNull()
            ?: return SeedResult(ok = false, inserted = 0, missingPermissions = writePermissions())

        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        val needed = writePermissions()
        val missing = needed - granted
        if (missing.size == needed.size) {
            Log.w(TAG, "seedTwoWeeks: no WRITE perms granted — skipping (missing=$missing)")
            return SeedResult(ok = false, inserted = 0, missingPermissions = missing)
        }
        if (missing.isNotEmpty()) {
            Log.i(TAG, "seedTwoWeeks: partial grant, continuing (missing=$missing)")
        }

        val now = nowProvider()
        val todayStart = now.atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
        // End of today's "bucket" = now, so inserts that cover today
        // span from start-of-day to right now. HC rejects end times in
        // the future, so we can't just use +1 day for today.
        val todayEnd = now

        // Best-effort purge of any prior seed records in the 30-day
        // lookback. Deletes by time range only hit records written by
        // this app's own data origin, so we won't touch real user data
        // from Fit, Samsung Health, etc. This matters on a re-Connect:
        // without it, the old seed's "yesterday" records stack with the
        // new seed's "yesterday" records and the step count doubles.
        val purgeStart = todayStart.minus(Duration.ofDays((DAYS + 1).toLong()))
        runCatching { purgePriorSeedData(client, purgeStart, now) }
            .onFailure { Log.w(TAG, "prior-seed purge failed — pressing on", it) }

        var inserted = 0

        // Steps — one daily bucket per calendar day, start-of-day to
        // end-of-day. Real trackers split into smaller intervals, but the
        // aggregator sums them anyway so one record per day is enough.
        // We cover today too: the real-device reading (e.g. 12 steps
        // before you've moved around) would otherwise dominate today's
        // bar. A 3k–14k seed on top of that makes today look lived-in.
        inserted += safeInsert("steps") {
            val records = (0 until DAYS).map { daysAgo ->
                val dayStart = todayStart.minus(Duration.ofDays(daysAgo.toLong()))
                val isToday = daysAgo == 0
                val end = if (isToday) todayEnd else dayStart.plus(Duration.ofDays(1))
                val count = random.nextLong(3_000, 14_000)
                StepsRecord(
                    startTime = dayStart,
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = end,
                    endZoneOffset = ZoneOffset.UTC,
                    count = count,
                    metadata = seedMetadata(CLIENT_ID_STEPS, daysAgo),
                )
            }
            client.insertRecords(records).recordIdsList.size
        }

        // Resting HR — one morning reading per day.
        inserted += safeInsert("resting_hr") {
            val records = (0 until DAYS).map { daysAgo ->
                val time = todayStart.minus(Duration.ofDays(daysAgo.toLong()))
                    .plus(Duration.ofHours(7))
                    .plus(Duration.ofMinutes(random.nextLong(0, 30)))
                RestingHeartRateRecord(
                    time = time,
                    zoneOffset = ZoneOffset.UTC,
                    beatsPerMinute = random.nextLong(55, 75),
                    metadata = seedMetadata(CLIENT_ID_RESTING_HR, daysAgo),
                )
            }
            client.insertRecords(records).recordIdsList.size
        }

        // Heart rate — a handful of samples per day clustered in daytime.
        // Today's samples are clipped to "before now" — HC rejects
        // sample times in the future, which would otherwise blow up
        // the whole HR insert on a seed run mid-morning.
        inserted += safeInsert("heart_rate") {
            val records = buildList {
                for (daysAgo in 0 until DAYS) {
                    val dayStart = todayStart.minus(Duration.ofDays(daysAgo.toLong()))
                    val samples = (0 until 6).mapNotNull { bucket ->
                        val time = dayStart.plus(Duration.ofHours(8L + bucket * 2))
                        if (!time.isBefore(now)) return@mapNotNull null
                        HeartRateRecord.Sample(
                            time = time,
                            beatsPerMinute = random.nextLong(62, 110),
                        )
                    }
                    if (samples.isEmpty()) continue
                    add(
                        HeartRateRecord(
                            startTime = samples.first().time,
                            startZoneOffset = ZoneOffset.UTC,
                            endTime = samples.last().time.plus(Duration.ofMinutes(1)),
                            endZoneOffset = ZoneOffset.UTC,
                            samples = samples,
                            metadata = seedMetadata(CLIENT_ID_HEART_RATE, daysAgo),
                        ),
                    )
                }
            }
            client.insertRecords(records).recordIdsList.size
        }

        // Sleep — previous-night session, 6.5–8.5h, ending around 7am.
        inserted += safeInsert("sleep") {
            val records = (0 until DAYS).map { daysAgo ->
                val wakeDay = todayStart.atZone(ZoneId.systemDefault()).toLocalDate()
                    .minusDays(daysAgo.toLong())
                val wakeTime = wakeDay.atTime(LocalTime.of(6, random.nextInt(0, 60)))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                val durationMinutes = random.nextLong(6 * 60, (8.5 * 60).toLong())
                val sleepStart = wakeTime.minus(Duration.ofMinutes(durationMinutes))
                SleepSessionRecord(
                    startTime = sleepStart,
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = wakeTime,
                    endZoneOffset = ZoneOffset.UTC,
                    title = "Night sleep",
                    metadata = seedMetadata(CLIENT_ID_SLEEP, daysAgo),
                )
            }
            client.insertRecords(records).recordIdsList.size
        }

        // SpO2 — one evening reading per day (skipped for today if now is
        // earlier than 21:00, since HC rejects future timestamps).
        inserted += safeInsert("spo2") {
            val records = (0 until DAYS).mapNotNull { daysAgo ->
                val time = todayStart.minus(Duration.ofDays(daysAgo.toLong()))
                    .plus(Duration.ofHours(21))
                if (!time.isBefore(now)) return@mapNotNull null
                OxygenSaturationRecord(
                    time = time,
                    zoneOffset = ZoneOffset.UTC,
                    percentage = Percentage(random.nextDouble(95.0, 99.5)),
                    metadata = seedMetadata(CLIENT_ID_SPO2, daysAgo),
                )
            }
            if (records.isEmpty()) 0
            else client.insertRecords(records).recordIdsList.size
        }

        // Respiratory rate — one mid-day reading per day (same future-time
        // guard as SpO2).
        inserted += safeInsert("respiratory_rate") {
            val records = (0 until DAYS).mapNotNull { daysAgo ->
                val time = todayStart.minus(Duration.ofDays(daysAgo.toLong()))
                    .plus(Duration.ofHours(13))
                if (!time.isBefore(now)) return@mapNotNull null
                RespiratoryRateRecord(
                    time = time,
                    zoneOffset = ZoneOffset.UTC,
                    rate = random.nextDouble(12.0, 18.0),
                    metadata = seedMetadata(CLIENT_ID_RESP_RATE, daysAgo),
                )
            }
            if (records.isEmpty()) 0
            else client.insertRecords(records).recordIdsList.size
        }

        // Active calories — one daily sum between 180 and 620 kcal.
        // Today's bucket ends at now (see Steps above for rationale).
        inserted += safeInsert("active_calories") {
            val records = (0 until DAYS).map { daysAgo ->
                val dayStart = todayStart.minus(Duration.ofDays(daysAgo.toLong()))
                val isToday = daysAgo == 0
                val end = if (isToday) todayEnd else dayStart.plus(Duration.ofDays(1))
                ActiveCaloriesBurnedRecord(
                    startTime = dayStart,
                    startZoneOffset = ZoneOffset.UTC,
                    endTime = end,
                    endZoneOffset = ZoneOffset.UTC,
                    energy = Energy.kilocalories(random.nextDouble(180.0, 620.0)),
                    metadata = seedMetadata(CLIENT_ID_ACTIVE_CAL, daysAgo),
                )
            }
            client.insertRecords(records).recordIdsList.size
        }

        // Exercise sessions — one every three days, 30–60min run.
        inserted += safeInsert("exercise") {
            val records = buildList {
                for (daysAgo in 0 until DAYS step 3) {
                    val start = todayStart.minus(Duration.ofDays(daysAgo.toLong()))
                        .plus(Duration.ofHours(18))
                    val duration = Duration.ofMinutes(random.nextLong(30, 60))
                    val end = start.plus(duration)
                    if (!end.isBefore(now) && daysAgo == 0) continue // don't seed a future evening run
                    add(
                        ExerciseSessionRecord(
                            startTime = start,
                            startZoneOffset = ZoneOffset.UTC,
                            endTime = end,
                            endZoneOffset = ZoneOffset.UTC,
                            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                            title = "Evening run",
                            metadata = seedMetadata(CLIENT_ID_EXERCISE, daysAgo),
                        ),
                    )
                }
            }
            if (records.isEmpty()) 0
            else client.insertRecords(records).recordIdsList.size
        }

        // Height — one static reading, timestamped at `now` so it's the
        // latest record and the dashboard's "latest" view shows OUR value
        // (1.78 m) rather than whatever the user already logged today
        // (e.g. 185 cm). HC stores every record; the repository picks
        // the most recent by time.
        inserted += safeInsert("height") {
            val record = HeightRecord(
                time = now,
                zoneOffset = ZoneOffset.UTC,
                height = Length.meters(1.78),
                metadata = seedMetadata(CLIENT_ID_HEIGHT, 0),
            )
            client.insertRecords(listOf(record)).recordIdsList.size
        }

        // Weight — a gently-declining reading per day (75.0 → 73.5 kg).
        // Today's reading is pinned at `now` for the same reason as
        // Height — so the displayed "latest" on the dashboard is the
        // seed value, not any pre-existing user reading from earlier
        // today.
        inserted += safeInsert("weight") {
            val records = (0 until DAYS).map { daysAgo ->
                val isToday = daysAgo == 0
                val time = if (isToday) {
                    now
                } else {
                    todayStart.minus(Duration.ofDays(daysAgo.toLong()))
                        .plus(Duration.ofHours(8))
                }
                val kg = 73.5 + (daysAgo.toDouble() / DAYS) * 1.5 + random.nextDouble(-0.3, 0.3)
                WeightRecord(
                    time = time,
                    zoneOffset = ZoneOffset.UTC,
                    weight = Mass.kilograms(kg),
                    metadata = seedMetadata(CLIENT_ID_WEIGHT, daysAgo),
                )
            }
            client.insertRecords(records).recordIdsList.size
        }

        Log.i(TAG, "seedTwoWeeks: wrote $inserted records (missingPerms=$missing)")
        return SeedResult(
            ok = inserted > 0,
            inserted = inserted,
            missingPermissions = missing,
        )
    }

    /**
     * Delete any records this app previously wrote in the look-back
     * window. HC scopes time-range deletes to the caller's data origin
     * automatically, so other apps' data is safe. Called at the top of
     * [seedTwoWeeks] to guarantee the post-seed state is exactly one
     * seed-pass worth of data.
     */
    private suspend fun purgePriorSeedData(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
    ) {
        val range = TimeRangeFilter.between(start, end)
        val types: List<KClass<out Record>> = listOf(
            StepsRecord::class,
            RestingHeartRateRecord::class,
            HeartRateRecord::class,
            SleepSessionRecord::class,
            OxygenSaturationRecord::class,
            RespiratoryRateRecord::class,
            ActiveCaloriesBurnedRecord::class,
            ExerciseSessionRecord::class,
            HeightRecord::class,
            WeightRecord::class,
        )
        types.forEach { type ->
            runCatching {
                client.deleteRecords(recordType = type, timeRangeFilter = range)
            }.onFailure { Log.w(TAG, "delete $type failed (non-fatal)", it) }
        }
    }

    /**
     * Build a [Metadata] with a deterministic [clientRecordId] so
     * subsequent seed runs upsert rather than duplicate.
     *
     * Health Connect 1.1.0 stable made the [Metadata] constructor
     * `internal` and exposes a `manualEntry(...)` factory in its place.
     * The factory still threads through `clientRecordId`, so the
     * upsert semantics are preserved.
     */
    private fun seedMetadata(kind: String, daysAgo: Int): Metadata =
        Metadata.manualEntry(clientRecordId = "$CLIENT_ID_PREFIX-$kind-d$daysAgo")

    /**
     * The write-permission strings the seeder needs. Mirrors the read
     * list but via `HealthPermission.getWritePermission` — callers pass
     * this to the PermissionController request contract.
     */
    fun writePermissions(): Set<String> = setOf(
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(StepsRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(HeartRateRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(SleepSessionRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(RespiratoryRateRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(HeightRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getWritePermission(WeightRecord::class),
    )

    private suspend inline fun safeInsert(
        label: String,
        crossinline block: suspend () -> Int,
    ): Int = runCatching { block() }
        .onFailure { Log.w(TAG, "seed insert $label failed", it) }
        .onSuccess { Log.d(TAG, "seed insert $label -> $it records") }
        .getOrDefault(0)

    data class SeedResult(
        val ok: Boolean,
        val inserted: Int,
        val missingPermissions: Set<String>,
    )

    companion object {
        private const val TAG = "HealthSampleSeeder"
        private const val DAYS = 14
        private const val SEED: Long = 20260420L

        /**
         * Stable prefix for every seeded record's `clientRecordId`. Used
         * for idempotent upsert — if we ever need a migration, bump
         * [SEED_CLIENT_ID_PREFIX] in `HealthSeedFilter.kt` and the next
         * seed run produces a clean set. Single source of truth lives in
         * the filter file because the read-side filter has to recognise
         * exactly what the write-side wrote.
         */
        private const val CLIENT_ID_PREFIX = SEED_CLIENT_ID_PREFIX
        private const val CLIENT_ID_STEPS = "steps"
        private const val CLIENT_ID_RESTING_HR = "resting-hr"
        private const val CLIENT_ID_HEART_RATE = "heart-rate"
        private const val CLIENT_ID_SLEEP = "sleep"
        private const val CLIENT_ID_SPO2 = "spo2"
        private const val CLIENT_ID_RESP_RATE = "resp-rate"
        private const val CLIENT_ID_ACTIVE_CAL = "active-cal"
        private const val CLIENT_ID_EXERCISE = "exercise"
        private const val CLIENT_ID_HEIGHT = "height"
        private const val CLIENT_ID_WEIGHT = "weight"
    }
}
