package com.example.mob_dev_portfolio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mob_dev_portfolio.data.preferences.UiPreferences
import com.example.mob_dev_portfolio.notifications.AnalysisNotifier
import com.example.mob_dev_portfolio.ui.AuraApp
import com.example.mob_dev_portfolio.ui.DeepLinkTarget
import com.example.mob_dev_portfolio.ui.theme.AuraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val preferencesFlow = (application as AuraApplication)
            .container
            .uiPreferencesRepository
            .preferences
        // Handle the notification deep-link for the cold-start case: the
        // system launches us with the intent, we read the extra once, and
        // push an event that AuraApp picks up after Compose has wired up
        // the NavController.
        handleDeepLinkIntent(intent)
        setContent {
            val prefs by preferencesFlow.collectAsStateWithLifecycle(initialValue = UiPreferences())
            AuraTheme(themeMode = prefs.themeMode) {
                AuraApp()
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
        setIntent(newIntent)
        handleDeepLinkIntent(newIntent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val open = intent?.getBooleanExtra(
            AnalysisNotifier.EXTRA_OPEN_ANALYSIS_RESULT,
            false,
        ) == true
        if (!open) return
        // Clear the extra so a config change doesn't replay the deep link.
        // setIntent above keeps a reference to the original, so we mutate
        // the bundle in place.
        intent.removeExtra(AnalysisNotifier.EXTRA_OPEN_ANALYSIS_RESULT)
        (application as AuraApplication).container.deepLinkEvents.emit(
            DeepLinkTarget.AnalysisResult,
        )
    }
}
