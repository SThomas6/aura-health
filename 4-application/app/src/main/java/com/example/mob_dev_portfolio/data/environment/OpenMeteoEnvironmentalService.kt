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
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Open-Meteo-backed [EnvironmentalService].
 *
 * - **No API key** required — this provider is free and unauthenticated, which
 *   is important for a graded demo that must run on a fresh install.
 * - Two endpoints are fetched in parallel (forecast + air-quality) inside a
 *   single coroutine scope. If one fails but the other succeeds we still
 *   return [EnvironmentalFetchResult.Success] with a partial snapshot rather
 *   than losing the good half.
 * - The caller wraps this in `withTimeout(5_000)`. If that fires,
 *   [kotlinx.coroutines.TimeoutCancellationException] propagates out of here
 *   unchanged — the VM catches it and emits [EnvironmentalFetchResult.Timeout].
 *   We do **not** swallow it here, because cooperative coroutine cancellation
 *   must bubble up for `withTimeout` to function correctly.
 *
 * OkHttp's own read/connect timeouts are deliberately generous (4s) so that
 * the outer `withTimeout(5_000)` is the authoritative SLA — we never want
 * OkHttp to throw a SocketTimeoutException just a few ms before the outer
 * timeout fires and produce two competing error messages.
 */
class OpenMeteoEnvironmentalService(
    private val client: OkHttpClient = defaultClient(),
    private val forecastBaseUrl: String = FORECAST_URL,
    private val airQualityBaseUrl: String = AIR_QUALITY_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EnvironmentalService {

    override suspend fun fetch(latitude: Double, longitude: Double): EnvironmentalFetchResult =
        coroutineScope {
            // Parallel fetch — the 5s budget is shared across both calls.
            val forecastDeferred = async(ioDispatcher) {
                runCatching { fetchForecast(latitude, longitude) }
            }
            val airQualityDeferred = async(ioDispatcher) {
                runCatching { fetchAirQuality(latitude, longitude) }
            }

            val forecastResult = forecastDeferred.await()
            val airQualityResult = airQualityDeferred.await()

            // If BOTH endpoints failed, surface a specific failure mode.
            if (forecastResult.isFailure && airQualityResult.isFailure) {
                return@coroutineScope toFailure(
                    forecastResult.exceptionOrNull() ?: airQualityResult.exceptionOrNull()!!,
                )
            }

            val forecast = forecastResult.getOrNull() ?: EnvironmentalSnapshot.EMPTY
            val air = airQualityResult.getOrNull() ?: EnvironmentalSnapshot.EMPTY

            EnvironmentalFetchResult.Success(
                EnvironmentalSnapshot(
                    weatherCode = forecast.weatherCode,
                    weatherDescription = forecast.weatherDescription,
                    temperatureCelsius = forecast.temperatureCelsius,
                    humidityPercent = forecast.humidityPercent,
                    pressureHpa = forecast.pressureHpa,
                    airQualityIndex = air.airQualityIndex,
                ),
            )
        }

    private fun toFailure(error: Throwable): EnvironmentalFetchResult = when (error) {
        // Order matters — HttpStatusException extends IOException, so the
        // specific case must be checked first.
        is HttpStatusException -> EnvironmentalFetchResult.ApiError(
            "Weather service error (${error.code}).",
        )
        is UnknownHostException -> EnvironmentalFetchResult.NoNetwork
        is IOException -> {
            Log.w(TAG, "Environmental fetch IO failure", error)
            EnvironmentalFetchResult.NoNetwork
        }
        is JSONException -> {
            Log.w(TAG, "Environmental fetch parse failure", error)
            EnvironmentalFetchResult.ApiError("Couldn't read weather data.")
        }
        else -> {
            Log.w(TAG, "Environmental fetch unexpected failure", error)
            EnvironmentalFetchResult.ApiError("Couldn't read weather data.")
        }
    }

    private suspend fun fetchForecast(latitude: Double, longitude: Double): EnvironmentalSnapshot =
        withContext(ioDispatcher) {
            val url = buildUrl(
                base = forecastBaseUrl,
                params = mapOf(
                    "latitude" to latitude.toString(),
                    "longitude" to longitude.toString(),
                    "current" to "temperature_2m,relative_humidity_2m,pressure_msl,weather_code",
                ),
            )
            val json = executeJson(url)
            val current = json.optJSONObject("current")
                ?: throw JSONException("Missing 'current' block in forecast response")
            val code = current.optNullableInt("weather_code")
            EnvironmentalSnapshot(
                weatherCode = code,
                weatherDescription = describeWeatherCode(code),
                temperatureCelsius = current.optNullableDouble("temperature_2m"),
                humidityPercent = current.optNullableInt("relative_humidity_2m"),
                pressureHpa = current.optNullableDouble("pressure_msl"),
            )
        }

    private suspend fun fetchAirQuality(latitude: Double, longitude: Double): EnvironmentalSnapshot =
        withContext(ioDispatcher) {
            val url = buildUrl(
                base = airQualityBaseUrl,
                params = mapOf(
                    "latitude" to latitude.toString(),
                    "longitude" to longitude.toString(),
                    "current" to "european_aqi",
                ),
            )
            val json = executeJson(url)
            val current = json.optJSONObject("current")
                ?: throw JSONException("Missing 'current' block in air-quality response")
            EnvironmentalSnapshot(
                airQualityIndex = current.optNullableInt("european_aqi"),
            )
        }

    private fun executeJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code)
            }
            val body = response.body?.string()
                ?: throw JSONException("Empty response body")
            return JSONObject(body)
        }
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        val separator = if (base.contains('?')) '&' else '?'
        return "$base$separator$query"
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    companion object {
        private const val TAG = "OpenMeteoService"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val AIR_QUALITY_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }
}

private fun JSONObject.optNullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
