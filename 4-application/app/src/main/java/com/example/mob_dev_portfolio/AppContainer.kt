package com.example.mob_dev_portfolio

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.example.mob_dev_portfolio.data.AuraDatabase
import com.example.mob_dev_portfolio.data.SymptomLogEntity
import com.example.mob_dev_portfolio.data.SymptomLogRepository
import com.example.mob_dev_portfolio.data.SymptomLogSeeder
import com.example.mob_dev_portfolio.data.doctor.DoctorVisitRepository
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
import com.example.mob_dev_portfolio.data.environment.EnvironmentalHistoryRepository
import com.example.mob_dev_portfolio.data.environment.EnvironmentalHistoryService
import com.example.mob_dev_portfolio.data.environment.EnvironmentalService
import com.example.mob_dev_portfolio.data.environment.OpenMeteoEnvironmentalHistoryService
import com.example.mob_dev_portfolio.data.environment.OpenMeteoEnvironmentalService
import com.example.mob_dev_portfolio.data.health.HealthConnectService
import com.example.mob_dev_portfolio.data.health.HealthHistoryRepository
import com.example.mob_dev_portfolio.data.health.HealthPreferencesRepository
import com.example.mob_dev_portfolio.data.health.HealthSampleSeeder
import com.example.mob_dev_portfolio.data.location.AndroidGeocoder
import com.example.mob_dev_portfolio.data.location.FusedLocationProvider
import com.example.mob_dev_portfolio.data.location.LocationProvider
import com.example.mob_dev_portfolio.data.location.ReverseGeocoder
import com.example.mob_dev_portfolio.data.medication.MedicationRepository
import com.example.mob_dev_portfolio.data.photo.SymptomPhotoRepository
import com.example.mob_dev_portfolio.notifications.MedicationReminderNotifier
import com.example.mob_dev_portfolio.reminders.MedicationReminderScheduler
import com.example.mob_dev_portfolio.data.preferences.UiPreferencesRepository
import com.example.mob_dev_portfolio.data.preferences.UserProfileRepository
import com.example.mob_dev_portfolio.data.report.HealthReportPdfGenerator
import com.example.mob_dev_portfolio.data.report.ReportArchiveRepository
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
private val Context.healthPreferencesStore: DataStore<Preferences> by preferencesDataStore(name = "aura_health_prefs")

interface AppContainer {
    val symptomLogRepository: SymptomLogRepository
    val uiPreferencesRepository: UiPreferencesRepository
    val locationProvider: LocationProvider
    val reverseGeocoder: ReverseGeocoder
    val environmentalService: EnvironmentalService

    /**
     * Historical weather + AQI fetcher for the Trends overlay lines.
     * Distinct from [environmentalService] (which takes one sample at
     * save-time) — this one returns full time-series over a window.
     */
    val environmentalHistoryService: EnvironmentalHistoryService

    /** Caching / location-resolving wrapper around [environmentalHistoryService]. */
    val environmentalHistoryRepository: EnvironmentalHistoryRepository
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
     * Session-lived biometric-lock gate. See
     * [com.example.mob_dev_portfolio.security.AppLockController] — kept
     * in the container (not in MainActivity) so the Settings screen can
     * flip it directly when the user enables the lock while already
     * inside the app.
     */
    val appLockController: com.example.mob_dev_portfolio.security.AppLockController

    /**
     * Offline health-report generator. Aggregates symptom logs + AI
     * analysis history into a structured PDF using Android's native
     * [android.graphics.pdf.PdfDocument] — no network, no third-party
     * library.
     */
    val reportRepository: ReportRepository

    /** Native [android.graphics.pdf.PdfDocument]-backed report writer. */
    val healthReportPdfGenerator: HealthReportPdfGenerator

    /**
     * Reads Health Connect records into an in-memory [com.example.mob_dev_portfolio.data.health.HealthSnapshot].
     * The only component that talks to the HC provider directly —
     * view-models request grants via the SDK's permission contract but
     * all record reads flow through here so the per-metric fallback
     * logic stays in one place.
     */
    val healthConnectService: HealthConnectService

    /**
     * Persists the user's per-metric opt-in toggles + the master
     * "integration active" flag. Stored in its own DataStore file so
     * the settings screen renders without unlocking the encrypted
     * Room database.
     */
    val healthPreferencesRepository: HealthPreferencesRepository

    /**
     * Time-series reader over Health Connect for the Home dashboard +
     * fullscreen detail screens. Separate from [healthConnectService]
     * because those path build aggregates for the AI prompt; this path
     * returns raw per-day (or per-hour) buckets for graphing.
     */
    val healthHistoryRepository: HealthHistoryRepository

    /**
     * Developer-only seeder used by the "Seed sample data" button on the
     * Health Data Settings screen. Writes ~14 days of plausible records
     * so the Home graphs have data to render without the user owning a
     * wearable.
     */
    val healthSampleSeeder: HealthSampleSeeder

