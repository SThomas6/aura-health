package com.example.mob_dev_portfolio.data

import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Developer-facing seeder that populates the symptom log table with a
 * month of plausible-looking entries so graphs, filters, reports, and
 * the AI analysis prompt have something to chew on at first launch.
 *
 * Design choices:
 *
 *  - Writes **only when the table is empty.** A returning user who has
 *    real logs must never have them diluted by demo data, so we gate
 *    hard on the row count rather than on a per-seeder "did we run
 *    this build" flag.
 *
 *  - Spreads four distinct symptom names across ~30 days with repeats,
 *    so the Trends screen's "most-frequent symptom" fallback has a
 *    clear winner, and the overlay/correlation views have multiple
 *    series to compare.
 *
 *  - One of the logs is **multi-day** — this exercises the new
 *    multi-day expansion path in the Trends ViewModel (one plot per
 *    day of the span) which is otherwise only visible if a user happens
 *    to log a long-running symptom themselves.
 *
 *  - Each log is stamped with approximate London coordinates (51.51N,
 *    -0.13W) so the environmental-history fetcher has a location it
 *    can resolve from the "most recent log" heuristic; without that,
 *    the env overlays on the Trends page silently fall back to
 *    Unavailable.
 *
 *  - Environmental snapshot fields (temperature, humidity, pressure,
 *    AQI) are populated with climatologically plausible April values
 *    so the per-day env *point* columns the Logs page surfaces aren't
 *    empty — the continuous Trends overlays come from the separate
 *    historical-weather repository at render time.
 *
 * This is deliberately a Kotlin class with a single `seedIfEmpty`
 * entrypoint rather than a Room callback, so the AppContainer can
 * choose *when* to run it (after database init, off the main thread).
 */
