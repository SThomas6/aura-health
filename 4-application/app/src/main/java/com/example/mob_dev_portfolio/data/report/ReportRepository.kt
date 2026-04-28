package com.example.mob_dev_portfolio.data.report

import com.example.mob_dev_portfolio.data.SymptomLogDao
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.ai.AnalysisGuidance
import com.example.mob_dev_portfolio.data.ai.AnalysisRun
import com.example.mob_dev_portfolio.data.ai.AnalysisRunDao
import com.example.mob_dev_portfolio.data.ai.AnalysisRunEntity
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitRepository
import com.example.mob_dev_portfolio.data.photo.SymptomPhotoRepository

/**
 * Assembles the data for a health-report PDF.
 *
 * Two decisions worth flagging:
 *
 * 1. The aggregate queries ([SymptomLogDao.totalCount] and
 *    [SymptomLogDao.averageSeverity]) are pushed down to SQLite rather
 *    than computed in Kotlin — the user story mandates this, and on a
 *    large table it keeps us from loading every row just to average a
 *    single column. For the chronological list we *do* need every row,
 *    so that query loads them all by design.
 *
 * 2. We fan out to the AI analysis DAO in parallel with the symptom-log
 *    DAO; both are read-only snapshots so there's no transactional
 *    coupling between them. The generator is free to interleave or
 *    separate them as the layout dictates.
 *
 * ### Photos (FR-PA-06)
 * Each [ReportLog] carries any photo attachments as decrypted JPEG
 * bytes so the PDF generator can decode + scale them on the fly.
 * We deliberately hand over the plaintext bytes rather than [java.io.File]
 * references: the generator runs on the UI process and has no key
 * access of its own, so pre-decrypting here keeps the crypto surface
 * narrow and doesn't leave plaintext scratch files on disk.
 */
open class ReportRepository(
    private val symptomLogDao: SymptomLogDao,
    private val analysisRunDao: AnalysisRunDao,
    /**
     * Optional so tests that don't care about photos can pass null. In
     * production the AppContainer always supplies the real one.
     */
    private val photoRepository: SymptomPhotoRepository? = null,
    /**
     * Optional doctor-visit repo, used to pull the set of "cleared" log
     * ids so [loadReportSnapshot] can hide them from the PDF by default.
     * Nullable so the existing test doubles still construct without
     * having to spin up the doctor data layer.
     */
    private val doctorVisitRepository: DoctorVisitRepository? = null,
) {

    /**
     * Snapshot the full report payload.
     *
     * This is a suspending one-shot by design — the user taps "Generate"
     * and expects a single consistent document, not a live-updating
     * one. If a new log lands mid-generation it'll be in the *next*
     * report, not this one.
     *
     * @param includeClearedLogs When false (the default), any log
     *   marked "cleared" by a doctor visit is dropped from the
     *   chronological logs section. The aggregate counters
     *   ([ReportSnapshot.totalLogCount], [ReportSnapshot.averageSeverity])
     *   still reflect the full table because they're documented as
     *   "everything ever logged" — hiding cleared items from those
     *   would silently understate the user's history. The PDF screen
     *   surfaces this as a "Show all symptoms (incl. cleared)" toggle.
     */
    open suspend fun loadReportSnapshot(
        generatedAtEpochMillis: Long = System.currentTimeMillis(),
        includeClearedLogs: Boolean = false,
    ): ReportSnapshot {
        val rawLogs = symptomLogDao.listChronologicalAsc()
        val clearedIds: Set<Long> = if (includeClearedLogs) {
            emptySet()
        } else {
            runCatching { doctorVisitRepository?.snapshotForAnalysis()?.clearedLogIds }
                .getOrNull()
                .orEmpty()
        }
        val logs = rawLogs
            .filterNot { it.id in clearedIds }
            .map { entity ->
                val photos = photoRepository?.let { repo ->
                    // Pull the row list first, then decrypt each into bytes.
                    // Corrupt / missing files drop silently — the PDF still
                    // renders without a phantom placeholder.
                    repo.listForLog(entity.id)
                        .mapNotNull { repo.readBytes(it) }
                }.orEmpty()
                entity.toReportLog(photos)
            }
        val analyses = analysisRunDao.listChronologicalAsc().map { it.toReportAnalysis() }
        val total = symptomLogDao.totalCount()
        val average = symptomLogDao.averageSeverity()
        return ReportSnapshot(
            generatedAtEpochMillis = generatedAtEpochMillis,
            logs = logs,
            analyses = analyses,
            totalLogCount = total,
            averageSeverity = average,
            includesClearedLogs = includeClearedLogs,
            hiddenClearedCount = if (includeClearedLogs) 0 else clearedIds.count { id ->
                rawLogs.any { it.id == id }
            },
        )
    }
}

