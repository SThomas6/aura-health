package com.example.mob_dev_portfolio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [SymptomLogEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AuraDatabase : RoomDatabase() {

    abstract fun symptomLogDao(): SymptomLogDao

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

        fun get(context: Context, passphrase: ByteArray): AuraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuraDatabase::class.java,
                    "aura.db",
                )
                    .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
