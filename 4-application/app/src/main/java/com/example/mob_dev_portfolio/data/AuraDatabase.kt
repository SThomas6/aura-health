package com.example.mob_dev_portfolio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [SymptomLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AuraDatabase : RoomDatabase() {

    abstract fun symptomLogDao(): SymptomLogDao

    companion object {
        @Volatile
        private var instance: AuraDatabase? = null

        fun get(context: Context, passphrase: ByteArray): AuraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuraDatabase::class.java,
                    "aura.db",
                )
                    .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
