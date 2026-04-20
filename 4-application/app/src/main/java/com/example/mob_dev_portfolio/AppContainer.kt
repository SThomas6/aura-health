package com.example.mob_dev_portfolio

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.example.mob_dev_portfolio.data.AuraDatabase
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import androidx.work.WorkManager
import com.example.mob_dev_portfolio.data.ai.AnalysisHistoryRepository
import com.example.mob_dev_portfolio.data.ai.AnalysisResultStore
import com.example.mob_dev_portfolio.data.ai.AnalysisService
import com.example.mob_dev_portfolio.data.ai.AndroidNetworkConnectivity
import com.example.mob_dev_portfolio.data.ai.GeminiClient
import com.example.mob_dev_portfolio.data.ai.HttpGeminiClient
import com.example.mob_dev_portfolio.data.ai.NetworkConnectivity
import com.example.mob_dev_portfolio.notifications.AnalysisNotifier
import com.example.mob_dev_portfolio.ui.DeepLinkEvents
import com.example.mob_dev_portfolio.work.AnalysisScheduler
import com.example.mob_dev_portfolio.work.WorkManagerAnalysisScheduler
import com.example.mob_dev_portfolio.data.environment.EnvironmentalService
import com.example.mob_dev_portfolio.data.environment.OpenMeteoEnvironmentalService
import com.example.mob_dev_portfolio.data.location.AndroidGeocoder
import com.example.mob_dev_portfolio.data.location.FusedLocationProvider
import com.example.mob_dev_portfolio.data.location.LocationProvider
import com.example.mob_dev_portfolio.data.location.ReverseGeocoder
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import com.example.mob_dev_portfolio.data.preferences.UserProfileRepository
import com.example.mob_dev_portfolio.data.report.HealthReportPdfGenerator
import com.example.mob_dev_portfolio.data.report.ReportRepository
import com.example.mob_dev_portfolio.data.security.DatabasePassphraseProvider
import com.example.mob_dev_portfolio.data.security.PassphraseOutcome
import com.example.mob_dev_portfolio.data.security.PlaintextDatabaseMigrator
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

private val Context.uiPreferencesStore: DataStore<Preferences> by preferencesDataStore(name = "aura_ui_prefs")
private val Context.userProfileStore: DataStore<Preferences> by preferencesDataStore(name = "aura_user_profile")
private val Context.analysisResultStoreDs: DataStore<Preferences> by preferencesDataStore(name = "aura_analysis_result")

interface AppContainer {
    val symptomLogRepository: SymptomLogRepository
    val uiPreferencesRepository: UiPreferencesRepository
    val locationProvider: LocationProvider
    val reverseGeocoder: ReverseGeocoder
    val environmentalService: EnvironmentalService
    val userProfileRepository: UserProfileRepository
    val geminiClient: GeminiClient
    val analysisService: AnalysisService
    val networkConnectivity: NetworkConnectivity

    /**
     * Surfaces results from the background [com.example.mob_dev_portfolio.work.AnalysisWorker]
     * to the UI: the worker writes here on success; the Analysis screen
     * observes it. Also survives process death, so a notification tap that
     * cold-starts the app still finds the summary waiting.
     */
    val analysisResultStore: AnalysisResultStore

    /**
     * Room-backed history of every completed AI analysis run. The worker
     * writes rows here on success; the history screen observes them via
     * Flow so new results appear without a manual refresh, and the list
     * reads from local storage so it works offline.
     */
    val analysisHistoryRepository: AnalysisHistoryRepository

    /** Channel + notification builder shared by the worker. */
    val analysisNotifier: AnalysisNotifier

    /** Thin façade over WorkManager for the Analysis feature. */
    val analysisScheduler: AnalysisScheduler

    /**
     * App-wide event bus for notification-tap deep links. [MainActivity]
     * emits when it detects the notification extra; the Compose nav layer
     * subscribes and routes to the Analysis destination.
     */
    val deepLinkEvents: DeepLinkEvents

    /**
     * Offline health-report generator. Aggregates symptom logs + AI
     * analysis history into a structured PDF using Android's native
     * [android.graphics.pdf.PdfDocument] — no network, no third-party
     * library.
     */
    val reportRepository: ReportRepository

    /** Native [android.graphics.pdf.PdfDocument]-backed report writer. */
    val healthReportPdfGenerator: HealthReportPdfGenerator
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

    override val environmentalService: EnvironmentalService by lazy {
        OpenMeteoEnvironmentalService()
    }

    override val userProfileRepository: UserProfileRepository by lazy {
        UserProfileRepository(appContext.userProfileStore)
    }

    /**
     * BuildConfig carries the key loaded from local.properties at build time.
     * If the collaborator hasn't set `GEMINI_API_KEY`, [HttpGeminiClient]
     * short-circuits to an ApiError at call time — we never crash here.
     */
    override val geminiClient: GeminiClient by lazy {
        HttpGeminiClient(
            apiKey = BuildConfig.GEMINI_API_KEY,
            model = BuildConfig.GEMINI_MODEL,
        )
    }

    override val analysisService: AnalysisService by lazy {
        AnalysisService(client = geminiClient)
    }

    override val networkConnectivity: NetworkConnectivity by lazy {
        AndroidNetworkConnectivity(appContext)
    }

    override val analysisResultStore: AnalysisResultStore by lazy {
        AnalysisResultStore(appContext.analysisResultStoreDs)
    }

    override val analysisHistoryRepository: AnalysisHistoryRepository by lazy {
        AnalysisHistoryRepository(database.analysisRunDao())
    }

    override val analysisNotifier: AnalysisNotifier by lazy {
        AnalysisNotifier(appContext)
    }

    override val analysisScheduler: AnalysisScheduler by lazy {
        // WorkManager.getInstance initialises via the default
        // WorkManagerInitializer content provider, which is already on the
        // classpath via the work-runtime-ktx dependency — no manual
        // bootstrap required in Application.onCreate.
        WorkManagerAnalysisScheduler(WorkManager.getInstance(appContext))
    }

    override val deepLinkEvents: DeepLinkEvents by lazy { DeepLinkEvents() }

    override val reportRepository: ReportRepository by lazy {
        ReportRepository(
            symptomLogDao = database.symptomLogDao(),
            analysisRunDao = database.analysisRunDao(),
        )
    }

    override val healthReportPdfGenerator: HealthReportPdfGenerator by lazy {
        HealthReportPdfGenerator(context = appContext)
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
