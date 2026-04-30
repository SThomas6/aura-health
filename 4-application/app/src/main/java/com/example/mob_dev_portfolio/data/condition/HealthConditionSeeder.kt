package com.example.mob_dev_portfolio.data.condition

import android.util.Log
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import kotlinx.coroutines.flow.firstOrNull
import java.time.Duration
import java.time.Instant

/**
 * Demo-flavor seeder that populates a few user-declared health
 * conditions and pins matching symptom logs to them, so the
 * "Group under condition" picker on the symptom editor and the
 * grouped sections on the History screen have something to render
 * out of the box.
 *
 * ### Distinction from [com.example.mob_dev_portfolio.data.doctor.DoctorVisitSeeder]
 * That seeder writes doctor visits + diagnoses; those are tied to a
 * specific clinic interaction. This seeder writes **standing** health
 * conditions the user is asserting about themselves (e.g. "Migraine",
 * "Mild anxiety") with no visit attached. Both feed the AI's doctor-
 * context bundle but they're surfaced as distinct concepts in the UI.
 *
 * ### Ordering constraint
 * Must run **after** [com.example.mob_dev_portfolio.data.SymptomLogSeeder]
 * has populated the symptom log table — conditions reference logs by
 * id when pinning. Should also run **after**
 * [com.example.mob_dev_portfolio.data.doctor.DoctorVisitSeeder] so we
 * can avoid double-linking the same log to both a diagnosis and a
 * condition (the editor picker will eventually treat the two as
 * mutually exclusive; keeping the seed honest now means no migration
 * pain later).
 *
 * ### Idempotency
 * Gated on the `health_conditions` table being empty. Re-runs are
 * harmless: a returning user with real conditions is never overwritten,
 * and even a partially-seeded run would just bail.
 */
class HealthConditionSeeder(
    private val conditionRepository: HealthConditionRepository,
    private val symptomLogRepository: SymptomLogRepository,
    private val nowProvider: () -> Instant = { Instant.now() },
) {

    /**
     * Insert the demo condition set if (and only if) the conditions
     * table is empty AND symptom logs already exist to link to.
     * Returns the number of conditions written; 0 means the seeder
     * was a no-op.
     */
    suspend fun seedIfEmpty(): Int {
        val existingConditions = runCatching {
            conditionRepository.observeAll().firstOrNull()
        }.getOrNull()?.size ?: -1
        if (existingConditions != 0) {
            if (existingConditions > 0) {
                Log.i(TAG, "Skipping condition seed — $existingConditions existing conditions")
            }
            return 0
        }

        val logs = runCatching {
            symptomLogRepository.observeAll().firstOrNull()
        }.getOrNull().orEmpty()
        if (logs.isEmpty()) {
            Log.i(TAG, "Skipping condition seed — no symptom logs to link to")
            return 0
        }

        val now = nowProvider()
        val drafts = buildSeedConditions(now, logs)
        drafts.forEach { draft ->
            val conditionId = conditionRepository.upsert(
                name = draft.name,
                notes = draft.notes,
            )
            if (conditionId != 0L) {
                draft.linkedLogIds.forEach { logId ->
                    conditionRepository.setLogCondition(logId, conditionId)
                }
            }
        }
        Log.i(TAG, "Seeded ${drafts.size} demo health conditions")
        return drafts.size
    }

    /**
     * Build the demo condition set wired to real symptom log ids.
     *
     *  - **Migraine** — links the more *recent* headache logs (those
     *    newer than the doctor's tension-headache visit ~6 weeks ago)
     *    so we don't double-link with the diagnosis from
     *    [DoctorVisitSeeder]. Models the user asserting a long-standing
     *    migraine pattern alongside the doctor's narrower episode-level
     *    diagnosis.
     *  - **Mild anxiety** — links every nausea log; nausea isn't
     *    covered by the doctor seed, so this slot is free.
     *  - **Hypothyroidism** — standalone condition, no log links.
     *    Demonstrates the picker's "condition with no auto-grouped
     *    logs yet" affordance.
     */
    private fun buildSeedConditions(
        now: Instant,
        logs: List<SymptomLog>,
    ): List<HealthConditionDraft> {
        // Mirror the cut-off used by DoctorVisitSeeder so the seed
        // sets stay disjoint without the two seeders having to share
        // state.
        val visit1Date = now.minus(Duration.ofDays(42)).toEpochMilli()

        val recentHeadacheLogIds = logs
            .filter { it.symptomName == "Headache" && it.startEpochMillis >= visit1Date }
            .map { it.id }
            .toSet()
        val nauseaLogIds = logs
            .filter { it.symptomName == "Nausea" }
            .map { it.id }
            .toSet()

        return listOf(
            HealthConditionDraft(
                name = "Migraine",
                notes = "Long-standing pattern of episodic migraines. " +
                    "Distinct from the tension headaches the GP has seen.",
                linkedLogIds = recentHeadacheLogIds,
            ),
            HealthConditionDraft(
                name = "Mild anxiety",
                notes = "Self-managed; can manifest as queasiness or nausea " +
                    "during stressful weeks at work.",
                linkedLogIds = nauseaLogIds,
            ),
            HealthConditionDraft(
                name = "Hypothyroidism",
                notes = "Diagnosed years ago, well-controlled on levothyroxine.",
                linkedLogIds = emptySet(),
            ),
        )
    }

    /**
     * Internal draft type used while building the seed set — kept
     * private to the seeder so the public repo surface (which already
     * has its own `upsert` shape) doesn't grow a parallel data class.
     */
    private data class HealthConditionDraft(
        val name: String,
        val notes: String,
        val linkedLogIds: Set<Long>,
    )

    companion object {
        private const val TAG = "HealthConditionSeeder"
    }
}
