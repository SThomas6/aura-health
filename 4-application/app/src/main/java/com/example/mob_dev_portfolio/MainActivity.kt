package com.example.mob_dev_portfolio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mob_dev_portfolio.data.preferences.ThemeMode
import com.example.mob_dev_portfolio.data.preferences.UiPreferences
import com.example.mob_dev_portfolio.ui.AuraApp
import com.example.mob_dev_portfolio.ui.theme.AuraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val preferencesFlow = (application as AuraApplication)
            .container
            .uiPreferencesRepository
            .preferences
        setContent {
            val prefs by preferencesFlow.collectAsStateWithLifecycle(initialValue = UiPreferences())
            AuraTheme(themeMode = prefs.themeMode) {
                AuraApp()
            }
        }
    }
}
