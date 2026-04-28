package com.example.mob_dev_portfolio.data.doctor

import android.util.Log
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import kotlinx.coroutines.flow.firstOrNull
import java.time.Duration
import java.time.Instant

/**
 * Demo-flavor seeder that populates a couple of plausible doctor visits
 * with linked symptom logs and diagnoses, so the Doctor Visits tab,
 * the per-log "linked to diagnosis" annotation badge on the symptom
 * detail screen, and the AI pipeline's doctor-context branch all have
 * something to render on first launch without the user having to
 * manually create a visit.
 *
 * ### Ordering constraint
 * This seeder must run **after** [com.example.mob_dev_portfolio.data.SymptomLogSeeder]
 * has populated the symptom log table — visits reference symptom logs
 * by id, so we need real ids to wire up the join tables. The
 * [seedIfEmpty] entrypoint queries [SymptomLogRepository.observeAll]
 * itself; if the symptom seed hasn't landed yet (no logs in the table),
 * the doctor seeder is a no-op and will retry on the next cold start.
 *
 * ### Idempotency
 * Like the symptom seeder, gated on the visits table being empty. A
 * returning user with real visits is never overwritten — even if the
 * symptom seeder were to dilute the log table (it doesn't), the doctor
 * seed bails out the moment any real visit exists.
 *
 * ### Linking strategy
 * Visits link to logs by **predicate, not by id list**. The seed runs
 * "diagnosis = Tension headache, links to every Headache log before
 * the visit date" rather than "links to log ids 3, 5, 7". This survives
 * any future change to the symptom seeder's ordering or count without
 * the doctor seeder needing matching updates.
 */
class DoctorVisitSeeder(
    private val visitRepository: DoctorVisitRepository,
    private val symptomLogRepository: SymptomLogRepository,
    private val nowProvider: () -> Instant = { Instant.now() },
) {

    /**
     * Insert the demo visit set if (and only if) the visits table is
     * empty AND symptom logs already exist to link to. Returns the
     * number of visits written; 0 means the seeder was a no-op.
     */
    suspend fun seedIfEmpty(): Int {
        // Bail if any visit already exists. We want a single source of
        // truth and never want to dilute a returning user's real data.
        val existingVisits = runCatching {
            visitRepository.observeVisits().firstOrNull()
        }.getOrNull()?.size ?: -1
        if (existingVisits != 0) {
            if (existingVisits > 0) {
                Log.i(TAG, "Skipping doctor visit seed — $existingVisits existing visits")
            }
            return 0
        }

        // Need symptom logs to wire visits to. If the symptom seeder
        // hasn't run yet (or ran on the previous cold start but the
        // user immediately uninstalled), wait for the next launch.
        val logs = runCatching {
            symptomLogRepository.observeAll().firstOrNull()
        }.getOrNull().orEmpty()
        if (logs.isEmpty()) {
            Log.i(TAG, "Skipping doctor visit seed — no symptom logs to link to")
            return 0
        }

        val now = nowProvider()
        val drafts = buildSeedVisits(now, logs)
        drafts.forEach { visitRepository.saveVisit(it) }
        Log.i(TAG, "Seeded ${drafts.size} demo doctor visits")
        return drafts.size
    }

    /**
     * Build two plausible visit drafts wired to real symptom log ids:
     *
     *  - **Visit 1, ~6 weeks ago — Dr Sarah Patel, GP**
     *    Reviews the user's recurring headaches. Diagnoses "Tension
     *    headache" and links the older headache logs as supporting
     *    evidence. Old enough that the user has continued to log
     *    headaches *after* the visit — exercises the "linked logs +
     *    new logs of the same symptom" UI.
     *
     *  - **Visit 2, ~10 days ago — Dr James Lee, Internal Medicine**
     *    Reviews the recent fatigue spell. Diagnoses "Iron deficiency
     *    anaemia" linked to fatigue logs. *Clears* the dizziness log
     *    as not-of-concern — exercises the "cleared from AI context"
     *    badge path.
     */
    private fun buildSeedVisits(
        now: Instant,
        logs: List<SymptomLog>,
    ): List<DoctorVisitDraft> {
        val visit1Date = now.minus(Duration.ofDays(42)).toEpochMilli()
        val visit2Date = now.minus(Duration.ofDays(10)).toEpochMilli()

        // Visit 1 — links every Headache log dated before the visit.
        // The newer headaches the user has logged since the visit
        // remain unlinked, so the AI prompt picks them up as fresh
        // context the doctor hasn't seen yet.
        val visit1HeadacheLogIds = logs
            .filter { it.symptomName == "Headache" && it.startEpochMillis < visit1Date }
            .map { it.id }
            .toSet()

        // Visit 2 — links every Fatigue log (the original cluster plus
        // the multi-day spell). Clears the one Dizziness entry as a
        // non-concern: it shows the marker the "this log is greyed out
        // and excluded from AI" rendering path.
        val visit2FatigueLogIds = logs
            .filter { it.symptomName == "Fatigue" }
            .map { it.id }
            .toSet()
        val dizzinessLogIds = logs
            .filter { it.symptomName == "Dizziness" }
            .map { it.id }
            .toSet()

        return listOf(
            DoctorVisitDraft(
                doctorName = "Dr Sarah Patel",
                visitDateEpochMillis = visit1Date,
                summary = "Routine consult about recurring headaches. " +
                    "Discussed sleep hygiene, screen-time breaks, and OTC " +
                    "ibuprofen as first-line relief. No red flags on " +
                    "neuro exam — to review again in 8 weeks if symptoms " +
                    "worsen or new ones appear.",
                coveredLogIds = emptySet(),
                diagnoses = if (visit1HeadacheLogIds.isEmpty()) emptyList() else listOf(
                    DoctorDiagnosisDraft(
                        label = "Tension headache",
                        notes = "Stress- and posture-related. Not migraine — no aura, " +
                            "no photophobia, bilateral pressure rather than throbbing.",
                        linkedLogIds = visit1HeadacheLogIds,
                    ),
                ),
            ),
            DoctorVisitDraft(
                doctorName = "Dr James Lee",
                visitDateEpochMillis = visit2Date,
                summary = "Review of persistent fatigue and one episode of " +
                    "postural dizziness. Bloods drawn — ferritin low at " +
                    "12 μg/L. Started on oral iron. Dizziness most likely " +
                    "secondary to mild orthostatic drop on standing; not " +
                    "investigating further unless it recurs.",
                // Mark the dizziness log "covered & cleared" so the AI
                // skips it on subsequent runs and the LogDetailScreen
                // shows the cleared badge.
                coveredLogIds = dizzinessLogIds,
                diagnoses = if (visit2FatigueLogIds.isEmpty()) emptyList() else listOf(
                    DoctorDiagnosisDraft(
                        label = "Iron deficiency anaemia",
                        notes = "Ferritin 12 μg/L (ref 30–400). Likely contributing " +
                            "to the fatigue cluster. Recheck bloods in 12 weeks " +
                            "after starting ferrous sulphate 200 mg OD.",
                        linkedLogIds = visit2FatigueLogIds,
                    ),
                ),
            ),
        )
    }

    companion object {
        private const val TAG = "DoctorVisitSeeder"
    }
}