class SymptomLogSeeder(
    private val repository: SymptomLogRepository,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val random: Random = Random(SEED),
) {

    /**
     * Insert the demo data set if (and only if) the log table is empty.
     * Returns the number of rows written — 0 means the seeder was a
     * no-op because the user already has real data.
     */
    suspend fun seedIfEmpty(): Int {
        // Peek the first emission from observeAll(). Using the DAO's
        // flow (rather than a one-shot suspend count) keeps this
        // seeder decoupled from the DAO surface — it relies only on
        // the repository's public API.
        val existing = runCatching { repository.observeAll().firstOrNull() }
            .getOrNull()
            ?.size
            ?: -1
        if (existing != 0) {
            if (existing > 0) {
                Log.i(TAG, "Skipping symptom seed — $existing existing logs")
            }
            return 0
        }
        val logs = buildSeedLogs(nowProvider())
        logs.forEach { repository.save(it) }
        Log.i(TAG, "Seeded ${logs.size} demo symptom logs")
        return logs.size
    }

    private fun buildSeedLogs(now: Instant): List<SymptomLog> {
        // Headache anchors the set — the most-frequent symptom so the
        // Trends screen's fallback picks it by default on a first
        // launch before the user taps anything.
        val headacheOffsets = listOf(0.5, 1.8, 3.2, 5.5, 8.1, 12.3, 17.4, 22.0, 27.5)
        val fatigueOffsets = listOf(2.0, 6.5, 10.3, 15.0, 20.2, 25.8)
        val nauseaOffsets = listOf(4.0, 9.5, 18.7, 24.1)
        // One explicitly multi-day entry: a four-day fatigue spell.
        val multiDayStartDaysAgo = 14.0

        val logs = mutableListOf<SymptomLog>()

        headacheOffsets.forEachIndexed { i, daysAgo ->
            logs += demoLog(
                now = now,
                symptom = "Headache",
                description = "Throbbing behind the eyes, worse in afternoon.",
                severity = wobble(base = 5, spread = 3),
                daysAgo = daysAgo,
                durationMinutes = 45L + random.nextLong(0, 180),
                notes = if (i % 3 == 0) "Took ibuprofen, faded after an hour." else "",
                medication = if (i % 2 == 0) "Ibuprofen 400mg" else "",
                tags = listOf("home", "evening").take(1 + (i % 2)),
            )
        }
        fatigueOffsets.forEach { daysAgo ->
            logs += demoLog(
                now = now,
                symptom = "Fatigue",
                description = "Low energy, heavy limbs.",
                severity = wobble(base = 4, spread = 2),
                daysAgo = daysAgo,
                durationMinutes = 90L + random.nextLong(0, 300),
                notes = "",
                medication = "",
                tags = listOf("work"),
            )
        }
        nauseaOffsets.forEach { daysAgo ->
            logs += demoLog(
                now = now,
                symptom = "Nausea",
                description = "Slight queasiness, no vomiting.",
                severity = wobble(base = 3, spread = 2),
                daysAgo = daysAgo,
                durationMinutes = 30L + random.nextLong(0, 90),
                notes = "Resolved with rest.",
                medication = "",
                tags = listOf("morning"),
            )
        }

        // Multi-day fatigue spell — start 14 days ago, 4 days long.
        logs += demoLog(
            now = now,
            symptom = "Fatigue",
            description = "Lingering tiredness after a bad night's sleep.",
            severity = 6,
            daysAgo = multiDayStartDaysAgo,
            durationMinutes = Duration.ofDays(4).toMinutes(),
            notes = "Spanned several days — struggled to focus at work.",
            medication = "",
            tags = listOf("chronic", "work"),
        )

        // One recent dizziness entry so the symptom dropdown has
        // variety even though it's not the most common.
        logs += demoLog(
            now = now,
            symptom = "Dizziness",
            description = "Brief spinning when standing up.",
            severity = 2,
            daysAgo = 1.2,
            durationMinutes = 15,
            notes = "Passed within minutes.",
            medication = "",
            tags = listOf("standing"),
        )

        return logs.sortedBy { it.startEpochMillis }
    }

    private fun demoLog(
        now: Instant,
        symptom: String,
        description: String,
        severity: Int,
        daysAgo: Double,
        durationMinutes: Long,
        notes: String,
        medication: String,
        tags: List<String>,
    ): SymptomLog {
        val startMs = now.minusMillis((daysAgo * DAY_MS).toLong()).toEpochMilli()
        val endMs = startMs + durationMinutes * 60_000L
        // April-in-London-ish climatology; just enough variance that
        // the Logs detail view shows different numbers per entry.
        val temp = 9.0 + random.nextDouble(-3.0, 8.0)
        val humidity = (55 + random.nextInt(-15, 25)).coerceIn(30, 95)
        val pressure = 1013.0 + random.nextDouble(-12.0, 12.0)
        val aqi = (35 + random.nextInt(-15, 40)).coerceIn(10, 120)

        return SymptomLog(
            id = 0L,
            symptomName = symptom,
            description = description,
            startEpochMillis = startMs,
            endEpochMillis = endMs,
            severity = severity.coerceIn(1, 10),
            medication = medication,
            contextTags = tags,
            notes = notes,
            createdAtEpochMillis = startMs,
            locationLatitude = LONDON_LAT,
            locationLongitude = LONDON_LON,
            locationName = "London, UK",
            weatherCode = 3,
            weatherDescription = "Overcast",
            temperatureCelsius = temp,
            humidityPercent = humidity,
            pressureHpa = pressure,
            airQualityIndex = aqi,
        )
    }

    private fun wobble(base: Int, spread: Int): Int {
        val offset = random.nextInt(-spread, spread + 1)
        return (base + offset).coerceIn(1, 10)
    }

    companion object {
        private const val TAG = "SymptomLogSeeder"
        private const val DAY_MS = 24.0 * 60 * 60 * 1000
        // Fixed seed so the demo set looks the same between installs —
        // great for screenshots and acceptance-testing.
        private const val SEED: Long = 0x5EEDCAFE
        private const val LONDON_LAT = 51.5072
        private const val LONDON_LON = -0.1276
    }
}

