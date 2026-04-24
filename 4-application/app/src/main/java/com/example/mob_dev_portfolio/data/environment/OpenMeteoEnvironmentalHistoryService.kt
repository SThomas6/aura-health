package com.example.mob_dev_portfolio.data.environment

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Open-Meteo-backed implementation of [EnvironmentalHistoryService].
 *
 * Two upstream endpoints participate:
 *   • The **forecast** endpoint (same as the point-in-time service) accepts
 *     `past_days=N` for windows within the last ~92 days — that covers
 *     Week and Month Trends ranges with a single call per dimension.
 *   • The **archive** endpoint takes explicit `start_date` / `end_date`
 *     and serves arbitrary historical ranges — used for 6 months and 1
 *     year.
 *
 * Weather and air-quality always arrive from *different* hostnames, so
 * we issue them in parallel via `async`; if one fails but the other
 * succeeds we still return the half we got rather than failing outright.
 */
class OpenMeteoEnvironmentalHistoryService(
    private val client: OkHttpClient = defaultClient(),
    private val forecastBaseUrl: String = OpenMeteoEnvironmentalService.FORECAST_URL,
    private val archiveBaseUrl: String = ARCHIVE_URL,
    private val airQualityForecastUrl: String = OpenMeteoEnvironmentalService.AIR_QUALITY_URL,
    private val airQualityArchiveUrl: String = AIR_QUALITY_ARCHIVE_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EnvironmentalHistoryService {

    override suspend fun fetchHistory(
        latitude: Double,
        longitude: Double,
        start: Instant,
        end: Instant,
        granularity: EnvironmentalHistoryService.Granularity,
    ): HistoryResult = coroutineScope {
        val weatherDeferred = async(ioDispatcher) {
            runCatching { fetchWeather(latitude, longitude, start, end, granularity) }
        }
        val aqiDeferred = async(ioDispatcher) {
            runCatching { fetchAirQuality(latitude, longitude, start, end, granularity) }
        }

        val weatherResult = weatherDeferred.await()
        val aqiResult = aqiDeferred.await()

        if (weatherResult.isFailure && aqiResult.isFailure) {
            Log.w(TAG, "Both history fetches failed", weatherResult.exceptionOrNull())
            return@coroutineScope HistoryResult.Unavailable
        }

        val weatherSamples: Map<Instant, EnvironmentalSample> =
            weatherResult.getOrNull()?.associateBy { it.time } ?: emptyMap()
        val aqiSamples: Map<Instant, EnvironmentalSample> =
            aqiResult.getOrNull()?.associateBy { it.time } ?: emptyMap()

        // Outer join on timestamp — weather and AQI might have slight
        // alignment drift; we keep every time that appeared anywhere and
        // fill the missing side with nulls.
        val allTimes = (weatherSamples.keys + aqiSamples.keys).sorted()
        val merged = allTimes.map { t ->
            val w = weatherSamples[t]
            val a = aqiSamples[t]
            EnvironmentalSample(
                time = t,
                temperatureCelsius = w?.temperatureCelsius,
                humidityPercent = w?.humidityPercent,
                pressureHpa = w?.pressureHpa,
                airQualityIndex = a?.airQualityIndex,
            )
        }
        HistoryResult.Success(merged)
    }

    // ── Weather ────────────────────────────────────────────────────────

    private suspend fun fetchWeather(
        latitude: Double,
        longitude: Double,
        start: Instant,
        end: Instant,
        granularity: EnvironmentalHistoryService.Granularity,
    ): List<EnvironmentalSample> = withContext(ioDispatcher) {
        val useArchive = useArchive(start, end)
        val base = if (useArchive) archiveBaseUrl else forecastBaseUrl
        val params = weatherParams(latitude, longitude, start, end, granularity, useArchive)
        val json = executeJson(buildUrl(base, params))
        parseWeather(json, granularity)
    }

    private fun weatherParams(
        latitude: Double,
        longitude: Double,
        start: Instant,
        end: Instant,
        granularity: EnvironmentalHistoryService.Granularity,
        useArchive: Boolean,
    ): Map<String, String> {
        val common = mutableMapOf(
            "latitude" to latitude.toString(),
            "longitude" to longitude.toString(),
            "timezone" to "UTC",
        )
        when (granularity) {
            EnvironmentalHistoryService.Granularity.Hourly ->
                common["hourly"] = "temperature_2m,relative_humidity_2m,pressure_msl"
            EnvironmentalHistoryService.Granularity.Daily ->
                common["daily"] = "temperature_2m_mean,relative_humidity_2m_mean,pressure_msl_mean"
        }
        if (useArchive) {
            common["start_date"] = DATE_FORMATTER.format(start.atZone(ZoneOffset.UTC).toLocalDate())
            common["end_date"] = DATE_FORMATTER.format(end.atZone(ZoneOffset.UTC).toLocalDate())
        } else {
            // Forecast endpoint: ask for "past N days including today".
            // +1 because past_days doesn't include today's partial data.
            val days = java.time.Duration.between(start, end).toDays().coerceIn(1, 92).toInt()
            common["past_days"] = days.toString()
            common["forecast_days"] = "1"
        }
        return common
    }

    private fun parseWeather(
        json: JSONObject,
        granularity: EnvironmentalHistoryService.Granularity,
    ): List<EnvironmentalSample> = when (granularity) {
        EnvironmentalHistoryService.Granularity.Hourly -> parseSection(
            json = json,
            sectionKey = "hourly",
            tempKey = "temperature_2m",
            humidityKey = "relative_humidity_2m",
            pressureKey = "pressure_msl",
        )
        EnvironmentalHistoryService.Granularity.Daily -> parseSection(
            json = json,
            sectionKey = "daily",
            tempKey = "temperature_2m_mean",
            humidityKey = "relative_humidity_2m_mean",
            pressureKey = "pressure_msl_mean",
        )
    }

    private fun parseSection(
        json: JSONObject,
        sectionKey: String,
        tempKey: String,
        humidityKey: String,
        pressureKey: String,
    ): List<EnvironmentalSample> {
        val block = json.optJSONObject(sectionKey) ?: return emptyList()
        val times = block.optJSONArray("time") ?: return emptyList()
        val temps = block.optJSONArray(tempKey)
        val hums = block.optJSONArray(humidityKey)
        val press = block.optJSONArray(pressureKey)
        return (0 until times.length()).mapNotNull { i ->
            val time = parseInstant(times.optString(i)) ?: return@mapNotNull null
            EnvironmentalSample(
                time = time,
                temperatureCelsius = temps?.optDoubleOrNull(i),
                humidityPercent = hums?.optDoubleOrNull(i)?.toInt(),
                pressureHpa = press?.optDoubleOrNull(i),
            )
        }
    }

    // ── AQI ────────────────────────────────────────────────────────────

    private suspend fun fetchAirQuality(
        latitude: Double,
        longitude: Double,
        start: Instant,
        end: Instant,
        granularity: EnvironmentalHistoryService.Granularity,
    ): List<EnvironmentalSample> = withContext(ioDispatcher) {
        val useArchive = useArchive(start, end)
        val base = if (useArchive) airQualityArchiveUrl else airQualityForecastUrl
        val params = aqiParams(latitude, longitude, start, end, granularity, useArchive)
        val json = executeJson(buildUrl(base, params))
        // AQI archive endpoint returns hourly even when asked for daily
        // (no native daily aggregation for AQI), so we always read hourly
        // and downsample when needed.
        val hourly = parseAqiHourly(json)
        if (granularity == EnvironmentalHistoryService.Granularity.Daily) {
            downsampleAqiToDaily(hourly)
        } else {
            hourly
        }
    }

    private fun aqiParams(
        latitude: Double,
        longitude: Double,
        start: Instant,
        end: Instant,
        granularity: EnvironmentalHistoryService.Granularity,
        useArchive: Boolean,
    ): Map<String, String> {
        val common = mutableMapOf(
            "latitude" to latitude.toString(),
            "longitude" to longitude.toString(),
            "timezone" to "UTC",
            "hourly" to "european_aqi",
        )
        if (useArchive) {
            common["start_date"] = DATE_FORMATTER.format(start.atZone(ZoneOffset.UTC).toLocalDate())
            common["end_date"] = DATE_FORMATTER.format(end.atZone(ZoneOffset.UTC).toLocalDate())
        } else {
            val days = java.time.Duration.between(start, end).toDays().coerceIn(1, 92).toInt()
            common["past_days"] = days.toString()
            common["forecast_days"] = "1"
        }
        // `granularity` isn't materially used here — AQI is always hourly
        // — but kept in the signature for symmetry with the weather fn.
        @Suppress("UNUSED_VARIABLE")
        val g = granularity
        return common
    }

    private fun parseAqiHourly(json: JSONObject): List<EnvironmentalSample> {
        val block = json.optJSONObject("hourly") ?: return emptyList()
        val times = block.optJSONArray("time") ?: return emptyList()
        val aqi = block.optJSONArray("european_aqi")
        return (0 until times.length()).mapNotNull { i ->
            val t = parseInstant(times.optString(i)) ?: return@mapNotNull null
            EnvironmentalSample(time = t, airQualityIndex = aqi?.optDoubleOrNull(i)?.toInt())
        }
    }

    /**
     * Fold hourly AQI samples into one per UTC day by taking the mean of
     * the readings on that day. Days with no reading are dropped.
     */
    private fun downsampleAqiToDaily(hourly: List<EnvironmentalSample>): List<EnvironmentalSample> =
        hourly
            .groupBy { it.time.atZone(ZoneOffset.UTC).toLocalDate() }
            .map { (date, samples) ->
                val values = samples.mapNotNull { it.airQualityIndex }
                EnvironmentalSample(
                    time = date.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    airQualityIndex = if (values.isEmpty()) null
                    else (values.sum().toDouble() / values.size).toInt(),
                )
            }
            .sortedBy { it.time }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun useArchive(start: Instant, end: Instant): Boolean {
        val days = java.time.Duration.between(start, end).toDays()
        return days > 90
    }

    private fun executeJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw JSONException("Empty body")
            return JSONObject(body)
        }
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val separator = if (base.contains('?')) '&' else '?'
        return "$base$separator$query"
    }

    private fun encode(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")

    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrEmpty()) return null
        return runCatching {
            // Open-Meteo returns ISO-local without a zone when timezone=UTC;
            // parse as local-date-time and attach UTC.
            LocalDate.parse(value.take(10)).let { date ->
                if (value.length >= 16) {
                    val time = java.time.LocalTime.parse(value.substring(11, 16))
                    date.atTime(time).toInstant(ZoneOffset.UTC)
                } else {
                    date.atStartOfDay(ZoneOffset.UTC).toInstant()
                }
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "OpenMeteoHistSvc"
        const val ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
        const val AIR_QUALITY_ARCHIVE_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"

        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

// Small helper — the Android SDK's JSONArray.optDouble returns NaN for
// missing / null / non-numeric positions, which is the exact value we
// want to translate to `null` for the UI.
private fun JSONArray.optDoubleOrNull(index: Int): Double? {
    if (isNull(index)) return null
    val v = optDouble(index)
    return if (v.isNaN()) null else v
}

/** Reserved for zone-aware future overloads — not used today. */
@Suppress("unused")
private val utcZone: ZoneId = ZoneId.of("UTC")
