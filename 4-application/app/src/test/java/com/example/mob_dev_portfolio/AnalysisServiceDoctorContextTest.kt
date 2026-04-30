package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.ai.AnalysisRequest
import com.example.mob_dev_portfolio.data.ai.AnalysisResult
import com.example.mob_dev_portfolio.data.ai.AnalysisService
import com.example.mob_dev_portfolio.data.ai.GeminiClient
import com.example.mob_dev_portfolio.data.doctor.DoctorContextSnapshot
import com.example.mob_dev_portfolio.data.preferences.UserProfile
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
 * Pins the AI ↔ doctor-visit contract: cleared logs never leave the
 * sanitiser, diagnosed logs carry their label, and the known-diagnoses
 * block is built (and stripped) correctly.
 *
 * The request/response pair is captured by a recording fake client —
 * no network, no DB. Every assertion here is on the [AnalysisRequest]
 * that *would* be sent, which is the real contract surface.
 */
class AnalysisServiceDoctorContextTest {

    private class RecordingClient : GeminiClient {
        var received: AnalysisRequest? = null
        override suspend fun analyze(request: AnalysisRequest): AnalysisResult {
            received = request
            return AnalysisResult.Success("ok")
        }
    }

    @Test
    fun cleared_log_ids_are_excluded_from_payload() = runTest {
        val client = RecordingClient()
        val service = AnalysisService(client = client, nowProvider = { utc(2026, 4, 19) })

        val logs = listOf(
            log(id = 1L, name = "Migraine", day = 15),
            log(id = 2L, name = "Nausea", day = 16),
            log(id = 3L, name = "Fatigue", day = 17),
        )
        val snapshot = DoctorContextSnapshot(
            clearedLogIds = setOf(2L),
            diagnosisLabelByLogId = emptyMap(),
            diagnosisLabels = emptyMap(),
            linkedLogIdsByDiagnosis = emptyMap(),
        )

        service.analyze(
            profile = UserProfile(),
            userContext = "",
            logs = logs,
            doctorContext = snapshot,
        )

        val sent = requireNotNull(client.received)
        val names = sent.logs.map { it.symptomName }
        assertEquals(listOf("Fatigue", "Migraine"), names)
        assertFalse("cleared log leaked through", names.contains("Nausea"))
    }

    @Test
    fun linked_logs_carry_diagnosis_label_annotation() = runTest {
        val client = RecordingClient()
        val service = AnalysisService(client = client, nowProvider = { utc(2026, 4, 19) })

        val logs = listOf(log(id = 10L, name = "Headache", day = 12))
        val snapshot = DoctorContextSnapshot(
            clearedLogIds = emptySet(),
            diagnosisLabelByLogId = mapOf(10L to "Chronic migraine"),
            diagnosisLabels = mapOf(99L to "Chronic migraine"),
            linkedLogIdsByDiagnosis = mapOf(99L to setOf(10L)),
        )

        service.analyze(
            profile = UserProfile(),
            userContext = "",
            logs = logs,
            doctorContext = snapshot,
        )

        val sent = requireNotNull(client.received)
        val only = sent.logs.single()
        assertEquals("Chronic migraine", only.diagnosisLabel)
    }

    @Test
    fun known_diagnoses_block_is_populated_sorted_and_excludes_cleared_history() = runTest {
        val client = RecordingClient()
        val service = AnalysisService(client = client, nowProvider = { utc(2026, 4, 19) })

        // Two diagnoses, labels intentionally out of alpha order so we can
        // assert the sort. Diagnosis 200 has two linked logs; one of them
        // is cleared and must NOT appear in the brief history even though
        // the diagnosis itself still does.
        val logs = listOf(
            log(id = 1L, name = "Headache", day = 10),
            log(id = 2L, name = "Pressure", day = 12),
            log(id = 3L, name = "Dizziness", day = 14),
        )
        val snapshot = DoctorContextSnapshot(
            clearedLogIds = setOf(1L),
            diagnosisLabelByLogId = mapOf(2L to "Migraine", 3L to "Anaemia"),
            diagnosisLabels = mapOf(
                100L to "Migraine",
                200L to "Anaemia",
            ),
            linkedLogIdsByDiagnosis = mapOf(
                100L to setOf(1L, 2L),
                200L to setOf(3L),
            ),
        )

        val request = service.buildRequest(
            profile = UserProfile(),
            userContext = "",
            logs = logs,
            doctorContext = snapshot,
        )

        // Alphabetical ordering: Anaemia before Migraine.
        assertEquals(listOf("Anaemia", "Migraine"), request.knownDiagnoses.map { it.label })

        val migraine = request.knownDiagnoses.first { it.label == "Migraine" }
        val migraineLogNames = migraine.history.map { it.symptomName }
        // Headache (id 1) was cleared, so it's suppressed from the history
        // even though the DB says it's linked.
        assertEquals(listOf("Pressure"), migraineLogNames)
    }

    @Test
    fun diagnosis_label_strips_patient_name() = runTest {
        val client = RecordingClient()
        val service = AnalysisService(client = client, nowProvider = { utc(2026, 4, 19) })

        val profile = UserProfile(
            fullName = "Jane Doe",
            dateOfBirthEpochMillis = utc(1996, 1, 1),
        )
        val logs = listOf(log(id = 5L, name = "Back pain", day = 15))
        val snapshot = DoctorContextSnapshot(
            clearedLogIds = emptySet(),
            diagnosisLabelByLogId = mapOf(5L to "Jane's lumbar strain"),
            diagnosisLabels = mapOf(42L to "Jane's lumbar strain"),
            linkedLogIdsByDiagnosis = mapOf(42L to setOf(5L)),
        )

        service.analyze(profile, "", logs, doctorContext = snapshot)

        val sent = requireNotNull(client.received)
        val annotation = sent.logs.single().diagnosisLabel
        assertNotNull(annotation)
        requireNotNull(annotation)
        assertFalse("name 'Jane' leaked through annotation", annotation.contains("Jane", ignoreCase = true))

        val knownLabel = sent.knownDiagnoses.single().label
        assertFalse("name 'Jane' leaked through knownDiagnoses", knownLabel.contains("Jane", ignoreCase = true))
    }

    @Test
    fun empty_doctor_context_produces_no_annotation_and_no_known_block() = runTest {
        val client = RecordingClient()
        val service = AnalysisService(client = client, nowProvider = { utc(2026, 4, 19) })

        service.analyze(
            profile = UserProfile(),
            userContext = "",
            logs = listOf(log(id = 1L, name = "Cough", day = 15)),
        )

        val sent = requireNotNull(client.received)
        assertNull(sent.logs.single().diagnosisLabel)
        assertTrue(sent.knownDiagnoses.isEmpty())
    }

    private fun log(id: Long, name: String, day: Int): SymptomLog = SymptomLog(
        id = id,
        symptomName = name,
        description = "",
        startEpochMillis = utc(2026, 4, day),
        endEpochMillis = null,
        severity = 5,
        medication = "",
        contextTags = emptyList(),
        notes = "",
        createdAtEpochMillis = utc(2026, 4, day),
    )

    private fun utc(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day)
        return cal.timeInMillis
    }
}
