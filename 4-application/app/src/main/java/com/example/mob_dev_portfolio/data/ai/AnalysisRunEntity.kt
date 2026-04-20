package com.example.mob_dev_portfolio.data.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.mob_dev_portfolio.data.SymptomLogEntity

/**
 * One completed AI analysis run.
 *
 * Each successful Gemini round-trip produces exactly one of these. The
 * worker writes the row from the background; the Analysis history screen
 * observes the table via a Flow so the UI updates the moment the row
 * lands — no manual refresh, no broadcast.
 *
 * ### Why store the raw summary text on the row?
 *
 * The Gemini response is free-form markdown. Parsing it into a structured
 * correlation list at write time is brittle (the model's schema drifts
 * between releases) and lossy (the prose around each bullet is part of
 * the explanation). We keep the full text so the detail screen can render
 * it verbatim, and layer structured fields (guidance, headline) on top
 * only where we can extract them reliably.
 *
 * ### Primary key
 *
 * Auto-generated. We intentionally avoid content-hashing — two runs made a
 * minute apart with identical input should still appear as two rows so
 * the user can see that they re-ran the analysis.
 */
@Entity(tableName = "analysis_runs")
data class AnalysisRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** When the worker finished writing the row. Drives the "newest first" sort. */
    val completedAtEpochMillis: Long,

    /**
     * Coarse two-bucket bucket used for the notification and the history-list
     * subtitle. Stored as the enum name so a future rename surfaces as a
     * compile error rather than a silent mismatch at read time.
     */
    @ColumnInfo(name = "guidance")
    val guidanceName: String,

    /**
     * One-sentence headline shown on the list row — pulled straight from
     * [AnalysisGuidance.headline] at write time. Duplicated from the enum
     * so future copy tweaks don't retroactively rewrite past runs.
     */
    val headline: String,

    /**
     * Full markdown summary returned by Gemini. The detail screen renders
     * this through the existing `MarkdownRenderer` so bullets, headings,
     * and bold spans all survive.
     */
    val summaryText: String,
)

/**
 * Join table wiring each run to the symptom logs that fed it.
 *
 * The user story's technical guideline is explicit: "link them
 * relationally to the associated symptom entries." A run observes a
 * snapshot of the logs at the moment it was launched; persisting the
 * snapshot means the detail view can still show "you had 12 logs
 * analysed" even after the user deletes some of them.
 *
 * `ON DELETE CASCADE` on the run side wipes the cross-refs when the run
 * row is removed; on the log side we use `SET DEFAULT` through
 * `deferred = false` by NOT cascading — instead we rely on [SymptomLogEntity]
 * keeping its row and the cross-ref retaining a (now-orphaned) logId.
 * That orphan is treated as "historical" in the UI: we show the recorded
 * count but don't try to link back to a log that no longer exists.
 *
 * A composite primary key (runId, logId) prevents duplicates within a
 * single run. The logId index speeds up the reverse lookup ("which runs
 * included this log?"), which we don't use today but is cheap to add and
 * keeps the table queryable for future features.
 */
@Entity(
    tableName = "analysis_run_logs",
    primaryKeys = ["runId", "logId"],
    foreignKeys = [
        ForeignKey(
            entity = AnalysisRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("logId")],
)
data class AnalysisRunLogCrossRef(
    val runId: Long,
    val logId: Long,
)
