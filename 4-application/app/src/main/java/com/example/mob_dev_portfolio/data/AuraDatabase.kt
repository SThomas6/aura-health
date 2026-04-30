package com.example.mob_dev_portfolio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.mob_dev_portfolio.data.ai.AnalysisRunDao
import com.example.mob_dev_portfolio.data.ai.AnalysisRunEntity
import com.example.mob_dev_portfolio.data.ai.AnalysisRunLogCrossRef
import com.example.mob_dev_portfolio.data.doctor.DoctorDiagnosisDao
import com.example.mob_dev_portfolio.data.doctor.DoctorDiagnosisEntity
import com.example.mob_dev_portfolio.data.doctor.DoctorDiagnosisLog
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitCoveredLog
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitDao
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitEntity
import com.example.mob_dev_portfolio.data.medication.DoseEventDao
import com.example.mob_dev_portfolio.data.medication.DoseEventEntity
import com.example.mob_dev_portfolio.data.medication.MedicationReminderDao
import com.example.mob_dev_portfolio.data.medication.MedicationReminderEntity
import com.example.mob_dev_portfolio.data.photo.SymptomPhotoDao
import com.example.mob_dev_portfolio.data.photo.SymptomPhotoEntity
import com.example.mob_dev_portfolio.data.report.ReportArchiveDao
import com.example.mob_dev_portfolio.data.report.ReportArchiveEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Single-source-of-truth Room database, opened on top of SQLCipher via
 * the `SupportOpenHelperFactory` so every byte at rest is encrypted with
 * the per-install passphrase resolved by [com.example.mob_dev_portfolio.data.security.DatabasePassphraseProvider].
 *
 * Schema lives across many entities (symptom logs, AI runs, reports,
 * medication, photos, doctor visits, conditions). Each feature owns its
 * tables in its own package; this class wires the DAOs up. The
 * `version`/migration list grows monotonically — old installs walk the
 * full migration chain on first launch after upgrade, never skipping
 * steps. `exportSchema = false` because we don't ship a JSON schema
 * artefact for the assessment build.
 *
 * The companion's [get] enforces a single instance per process (the
 * standard double-checked-locking pattern) so all DAOs share the same
 * connection pool.
 */