    /**
     * Room-backed index of every generated PDF report, paired with
     * the compressed files on disk. Backs the history screen and
     * drives the two-step (file-first, row-second) delete contract.
     */
    val reportArchiveRepository: ReportArchiveRepository

    /**
     * Idempotent "first-launch" seeder that inserts ~20 demo symptom
     * logs if (and only if) the log table is empty. Exists so the
     * graphs, history filters, and AI-analysis prompt all have data
     * to render on a brand-new install without the user having to
     * backfill an hour of manual entry first. Returning users with
     * real logs are never touched — the seeder short-circuits on any
     * non-zero row count.
     */
    val symptomLogSeeder: SymptomLogSeeder

    /**
     * Room-backed store of medication reminders + their append-only dose
     * history. Writes from the editor UI and the [MedicationReminderReceiver];
     * reads drive the list/history screens and the Home preview card.
     */
    val medicationRepository: MedicationRepository

    /**
     * AlarmManager wrapper that arms/cancels the next fire of each
     * reminder. Kept in the container so both the boot receiver and
     * the editor ViewModel dial the same instance.
     */
    val medicationReminderScheduler: MedicationReminderScheduler

    /** Channel + notification builder for medication-reminder fires. */
    val medicationReminderNotifier: MedicationReminderNotifier

    /**
     * Photo attachments for symptom logs. Owns the compress →
     * EXIF-strip → encrypt → store pipeline. Wired alongside the log
     * repository so the editor and detail screens can add, list,
     * and remove photos without going through a sub-viewmodel.
     */
    val symptomPhotoRepository: SymptomPhotoRepository

    /**
     * Doctor visits + their cleared/diagnosed symptom joins. Feeds
     * both the Doctor Visits top-level screen and the AI pipeline —
     * cleared logs are excluded from the Gemini prompt, and
     * diagnosis-linked logs are annotated with the diagnosis label
     * so the model treats them as known context.
     */
    val doctorVisitRepository: DoctorVisitRepository
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
        SymptomLogRepository(
            dao = database.symptomLogDao(),
            photoRepository = symptomPhotoRepository,
        )
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

    override val environmentalHistoryService: EnvironmentalHistoryService by lazy {
        OpenMeteoEnvironmentalHistoryService()
    }

    override val environmentalHistoryRepository: EnvironmentalHistoryRepository by lazy {
        EnvironmentalHistoryRepository(
            service = environmentalHistoryService,
            symptomLogRepository = symptomLogRepository,
        )
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

    override val appLockController: com.example.mob_dev_portfolio.security.AppLockController by lazy {
        com.example.mob_dev_portfolio.security.AppLockController()
    }

    override val reportRepository: ReportRepository by lazy {
        ReportRepository(
            symptomLogDao = database.symptomLogDao(),
            analysisRunDao = database.analysisRunDao(),
            photoRepository = symptomPhotoRepository,
        )
    }

    override val healthReportPdfGenerator: HealthReportPdfGenerator by lazy {
        HealthReportPdfGenerator(context = appContext)
    }

    override val reportArchiveRepository: ReportArchiveRepository by lazy {
        ReportArchiveRepository(
            dao = database.reportArchiveDao(),
            pdfGenerator = healthReportPdfGenerator,
        )
    }

    override val healthConnectService: HealthConnectService by lazy {
        HealthConnectService(appContext)
    }

    override val healthPreferencesRepository: HealthPreferencesRepository by lazy {
        HealthPreferencesRepository(appContext.healthPreferencesStore)
    }

    override val healthHistoryRepository: HealthHistoryRepository by lazy {
        HealthHistoryRepository(appContext)
    }

    override val healthSampleSeeder: HealthSampleSeeder by lazy {
        HealthSampleSeeder(appContext)
    }

    override val symptomLogSeeder: SymptomLogSeeder by lazy {
        SymptomLogSeeder(repository = symptomLogRepository)
    }

    override val medicationRepository: MedicationRepository by lazy {
        MedicationRepository(
            reminderDao = database.medicationReminderDao(),
            doseEventDao = database.doseEventDao(),
        )
    }

    override val medicationReminderScheduler: MedicationReminderScheduler by lazy {
        MedicationReminderScheduler(appContext)
    }

    override val medicationReminderNotifier: MedicationReminderNotifier by lazy {
        MedicationReminderNotifier(appContext)
    }

    override val symptomPhotoRepository: SymptomPhotoRepository by lazy {
        SymptomPhotoRepository(
            context = appContext,
            dao = database.symptomPhotoDao(),
        )
    }

    override val doctorVisitRepository: DoctorVisitRepository by lazy {
        DoctorVisitRepository(
            database = database,
            visitDao = database.doctorVisitDao(),
            diagnosisDao = database.doctorDiagnosisDao(),
        )
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
