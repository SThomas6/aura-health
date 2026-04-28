package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.ai.AnalysisRequest
import com.example.mob_dev_portfolio.data.ai.AnalysisResult
import com.example.mob_dev_portfolio.data.ai.AnalysisService
import com.example.mob_dev_portfolio.data.ai.GeminiClient
import com.example.mob_dev_portfolio.data.preferences.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * End-to-end check that the request the [AnalysisService] builds is PII-free
 * before it reaches the [GeminiClient]. This is the assertion the story
 * pinned in its privacy acceptance criterion: "the payload sent to the
 * Gemini API contains an age range (not a specific DOB) and no names."
 *
 * We verify this without touching HTTP — a recording fake client captures
 * the request object and the test inspects every string field for leaks.
 */
class AnalysisServicePayloadTest {

    private class RecordingClient : GeminiClient {
        var received: AnalysisRequest? = null
        override suspend fun analyze(request: AnalysisRequest): AnalysisResult {
            received = request
            return AnalysisResult.Success("ok")
        }
    }

    @Test
    fun request_contains_age_range_and_no_dob_or_name() = runTest {
        val client = RecordingClient()
        val now = utc(2026, 4, 19)
        val service = AnalysisService(client = client, nowProvider = { now })

        val profile = UserProfile(
            fullName = "Jane Doe",
            dateOfBirthEpochMillis = utc(1996, 1, 1), // 30 yrs old
        )
        val userContext = "Jane has been struggling with migraines lately."
        val logs = listOf(
            SymptomLog(
                id = 1L,
                symptomName = "Migraine",
                description = "Jane felt pressure behind eyes.",
                startEpochMillis = utc(2026, 4, 15),
                endEpochMillis = null,
                severity = 7,
                medication = "",
                contextTags = listOf("stress"),
                notes = "Doe called Jane after work.",
                createdAtEpochMillis = utc(2026, 4, 15),
                locationName = "Cardiff, UK",
                weatherDescription = "Rain",
                temperatureCelsius = 12.0,
                humidityPercent = 80,
                pressureHpa = 1005.0,
                airQualityIndex = 28,
            ),
        )

        service.analyze(profile, userContext, logs)

        val sent = client.received
        assertNotNull("service did not forward a request to the client", sent)
        requireNotNull(sent)

        // Age range is present, DOB is not.
        assertEquals("25-34", sent.ageRange)
        val allText = listOf(
            sent.userContext,
            sent.logs.joinToString(" ") { buildString {
                append(it.symptomName); append(' ')
                append(it.description); append(' ')
                append(it.notes); append(' ')
                append(it.contextTags.joinToString(","))
            } },
        ).joinToString(" ")

        assertFalse("name 'Jane' leaked through", allText.contains("Jane", ignoreCase = true))
        assertFalse("name 'Doe' leaked through", allText.contains("Doe", ignoreCase = true))
        // The raw DOB ms value must never appear verbatim either.
        assertFalse(
            "DOB millis leaked through",
            allText.contains(profile.dateOfBirthEpochMillis!!.toString()),
        )
    }

    @Test
    fun empty_profile_still_produces_usable_request() = runTest {
        val client = RecordingClient()
        val service = AnalysisService(client = client, nowProvider = { utc(2026, 4, 19) })

        service.analyze(profile = UserProfile(), userContext = "hello", logs = emptyList())

        val sent = client.received
        assertNotNull(sent)
        requireNotNull(sent)
        assertEquals("Unknown", sent.ageRange)
        assertEquals("hello", sent.userContext)
        assertTrue(sent.logs.isEmpty())
    }

    @Test
    fun logs_are_capped_and_sorted_most_recent_first() = runTest {
        val client = RecordingClient()
        // Tight cap so we can assert the cut-off cheaply.
        val service = AnalysisService(client = client, maxLogs = 2, nowProvider = { utc(2026, 4, 19) })

        val logs = (1..5).map { i ->
            SymptomLog(
                id = i.toLong(),
                symptomName = "Log$i",
                description = "",
                startEpochMillis = utc(2026, 4, i + 10),
                endEpochMillis = null,
                severity = 5,
                medication = "",
                contextTags = emptyList(),
                notes = "",
                createdAtEpochMillis = utc(2026, 4, i + 10),
            )
        }

        service.analyze(profile = UserProfile(), userContext = "", logs = logs)

        val sent = client.received
        assertNotNull(sent)
        requireNotNull(sent)
        assertEquals(2, sent.logs.size)
        // Most-recent first ordering — Log5 before Log4.
        assertEquals("Log5", sent.logs[0].symptomName)
        assertEquals("Log4", sent.logs[1].symptomName)
    }

    @Test
    fun build_request_is_deterministic_for_equal_inputs() {
        // Same inputs + same clock = same request. Important because the
        // payload is what the reviewer will inspect; flakiness here would
        // undermine the privacy audit.
        val a = AnalysisService(client = RecordingClient(), nowProvider = { utc(2026, 4, 19) })
        val b = AnalysisService(client = RecordingClient(), nowProvider = { utc(2026, 4, 19) })
        val profile = UserProfile(fullName = "Sam", dateOfBirthEpochMillis = utc(1990, 6, 15))

        val r1 = a.buildRequest(profile, "hello", emptyList())
        val r2 = b.buildRequest(profile, "hello", emptyList())

        assertEquals(r1, r2)
        // Defensive: the name never appears in the derived request.
        assertFalse(r1.toString().contains("Sam"))
        assertNull(r1.logs.firstOrNull())
    }

    private fun utc(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day)
        return cal.timeInMillis
    }
}
