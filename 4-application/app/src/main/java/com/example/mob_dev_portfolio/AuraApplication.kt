package com.example.mob_dev_portfolio

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.example.mob_dev_portfolio.data.preferences.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AuraApplication : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * Lightweight application-scoped coroutine scope for one-shot
     * start-up tasks (seeders, cache warm-ups) that shouldn't block
     * onCreate. Intentionally *not* exposed — if something else in the
     * app needs a long-lived scope, it should take its own via the
     * container rather than reach into Application.
     */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        container = DefaultAppContainer(this)

        // Apply the user's persisted theme preference to AppCompat's
        // global night-mode flag. AppCompat persists the value via its
        // own auto-save ContentProvider, which means on the *next* cold
        // start the system picks the correct -night resource qualifier
        // before our first Activity runs — fixing the issue where the
        // launcher splash always followed the device-wide dark setting
        // and ignored the in-app Light/Dark toggle. We block briefly on
        // the DataStore read here; it's a single prefs file read on the
        // IO dispatcher and happens once per process, which is cheaper
        // than letting the splash flash-mismatch on every launch.
        runCatching {
            val mode = runBlocking {
                container.uiPreferencesRepository.preferences.first().themeMode
            }
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    ThemeMode.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    ThemeMode.Light -> AppCompatDelegate.MODE_NIGHT_NO
                    ThemeMode.Dark -> AppCompatDelegate.MODE_NIGHT_YES
                },
            )
        }.onFailure { Log.w("AuraApplication", "Theme mode apply failed", it) }

        // Ensure the weekly background analysis is scheduled. Idempotent —
        // ExistingPeriodicWorkPolicy.KEEP inside the scheduler means this
        // no-ops once the recurring work is already queued, so calling it
        // on every cold start is the right thing.
        container.analysisScheduler.scheduleWeekly()

        // Fire the first-launch demo seeders on a background scope so
        // a cold start doesn't stall on the database + flow read. Both
        // are no-ops when their respective tables already have rows, so
        // this is safe to run on every cold start.
        //
        // Only the `demo` product flavor seeds — production builds get a
        // clean database so first-run UX matches what a real user would
        // experience. The flag is set via BuildConfig from the flavor
        // block in app/build.gradle.
        //
        // Order matters: the doctor seeder links visits to symptom logs
        // by id, so the symptom seed has to land first. We run them
        // sequentially in a single coroutine rather than two parallel
        // launches to avoid the doctor seeder racing the symptom seed
        // and bailing out as "no logs yet".
        if (BuildConfig.SEED_SAMPLE_DATA) {
            startupScope.launch {
                runCatching { container.symptomLogSeeder.seedIfEmpty() }
                    .onFailure { Log.w("AuraApplication", "Symptom seed failed", it) }
                runCatching { container.doctorVisitSeeder.seedIfEmpty() }
                    .onFailure { Log.w("AuraApplication", "Doctor visit seed failed", it) }
            }
        }

        // Re-arm medication reminders on cold start. The boot receiver
        // covers reboots, but this covers every other way our alarms can
        // go missing — task-killed by aggressive OEMs, app force-stopped
        // by the user, or simply because a new schedule was added in the
        // last process. `rescheduleAll` cancels-then-arms per reminder so
        // repeat runs converge rather than multiply. We also prune dose
        // events older than the 30-day history window (FR-MR-06) here so
        // the pruning stays append-friendly without a dedicated worker.
        startupScope.launch {
            runCatching {
                val reminders = container.medicationRepository.listAll()
                container.medicationReminderNotifier.ensureChannel()
                container.medicationReminderScheduler.rescheduleAll(reminders)
                val cutoff = System.currentTimeMillis() -
                    com.example.mob_dev_portfolio.data.medication.HISTORY_RETENTION_MILLIS
                container.medicationRepository.pruneOlderThan(cutoff)
            }.onFailure { Log.w("AuraApplication", "Medication rearm failed", it) }
        }
    }
}
