package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.environment.EnvironmentalFetchResult
import com.example.mob_dev_portfolio.data.environment.OpenMeteoEnvironmentalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Wire-level tests for the Open-Meteo adapter. Uses MockWebServer to avoid
 * real network I/O while still exercising the OkHttp call stack, JSON
 * parsing, and the parallel-fetch merge logic.
 *
 * Requests are routed by path (`/forecast` vs `/airquality`) rather than by
 * queue order because the two endpoints are fetched in parallel — there is
 * no guaranteed arrival order.
 */
class OpenMeteoEnvironmentalServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun happy_path_populates_all_fields() = runTest {
        server.dispatcher = routing(
            forecast = MockResponse().setBody(
                """
                {
                  "current": {
                    "temperature_2m": 18.4,
                    "relative_humidity_2m": 63,
                    "pressure_msl": 1012.3,
                    "weather_code": 3
                  }
                }
                """.trimIndent(),
            ),
            airQuality = MockResponse().setBody(
                """{ "current": { "european_aqi": 28 } }""",
            ),
        )

        val service = newService()
        val result = service.fetch(51.51, -3.18)

        assertTrue(result is EnvironmentalFetchResult.Success)
        val snap = (result as EnvironmentalFetchResult.Success).snapshot
        assertEquals(18.4, snap.temperatureCelsius!!, 1e-6)
        assertEquals(63, snap.humidityPercent!!)
        assertEquals(1012.3, snap.pressureHpa!!, 1e-6)
        assertEquals(3, snap.weatherCode!!)
        assertEquals("Overcast", snap.weatherDescription)
        assertEquals(28, snap.airQualityIndex!!)
    }

    @Test
    fun lat_and_long_are_forwarded_to_both_endpoints() = runTest {
        val seen = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request.path ?: ""
                return MockResponse().setBody(
                    when {
                        request.path!!.startsWith("/forecast") ->
                            """{ "current": { "temperature_2m": 10.0, "relative_humidity_2m": 50, "pressure_msl": 1000.0, "weather_code": 0 } }"""
                        else -> """{ "current": { "european_aqi": 15 } }"""
                    },
                )
            }
        }

        newService().fetch(51.51, -3.18)

        // Both endpoints must receive the rounded pair unchanged.
        val joined = seen.joinToString("\n")
        assertTrue("forecast call missing lat/lng", joined.contains("latitude=51.51"))
        assertTrue("forecast call missing lat/lng", joined.contains("longitude=-3.18"))
    }

    @Test
    fun partial_success_preserves_good_half_when_air_quality_fails() = runTest {
        server.dispatcher = routing(
            forecast = MockResponse().setBody(
                """{ "current": { "temperature_2m": 12.0, "relative_humidity_2m": 80, "pressure_msl": 1005.0, "weather_code": 61 } }""",
            ),
            airQuality = MockResponse().setResponseCode(503),
        )

        val result = newService().fetch(51.51, -3.18)

        assertTrue(result is EnvironmentalFetchResult.Success)
        val snap = (result as EnvironmentalFetchResult.Success).snapshot
        // Forecast side survived.
        assertEquals(12.0, snap.temperatureCelsius!!, 1e-6)
        assertEquals("Rain", snap.weatherDescription)
        // Air quality failed → null, not an ApiError. Partial success is fine.
        assertNull(snap.airQualityIndex)
    }

    @Test
    fun both_endpoints_failing_surfaces_api_error() = runTest {
        server.dispatcher = routing(
            forecast = MockResponse().setResponseCode(500),
            airQuality = MockResponse().setResponseCode(500),
        )

        val result = newService().fetch(51.51, -3.18)

        assertTrue(
            "expected ApiError, got $result",
            result is EnvironmentalFetchResult.ApiError,
        )
    }

    @Test
    fun malformed_json_surfaces_api_error() = runTest {
        server.dispatcher = routing(
            forecast = MockResponse().setBody("not-json-at-all"),
            airQuality = MockResponse().setBody("also-broken"),
        )

        val result = newService().fetch(51.51, -3.18)

        assertTrue(
            "expected ApiError, got $result",
            result is EnvironmentalFetchResult.ApiError,
        )
    }

    @Test
    fun missing_current_block_surfaces_api_error() = runTest {
        server.dispatcher = routing(
            forecast = MockResponse().setBody("""{ "metadata": {} }"""),
            airQuality = MockResponse().setBody("""{ "metadata": {} }"""),
        )

        val result = newService().fetch(51.51, -3.18)

        assertTrue(
            "expected ApiError, got $result",
            result is EnvironmentalFetchResult.ApiError,
        )
    }

    @Test
    fun null_json_field_becomes_null_kotlin_value_not_zero() = runTest {
        // Open-Meteo returns `null` for missing stations; we must not
        // confuse that with the valid value 0.
        server.dispatcher = routing(
            forecast = MockResponse().setBody(
                """{ "current": { "temperature_2m": null, "relative_humidity_2m": 55, "pressure_msl": 1010.0, "weather_code": null } }""",
            ),
            airQuality = MockResponse().setBody("""{ "current": { "european_aqi": 20 } }"""),
        )

        val result = newService().fetch(51.51, -3.18)

        assertTrue(result is EnvironmentalFetchResult.Success)
        val snap = (result as EnvironmentalFetchResult.Success).snapshot
        assertNull(snap.temperatureCelsius)
        assertNull(snap.weatherCode)
        assertNull(snap.weatherDescription) // derived from weatherCode, also null
        assertEquals(55, snap.humidityPercent!!)
        assertNotNull(snap.airQualityIndex)
    }

    private fun routing(forecast: MockResponse, airQuality: MockResponse): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when {
                    request.path?.startsWith("/forecast") == true -> forecast
                    request.path?.startsWith("/airquality") == true -> airQuality
                    else -> MockResponse().setResponseCode(404)
                }
        }

    private fun newService(): OpenMeteoEnvironmentalService {
        val base = server.url("/").toString().trimEnd('/')
        return OpenMeteoEnvironmentalService(
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            forecastBaseUrl = "$base/forecast",
            airQualityBaseUrl = "$base/airquality",
            ioDispatcher = Dispatchers.Unconfined,
        )
    }
}