@Database(
    entities = [
        SymptomLogEntity::class,
        AnalysisRunEntity::class,
        AnalysisRunLogCrossRef::class,
        ReportArchiveEntity::class,
        MedicationReminderEntity::class,
        DoseEventEntity::class,
        SymptomPhotoEntity::class,
        DoctorVisitEntity::class,
        DoctorVisitCoveredLog::class,
        DoctorDiagnosisEntity::class,
        DoctorDiagnosisLog::class,
        com.example.mob_dev_portfolio.data.condition.HealthConditionEntity::class,
        com.example.mob_dev_portfolio.data.condition.HealthConditionLog::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class AuraDatabase : RoomDatabase() {

    abstract fun symptomLogDao(): SymptomLogDao

    abstract fun analysisRunDao(): AnalysisRunDao

    abstract fun reportArchiveDao(): ReportArchiveDao

    abstract fun medicationReminderDao(): MedicationReminderDao

    abstract fun doseEventDao(): DoseEventDao

    abstract fun symptomPhotoDao(): SymptomPhotoDao

    abstract fun doctorVisitDao(): DoctorVisitDao

    abstract fun doctorDiagnosisDao(): DoctorDiagnosisDao

    abstract fun healthConditionDao(): com.example.mob_dev_portfolio.data.condition.HealthConditionDao

    companion object {
        @Volatile
        private var instance: AuraDatabase? = null

        /**
         * Adds nullable location columns for the privacy-preserving location capture feature.
         * Nullable because location is opt-in per-log; existing rows should remain null.
         */
        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE symptom_logs ADD COLUMN locationLatitude REAL")
                db.execSQL("ALTER TABLE symptom_logs ADD COLUMN locationLongitude REAL")
            }
        }

        /**
         * Adds the human-readable `locationName` column. Reverse geocoding runs once
         * at save time, so the resulting string is persisted and read back verbatim —
         * we never re-geocode on UI bind. Nullable so rows saved before this migration
         * surface the "Location unavailable" fallback without crashing.
         */
        private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE symptom_logs ADD COLUMN locationName TEXT")
            }
        }

        /**
         * Adds nullable environmental metric columns for the Environmental Data
         * Retrieval story. Every column is nullable because the API call is
         * best-effort: timeouts, offline saves, and HTTP errors must all
         * persist a row with null metrics rather than losing the symptom log.
         */
        private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE symptom_logs ADD COLUMN weatherCode INTEGER")
                db.execSQL("ALTER TABLE symptom_logs ADD COLUMN weatherDescription TEXT")
                db.execSQL("ALTER TABLE symptom_logs ADD COLUMN temperatureCelsius REAL")
                db.execSQL("ALTER TABLE symptom_logs ADD COLUMN humidityPercent INTEGER")
                db.execSQL("ALTER TABLE symptom_logs ADD COLUMN pressureHpa REAL")
                db.execSQL("ALTER TABLE symptom_logs ADD COLUMN airQualityIndex INTEGER")
            }
        }

        /**
         * Adds the `analysis_runs` table and its `analysis_run_logs` join
         * table for the AI Analysis History & Detail story. All columns are
         * non-nullable except where the app treats "unknown" as a real state
         * — the worker only ever inserts a complete row, so fields like
         * `summaryText` and `guidance` are always populated. Existing users
         * see an empty history list until their next analysis completes.
         */
        private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS analysis_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        completedAtEpochMillis INTEGER NOT NULL,
                        guidance TEXT NOT NULL,
                        headline TEXT NOT NULL,
                        summaryText TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS analysis_run_logs (
                        runId INTEGER NOT NULL,
                        logId INTEGER NOT NULL,
                        PRIMARY KEY(runId, logId),
                        FOREIGN KEY(runId) REFERENCES analysis_runs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_analysis_run_logs_logId ON analysis_run_logs(logId)",
                )
            }
        }

        /**
         * Adds the `report_archives` table for the PDF Report History &
         * File Management story. One row per generated PDF, indexed
         * uniquely on the compressed file name so the generator's
         * insert-on-success path can use ABORT as a safety net.
         * `averageSeverity` is REAL + nullable because SQLite's AVG()
         * returns NULL over zero rows.
         */
        private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS report_archives (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        compressedFileName TEXT NOT NULL,
                        generatedAtEpochMillis INTEGER NOT NULL,
                        uncompressedBytes INTEGER NOT NULL,
                        compressedBytes INTEGER NOT NULL,
                        totalLogCount INTEGER NOT NULL,
                        averageSeverity REAL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_report_archives_compressedFileName ON report_archives(compressedFileName)",
                )
            }
        }

        /**
         * Adds the nullable `healthMetricsCsv` column to `analysis_runs`
         * for the Health Connect integration story. Existing rows carry
         * NULL, which the detail screen treats as "no health data
         * available for this historical run". New runs write a CSV of
         * the short labels of metrics that were both enabled and
         * granted at analysis time so the detail screen can reconstruct
         * the "considered" chip row without re-querying Health Connect.
         */
        private val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analysis_runs ADD COLUMN healthMetricsCsv TEXT")
            }
        }

        /**
         * Introduces the Medication Reminders feature.
         *
         * Two new tables: `medication_reminders` holds one row per
         * configured schedule, `medication_dose_events` is an
         * append-only history of every fire + user action. The FK on
         * `medication_dose_events.medicationId` cascades on delete so
         * removing a reminder purges its history atomically.
         *
         * Indices: an index on `medicationId` (already implied by the
         * FK on some SQLite versions but declared explicitly to match
         * Room's expected schema), and on `scheduledAtEpochMillis` so
         * the "last 30 days" history query avoids a full-table scan
         * once the table has thousands of rows.
         */
        private val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS medication_reminders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        dosage TEXT NOT NULL,
                        frequencyKind TEXT NOT NULL,
                        timeOfDayMinutes INTEGER NOT NULL,
                        daysOfWeekMask INTEGER NOT NULL,
                        oneOffAtEpochMillis INTEGER,
                        enabled INTEGER NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS medication_dose_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        medicationId INTEGER NOT NULL,
                        scheduledAtEpochMillis INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        actedAtEpochMillis INTEGER NOT NULL,
                        FOREIGN KEY(medicationId) REFERENCES medication_reminders(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_medication_dose_events_medicationId ON medication_dose_events(medicationId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_medication_dose_events_scheduledAtEpochMillis ON medication_dose_events(scheduledAtEpochMillis)",
                )
            }
        }

        /**
         * Introduces the photo-attachment feature for symptom logs.
         *
         * One table: `symptom_photos`, one row per attached photo. The
         * FK to `symptom_logs.id` cascades on delete so removing a log
         * atomically purges its photo rows — that's the spine of
         * NFR-PA-05. File-system cleanup is handled separately by
         * [com.example.mob_dev_portfolio.data.photo.SymptomPhotoRepository.deleteForLog]
         * because Room cannot delete files; the repository runs the
         * file unlink *before* the row delete so the file list is
         * still discoverable.
         *
         * An index on `symptomLogId` so the detail-screen's
         * per-log query doesn't turn into a full-table scan once
         * the user has logged a month of photos.
         */
        private val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS symptom_photos (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        symptomLogId INTEGER NOT NULL,
                        storageFileName TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        FOREIGN KEY(symptomLogId) REFERENCES symptom_logs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_symptom_photos_symptomLogId ON symptom_photos(symptomLogId)",
                )
            }
        }

        /**
         * Introduces the Doctor Visits feature — the user logs clinic
         * visits and the symptoms the doctor reviewed, so the AI knows
         * which signals to ignore ("cleared") and which belong to a
         * known diagnosis.
         *
         * Four tables:
         *   - `doctor_visits` — one row per consultation.
         *   - `doctor_visit_covered_logs` — join: logs the doctor
         *     reviewed and cleared. Cascades on both sides so deleting
         *     the visit returns the log to active analysis, and
         *     deleting the log drops the clearance reference.
         *   - `doctor_diagnoses` — one row per issue the doctor flagged
         *     at a visit. Cascades on visit delete.
         *   - `doctor_diagnosis_logs` — join: symptom logs the user
         *     wants annotated with a known diagnosis in AI output.
         *     Cascades on both sides.
         */
        private val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS doctor_visits (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        doctorName TEXT NOT NULL,
                        visitDateEpochMillis INTEGER NOT NULL,
                        summary TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS doctor_visit_covered_logs (
                        visitId INTEGER NOT NULL,
                        logId INTEGER NOT NULL,
                        PRIMARY KEY(visitId, logId),
                        FOREIGN KEY(visitId) REFERENCES doctor_visits(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(logId) REFERENCES symptom_logs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_doctor_visit_covered_logs_logId ON doctor_visit_covered_logs(logId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS doctor_diagnoses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        visitId INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        FOREIGN KEY(visitId) REFERENCES doctor_visits(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_doctor_diagnoses_visitId ON doctor_diagnoses(visitId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS doctor_diagnosis_logs (
                        diagnosisId INTEGER NOT NULL,
                        logId INTEGER NOT NULL,
                        PRIMARY KEY(diagnosisId, logId),
                        FOREIGN KEY(diagnosisId) REFERENCES doctor_diagnoses(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(logId) REFERENCES symptom_logs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_doctor_diagnosis_logs_logId ON doctor_diagnosis_logs(logId)",
                )
            }
        }

        /**
         * Adds the user-declared health condition tables. Distinct from
         * the doctor-diagnosis system (which always lives under a
         * specific visit) — these are standalone facts about the user
         * captured at onboarding or via the dedicated Conditions
         * settings screen.
         *
         *  - `health_conditions` — one row per condition the user has
         *    declared (e.g. "Type 2 Diabetes"). Free-text name + notes,
         *    no FK to doctor data.
         *  - `health_condition_logs` — join: symptom logs the user
         *    wants grouped under a condition. Cascades on both sides
         *    so deleting a condition unlinks its logs and deleting a
         *    log clears the link.
         *
         * Both flow into the AI's already-explained context bundle
         * alongside the existing doctor-diagnosis data.
         */
        private val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS health_conditions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS health_condition_logs (
                        conditionId INTEGER NOT NULL,
                        logId INTEGER NOT NULL,
                        PRIMARY KEY(conditionId, logId),
                        FOREIGN KEY(conditionId) REFERENCES health_conditions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(logId) REFERENCES symptom_logs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_health_condition_logs_logId ON health_condition_logs(logId)",
                )
            }
        }

        /**
         * Lazily constructs the singleton database instance. Uses
         * double-checked locking so the synchronisation cost is paid
         * once on first open and never again. The passphrase is
         * defensively `copyOf`-ed because SQLCipher's open helper
         * zeroes the array it receives — re-using the original after
         * `build()` would leak a wiped buffer to later callers.
         */
        fun get(context: Context, passphrase: ByteArray): AuraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuraDatabase::class.java,
                    "aura.db",
                )
                    .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
