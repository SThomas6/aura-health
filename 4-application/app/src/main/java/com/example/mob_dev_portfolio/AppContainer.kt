package com.example.mob_dev_portfolio

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.example.mob_dev_portfolio.data.AuraDatabase
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.location.AndroidGeocoder
import com.example.mob_dev_portfolio.data.location.FusedLocationProvider
import com.example.mob_dev_portfolio.data.location.LocationProvider
import com.example.mob_dev_portfolio.data.location.ReverseGeocoder
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import com.example.mob_dev_portfolio.data.security.DatabasePassphraseProvider
import com.example.mob_dev_portfolio.data.security.PassphraseOutcome
import com.example.mob_dev_portfolio.data.security.PlaintextDatabaseMigrator
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

private val Context.uiPreferencesStore: DataStore<Preferences> by preferencesDataStore(name = "aura_ui_prefs")

interface AppContainer {
    val symptomLogRepository: SymptomLogRepository
    val uiPreferencesRepository: UiPreferencesRepository
    val locationProvider: LocationProvider
    val reverseGeocoder: ReverseGeocoder
}

class DefaultAppContainer(
    context: Context,
    private val quarantine: DatabaseQuarantine = FilesystemQuarantine.Default,
) : AppContainer {
    private val appContext = context.applicationContext

    private val database by lazy { buildDatabase() }

    private fun buildDatabase(): AuraDatabase {
        val result = runBlocking { DatabasePassphraseProvider.obtain(appContext) }
        val primary = File(appContext.getDatabasePath(PRIMARY_DB_NAME).absolutePath)

        when (result.outcome) {
            PassphraseOutcome.Reused -> Unit

            PassphraseOutcome.GeneratedFresh -> {
                if (primary.exists()) {
                    val legacyRows = if (PlaintextDatabaseMigrator.isLikelyPlaintextSqlite(primary)) {
                        PlaintextDatabaseMigrator.readLegacyRows(primary)
                    } else {
                        emptyList()
                    }
                    quarantine.moveAside(primary.parentFile ?: primary, tag = "legacy")
                    if (legacyRows.isNotEmpty()) {
                        val db = openEncryptedRoom(result.passphrase)
                        val imported = PlaintextDatabaseMigrator.importBlocking(db.symptomLogDao(), legacyRows)
                        Log.i(TAG, "Imported $imported legacy rows into encrypted database")
                        return db
                    }
                }
            }

            PassphraseOutcome.GeneratedAfterCorruption -> {
                if (primary.exists()) {
                    quarantine.moveAside(primary.parentFile ?: primary, tag = "corrupted")
                }
            }
        }

        return openEncryptedRoom(result.passphrase)
    }

    private fun openEncryptedRoom(passphrase: ByteArray): AuraDatabase =
        AuraDatabase.get(appContext, passphrase)

    override val symptomLogRepository: SymptomLogRepository by lazy {
        SymptomLogRepository(database.symptomLogDao())
    }

    override val uiPreferencesRepository: UiPreferencesRepository by lazy {
        UiPreferencesRepository(appContext.uiPreferencesStore)
    }

    override val locationProvider: LocationProvider by lazy {
        FusedLocationProvider(appContext)
    }

    override val reverseGeocoder: ReverseGeocoder by lazy {
        AndroidGeocoder(appContext)
    }

    companion object {
        private const val TAG = "AppContainer"
        const val PRIMARY_DB_NAME = "aura.db"
        val SIDECAR_DB_FILES = listOf("aura.db", "aura.db-journal", "aura.db-wal", "aura.db-shm")
    }
}

interface DatabaseQuarantine {
    fun moveAside(databasesDir: File, tag: String)
}

class FilesystemQuarantine(
    private val renamer: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DatabaseQuarantine {

    override fun moveAside(databasesDir: File, tag: String) {
        val primary = File(databasesDir, DefaultAppContainer.PRIMARY_DB_NAME)
        if (!primary.exists()) return
        val stamp = "$tag-${clock()}"
        DefaultAppContainer.SIDECAR_DB_FILES.forEach { name ->
            val source = File(databasesDir, name)
            if (!source.exists()) return@forEach
            val destination = File(databasesDir, "$name.$stamp")
            val renamed = runCatching { renamer(source, destination) }.getOrDefault(false)
            if (!renamed) {
                throw IOException(
                    "Could not preserve $name as ${destination.name}; refusing to destroy the only copy of the database.",
                )
            }
            Log.i(TAG, "Preserved $name as ${destination.name}")
        }
    }

    companion object {
        private const val TAG = "Quarantine"
        val Default: DatabaseQuarantine = FilesystemQuarantine()
    }
}
