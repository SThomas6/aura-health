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
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        SymptomLogEntity::class,
        AnalysisRunEntity::class,
        AnalysisRunLogCrossRef::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AuraDatabase : RoomDatabase() {

    abstract fun symptomLogDao(): SymptomLogDao

    abstract fun analysisRunDao(): AnalysisRunDao

    companion object {
        @Volatile
        private var instance: AuraDatabase? = null

        /**
         * Adds nullable location columns for the privacy-preserving location capture feature.
         * Nullable because location is opt-in per-log; existing rows should remain null.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
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
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
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
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
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
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
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

        fun get(context: Context, passphrase: ByteArray): AuraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuraDatabase::class.java,
                    "aura.db",
                )
                    .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