/**
 * Flat, UI-friendly view of everything the PDF generator needs.
 *
 * Kept as its own type (rather than reusing `SymptomLog` / `AnalysisRun`
 * from other layers) so the report pipeline is decoupled from any
 * future changes to those domain models — and so the generator never
 * depends on UI-facing types.
 */
data class ReportSnapshot(
    val generatedAtEpochMillis: Long,
    val logs: List<ReportLog>,
    val analyses: List<ReportAnalysis>,
    /** Result of `SELECT COUNT(*) FROM symptom_logs`. */
    val totalLogCount: Int,
    /**
     * Result of `SELECT AVG(severity) FROM symptom_logs`. Null when the
     * logs table is empty — SQLite returns NULL on AVG of zero rows.
     */
    val averageSeverity: Double?,
    /**
     * True when the snapshot was generated with cleared logs included.
     * The PDF generator uses this to render an explanatory note at the
     * top of the chronological logs section so a doctor reading the
     * printout knows whether they're looking at the curated or full
     * timeline.
     */
    val includesClearedLogs: Boolean = true,
    /**
     * Number of cleared logs that were dropped from [logs]. Zero when
     * [includesClearedLogs] is true.
     */
    val hiddenClearedCount: Int = 0,
) {
    val hasContent: Boolean get() = logs.isNotEmpty() || analyses.isNotEmpty()
}

/** Compact row for the chronological logs section. */
data class ReportLog(
    val id: Long,
    val symptomName: String,
    val description: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val severity: Int,
    val medication: String,
    val notes: String,
    val contextTags: String,
    val locationName: String?,
    val weatherDescription: String?,
    val temperatureCelsius: Double?,
    /**
     * Decrypted JPEG bytes for each attached photo, in capture order.
     * Empty when the log has no attachments — the PDF generator skips
     * the photo row entirely in that case.
     */
    val photoJpegBytes: List<ByteArray> = emptyList(),
)

/** Compact row for the AI insights section. */
data class ReportAnalysis(
    val id: Long,
    val completedAtEpochMillis: Long,
    val guidance: AnalysisGuidance,
    val headline: String,
    val summaryText: String,
)

private fun SymptomLogEntity.toReportLog(photos: List<ByteArray> = emptyList()): ReportLog = ReportLog(
    id = id,
    symptomName = symptomName,
    description = description,
    startEpochMillis = startEpochMillis,
    endEpochMillis = endEpochMillis,
    severity = severity,
    medication = medication,
    notes = notes,
    contextTags = contextTags,
    locationName = locationName,
    weatherDescription = weatherDescription,
    temperatureCelsius = temperatureCelsius,
    photoJpegBytes = photos,
)

private fun AnalysisRunEntity.toReportAnalysis(): ReportAnalysis = ReportAnalysis(
    id = id,
    completedAtEpochMillis = completedAtEpochMillis,
    // Fall back defensively so an old row with an unknown bucket still
    // renders cleanly — same strategy as [AnalysisHistoryRepository].
    guidance = runCatching { AnalysisGuidance.valueOf(guidanceName) }
        .getOrDefault(AnalysisGuidance.Clear),
    headline = headline,
    summaryText = summaryText,
)

/**
 * Helper shim so tests and the UI can convert a domain [AnalysisRun]
 * into the report shape without touching Room.
 */
internal fun AnalysisRun.toReportAnalysis(): ReportAnalysis = ReportAnalysis(
    id = id,
    completedAtEpochMillis = completedAtEpochMillis,
    guidance = guidance,
    headline = headline,
    summaryText = summaryText,
)
