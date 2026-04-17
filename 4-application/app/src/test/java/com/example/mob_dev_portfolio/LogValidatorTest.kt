package com.example.mob_dev_portfolio

import com.example.mob_dev_portfolio.ui.log.LogDraft
import com.example.mob_dev_portfolio.ui.log.LogField
import com.example.mob_dev_portfolio.ui.log.LogValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogValidatorTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `valid draft passes`() {
        val draft = LogDraft(
            symptomName = "Headache",
            description = "Dull ache on the left side",
            startEpochMillis = now - 60_000,
            severity = 4,
        )
        val result = LogValidator.validate(draft, now = now)
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `missing symptom name is reported`() {
        val draft = LogDraft(
            symptomName = "  ",
            description = "x",
            startEpochMillis = now,
        )
        val result = LogValidator.validate(draft, now = now)
        assertFalse(result.isValid)
        assertEquals("Symptom type is required", result.errors[LogField.SymptomName])
    }

    @Test
    fun `missing description is reported`() {
        val draft = LogDraft(
            symptomName = "Nausea",
            description = "",
            startEpochMillis = now,
        )
        val result = LogValidator.validate(draft, now = now)
        assertFalse(result.isValid)
        assertEquals("Description is required", result.errors[LogField.Description])
    }

    @Test
    fun `aggregates all invalid fields at once`() {
        val draft = LogDraft(
            symptomName = "",
            description = "",
            startEpochMillis = now + 10 * 60_000L,
            hasEnded = true,
            endEpochMillis = null,
            severity = 99,
        )
        val result = LogValidator.validate(draft, now = now)
        assertFalse(result.isValid)
        assertTrue(result.errors.containsKey(LogField.SymptomName))
        assertTrue(result.errors.containsKey(LogField.Description))
        assertTrue(result.errors.containsKey(LogField.StartDateTime))
        assertTrue(result.errors.containsKey(LogField.EndDateTime))
        assertTrue(result.errors.containsKey(LogField.Severity))
        assertEquals(5, result.errors.size)
    }

    @Test
    fun `end before start is rejected`() {
        val draft = LogDraft(
            symptomName = "Headache",
            description = "x",
            startEpochMillis = now,
            hasEnded = true,
            endEpochMillis = now - 5_000,
        )
        val result = LogValidator.validate(draft, now = now)
        assertFalse(result.isValid)
        assertTrue(result.errors.containsKey(LogField.EndDateTime))
    }

    @Test
    fun `future start within skew is allowed`() {
        val draft = LogDraft(
            symptomName = "Headache",
            description = "x",
            startEpochMillis = now + 30_000,
            severity = 5,
        )
        val result = LogValidator.validate(draft, now = now)
        assertTrue(result.isValid)
    }

    @Test
    fun `severity boundaries`() {
        val names = listOf("Headache", "Headache", "Headache")
        val cases = listOf(0, 1, 10)
        val expectedValid = listOf(false, true, true)
        cases.zip(expectedValid).forEachIndexed { i, (sev, valid) ->
            val draft = LogDraft(
                symptomName = names[i],
                description = "x",
                startEpochMillis = now,
                severity = sev,
            )
            val result = LogValidator.validate(draft, now = now)
            assertEquals("severity=$sev", valid, result.isValid)
        }
    }

    @Test
    fun `summary is line-separated bullets`() {
        val draft = LogDraft(symptomName = "", description = "", startEpochMillis = now)
        val result = LogValidator.validate(draft, now = now)
        val summary = result.summary()
        assertTrue(summary.contains("Symptom type is required"))
        assertTrue(summary.contains("Description is required"))
        assertTrue(summary.startsWith("• "))
    }
}
