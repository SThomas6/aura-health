package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.ai.AnalysisRequest
import com.example.mob_dev_portfolio.data.ai.AnalysisResult
import com.example.mob_dev_portfolio.data.ai.HttpGeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Wire-level tests for the Gemini adapter. Uses MockWebServer so the OkHttp
 * call stack, JSON body shape, and candidate-parsing logic are all exercised
 * without hitting the real endpoint.
 *
 * Two things matter most here:
 *   1. The outbound POST body must be well-formed Gemini `generateContent`
 *      shape — anything else would surface as an opaque 400 from Google.
 *   2. The sanitized request must round-trip through the wire without
 *      leaking names or DOB (re-asserted from a different layer than the
 *      sanitizer unit test, to catch regressions in the prompt builder).
 */
class HttpGeminiClientTest {

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
    fun happy_path_returns_success_with_text() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "candidates": [
                    { "content": { "parts": [ { "text": "Here is a summary." } ] } }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = newClient().analyze(minimalRequest())

        assertTrue("expected Success, got $result", result is AnalysisResult.Success)
        assertEquals("Here is a summary.", (result as AnalysisResult.Success).summaryText)
    }

    @Test
    fun post_body_is_valid_gemini_shape() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""",
            ),
        )

        newClient().analyze(minimalRequest())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        // URL path shape includes the model slug and :generateContent suffix.
        val path = recorded.path.orEmpty()
        assertTrue("path missing :generateContent — was $path", path.contains(":generateContent"))
        assertTrue("path missing model slug — was $path", path.contains("gemini-3.1-flash-lite-preview"))
        assertTrue("path missing api key query param — was $path", path.contains("key="))

        val body = JSONObject(recorded.body.readUtf8())
        val contents = body.getJSONArray("contents")
        assertEquals(1, contents.length())
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        val text = parts.getJSONObject(0).getString("text")
        assertTrue("prompt missing age range bucket", text.contains("25-34"))
    }

    @Test
    fun post_body_does_not_leak_pii() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""",
            ),
        )

        // Build a request that deliberately still smells like PII — but since
        // AnalysisRequest is the post-sanitization shape, the client must
        // never re-introduce anything that wasn't in these fields.
        val sanitized = AnalysisRequest(
            ageRange = "25-34",
            biologicalSex = null,
            userContext = "feels rough today",
            healthSummary = null,
            logs = listOf(
                AnalysisRequest.SanitizedLog(
                    symptomName = "Migraine",
                    severity = 8,
                    startIsoDate = "2026-04-15",
                    description = "pressure behind the eyes",
                    notes = "rest helped a little",
                    contextTags = listOf("stress"),
                    weatherDescription = "Rain",
                    temperatureCelsius = 12.0,
                    humidityPercent = 80,
                    pressureHpa = 1005.0,
                    airQualityIndex = 28,
                    locationName = "Cardiff, UK",
                    healthAggregate24h = null,
                ),
            ),
        )
        newClient().analyze(sanitized)

        val body = server.takeRequest().body.readUtf8()
        // Nothing name-shaped should appear. These are the canonical fakes
        // used across the AnalysisSanitizerTest and AnalysisServicePayloadTest.
        assertFalse("name 'Jane' leaked", body.contains("Jane", ignoreCase = true))
        assertFalse("name 'Doe' leaked", body.contains("Doe", ignoreCase = true))
        // A raw DOB millis value — 820454400000 is 1996-01-01 UTC.
        assertFalse("DOB millis leaked", body.contains("820454400000"))
    }

    @Test
    fun http_500_surfaces_api_error() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = newClient().analyze(minimalRequest())

        assertTrue("expected ApiError, got $result", result is AnalysisResult.ApiError)
    }

    @Test
    fun malformed_json_surfaces_api_error() = runTest {
        server.enqueue(MockResponse().setBody("{not-json"))

        val result = newClient().analyze(minimalRequest())

        assertTrue("expected ApiError, got $result", result is AnalysisResult.ApiError)
    }

    @Test
    fun no_candidates_surfaces_api_error() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[]}"""))

        val result = newClient().analyze(minimalRequest())

        assertTrue("expected ApiError, got $result", result is AnalysisResult.ApiError)
    }

    @Test
    fun blank_api_key_short_circuits_without_hitting_network() = runTest {
        val client = HttpGeminiClient(
            apiKey = "",
            model = "gemini-3.1-flash-lite-preview",
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = client.analyze(minimalRequest())

        assertTrue("expected ApiError, got $result", result is AnalysisResult.ApiError)
        assertEquals(0, server.requestCount)
    }

    private fun newClient(): HttpGeminiClient =
        HttpGeminiClient(
            apiKey = "fake-key",
            model = "gemini-3.1-flash-lite-preview",
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun minimalRequest(): AnalysisRequest = AnalysisRequest(
        ageRange = "25-34",
        biologicalSex = null,
        userContext = "",
        healthSummary = null,
        logs = emptyList(),
    )
}
