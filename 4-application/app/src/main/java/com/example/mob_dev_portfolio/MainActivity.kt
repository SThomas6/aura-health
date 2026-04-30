package com.example.mob_dev_portfolio

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mob_dev_portfolio.data.preferences.UiPreferences
import com.example.mob_dev_portfolio.notifications.AnalysisNotifier
import com.example.mob_dev_portfolio.ui.AuraApp
import com.example.mob_dev_portfolio.ui.DeepLinkTarget
import com.example.mob_dev_portfolio.ui.lock.BiometricAvailability
import com.example.mob_dev_portfolio.ui.lock.BiometricLockScreen
import com.example.mob_dev_portfolio.ui.lock.biometricAvailability
import com.example.mob_dev_portfolio.ui.onboarding.OnboardingScreen
import com.example.mob_dev_portfolio.ui.theme.AuraTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember

/**
 * Extends [FragmentActivity] rather than `ComponentActivity` because
 * [androidx.biometric.BiometricPrompt] requires a FragmentActivity host
 * to attach its internal auth fragment. FragmentActivity transitively
 * *is* a ComponentActivity, so `setContent`, `enableEdgeToEdge`, the
 * activity-result APIs, and all other Compose plumbing continue to
 * work unchanged.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() MUST run before super.onCreate() so the
        // platform knows to swap from Theme.AuraHealth.Starting to the
        // post-splash theme once the first frame is drawn. Skipping this
        // call works — the system falls back to the default splash — but
        // then the themed background + animated icon we set up in
        // themes.xml are never picked up.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AuraApplication).container
        val preferencesRepo = container.uiPreferencesRepository
        val preferencesFlow = preferencesRepo.preferences
        val onboardingCompleteFlow = preferencesRepo.onboardingComplete
        val biometricLockFlow = preferencesRepo.biometricLockEnabled
        val appLockController = container.appLockController
        // Handle the notification deep-link for the cold-start case: the
        // system launches us with the intent, we read the extra once, and
        // push an event that AuraApp picks up after Compose has wired up
        // the NavController.
        handleDeepLinkIntent(intent)
        setContent {
            val prefs by preferencesFlow.collectAsStateWithLifecycle(initialValue = UiPreferences())
            // `null` until DataStore hands back its first read. Rendering
            // neither UI during that tiny window prevents a cold-start
            // flash of AuraApp before OnboardingScreen takes over.
            val onboardingComplete by onboardingCompleteFlow
                .collectAsStateWithLifecycle(initialValue = null)
            val biometricLockEnabled by biometricLockFlow
                .collectAsStateWithLifecycle(initialValue = true)
            val unlocked by appLockController.unlocked.collectAsStateWithLifecycle()
            // Availability check paired with the default-on pref: a
            // brand-new device with no biometric enrolment *and* no
            // screen-lock credential can't clear a BiometricPrompt,
            // so we treat it as "don't gate" rather than stranding the
            // user on a lock screen. Safe-by-default + escape-hatch.
            val context = LocalContext.current
            val biometricsActuallyUsable = remember(context, biometricLockEnabled) {
                biometricAvailability(context) == BiometricAvailability.Available
            }
            AuraTheme(themeMode = prefs.themeMode) {
                when {
                    onboardingComplete == null -> Unit
                    onboardingComplete == false -> OnboardingScreen(onFinish = { /* flow-driven swap */ })
                    // Gate sits *after* onboarding so the user has had a
                    // chance to see the welcome flow before being asked
                    // to authenticate. Shown when the opt-in pref is
                    // true, the device can authenticate, and the
                    // current session hasn't been unlocked yet —
                    // onStop below re-locks whenever we leave the
                    // foreground.
                    biometricLockEnabled && biometricsActuallyUsable && !unlocked -> BiometricLockScreen(
                        onUnlock = { appLockController.markUnlocked() },
                    )
                    else -> AuraApp()
                }
            }
        }
    }

    /**
     * Warm-start path: the activity is declared `singleTop` in the manifest
     * so tapping a notification while the app is already running reuses this
     * instance and hits [onNewIntent]. We call [setIntent] so any later
     * `getIntent()` sees the fresh extras, then route the extra the same way
     * we do in [onCreate].
     */
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
        handleDeepLinkIntent(newIntent)
    }

    /**
     * Re-lock the session whenever we leave the foreground. Using
     * `onStop` (rather than `onPause`) means transient interruptions
     * like the quick-settings shade don't trigger a re-auth — only an
     * actual task-switch or screen-off does. Paired with the
     * [com.example.mob_dev_portfolio.security.AppLockController]
     * lookup in `setContent`, this yields a "lock on every resume" UX
     * that matches system-level behaviour.
     */
    override fun onStop() {
        super.onStop()
        (application as AuraApplication).container.appLockController.markLocked()
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        if (intent == null) return

        // Health Connect "why do you need this?" rationale intent. The
        // system launches us with this action when the user taps our
        // row in the Health Connect app's permissions list — we land
        // them on the settings screen.
        val action = intent.action
        if (action == ACTION_SHOW_PERMISSIONS_RATIONALE ||
            action == Intent.ACTION_VIEW_PERMISSION_USAGE
        ) {
            (application as AuraApplication).container.deepLinkEvents
                .emit(DeepLinkTarget.HealthDataSettings)
            return
        }

        val open = intent.getBooleanExtra(
            AnalysisNotifier.EXTRA_OPEN_ANALYSIS_RESULT,
            false,
        )
        if (!open) return
        // Prefer the run-scoped deep link when the worker attached a rowId.
        // `getLongExtra` returns the default when the extra is absent, so a
        // missing id falls through to the history-list target.
        val runId = intent.getLongExtra(AnalysisNotifier.EXTRA_ANALYSIS_RUN_ID, -1L)
        // Clear the extras so a config change doesn't replay the deep link.
        // setIntent above keeps a reference to the original, so we mutate
        // the bundle in place.
        intent.removeExtra(AnalysisNotifier.EXTRA_OPEN_ANALYSIS_RESULT)
        intent.removeExtra(AnalysisNotifier.EXTRA_ANALYSIS_RUN_ID)
        val target = if (runId > 0L) {
            DeepLinkTarget.AnalysisRun(runId)
        } else {
            DeepLinkTarget.AnalysisResult
        }
        (application as AuraApplication).container.deepLinkEvents.emit(target)
    }

    private companion object {
        /**
         * Matches the action declared on MainActivity's intent filter for
         * the Health Connect permissions rationale surface. Held locally
         * to avoid depending on the Health Connect client for a string
         * constant.
         */
        const val ACTION_SHOW_PERMISSIONS_RATIONALE =
            "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
    }
}
