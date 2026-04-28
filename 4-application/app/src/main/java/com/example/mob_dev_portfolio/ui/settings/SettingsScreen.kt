package com.example.mob_dev_portfolio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.mob_dev_portfolio.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.preferences.ThemeMode
import com.example.mob_dev_portfolio.ui.home.ThemePrefsViewModel
import com.example.mob_dev_portfolio.ui.lock.BiometricAvailability
import com.example.mob_dev_portfolio.ui.lock.biometricAvailability

/**
 * Consolidated settings page.
 *
 * Home used to carry Appearance / Demographic profile / Health data
 * integration as three separate tiles alongside the daily check-in
 * flow, which the user flagged as feeling "out of place". Moving them
 * here keeps Home focused on what matters day-to-day, while still
 * giving these lower-frequency destinations a single obvious place to
 * find them.
 *
 * The theme picker is rendered inline because it's a one-tap choice;
 * the two navigational rows delegate deeper into existing screens via
 * callbacks so this composable stays presentation-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDemographicProfile: () -> Unit,
    onOpenHealthDataSettings: () -> Unit,
    onOpenHealthConditions: () -> Unit,
    themeViewModel: ThemePrefsViewModel = viewModel(factory = ThemePrefsViewModel.Factory),
    biometricViewModel: BiometricSettingsViewModel = viewModel(factory = BiometricSettingsViewModel.Factory),
    medicationViewModel: MedicationRemindersSettingsViewModel = viewModel(factory = MedicationRemindersSettingsViewModel.Factory),
) {
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val themeError by themeViewModel.error.collectAsStateWithLifecycle()
    val biometricEnabled by biometricViewModel.enabled.collectAsStateWithLifecycle()
    val medicationRemindersEnabled by medicationViewModel.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-read availability on every recomposition so returning from
    // the system security settings (where the user might have just
    // enrolled a fingerprint) immediately flips the row from disabled
    // to enabled. `canAuthenticate` is a cheap in-process query.
    val biometricAvailability = remember(context, biometricEnabled) {
        biometricAvailability(context)
    }
    val snackbarHost = remember { SnackbarHostState() }
    // Resolve the snackbar action label in @Composable scope so the
    // LaunchedEffect (which is *not* composable) doesn't reach back
    // through `context.getString` — Compose lint flags that pattern
    // because the resource read isn't observable for config changes.
    val retryLabel = stringResource(R.string.action_retry)

    LaunchedEffect(themeError) {
        themeError?.let { error ->
            val result = snackbarHost.showSnackbar(
                message = error.message,
                actionLabel = retryLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) themeViewModel.retryLastError()
            else themeViewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("settings_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppearanceCard(current = themeMode, onSelect = themeViewModel::setThemeMode)

            BiometricLockCard(
                enabled = biometricEnabled,
                availability = biometricAvailability,
                onToggle = biometricViewModel::setEnabled,
            )

            MedicationRemindersCard(
                enabled = medicationRemindersEnabled,
                onToggle = medicationViewModel::setEnabled,
            )

            SettingsNavCard(
                title = stringResource(R.string.title_demographic_profile),
                subtitle = stringResource(R.string.settings_demographic_subtitle),
                icon = Icons.Filled.Person,
                onClick = onOpenDemographicProfile,
                testTag = "settings_demographic_profile",
            )
            SettingsNavCard(
                title = stringResource(R.string.title_health_data),
                subtitle = stringResource(R.string.settings_health_data_subtitle),
                icon = Icons.Filled.Favorite,
                onClick = onOpenHealthDataSettings,
                testTag = "settings_health_data",
            )
            SettingsNavCard(
                title = stringResource(R.string.title_health_conditions),
                subtitle = stringResource(R.string.settings_health_conditions_subtitle),
                icon = Icons.Filled.HealthAndSafety,
                onClick = onOpenHealthConditions,
                testTag = "settings_health_conditions",
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

/**
 * The theme picker, identical to the one that used to live on Home.
 * Extracted here so the previous Home copy can be deleted; the segmented
 * button ids + testTags are preserved so existing UI tests keep working.
 */
@Composable
private fun AppearanceCard(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeModeOption(ThemeMode.System, stringResource(R.string.settings_appearance_system), Icons.Filled.SettingsBrightness),
        ThemeModeOption(ThemeMode.Light, stringResource(R.string.settings_appearance_light), Icons.Filled.LightMode),
        ThemeModeOption(ThemeMode.Dark, stringResource(R.string.settings_appearance_dark), Icons.Filled.DarkMode),
    )
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    val selected = option.mode == current
                    SegmentedButton(
                        selected = selected,
                        onClick = { onSelect(option.mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        icon = {
                            SegmentedButtonDefaults.Icon(
                                active = selected,
                                activeContent = {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                                    )
                                },
                                inactiveContent = {
                                    Icon(
                                        option.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("theme_${option.mode.name.lowercase()}"),
                        label = {
                            Text(
                                option.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsNavCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Global "medication reminders" switch tile. Turning it off cancels
 * every armed alarm in the system but keeps the reminder rows in Room
 * intact (FR-MR-08). Mirrors the biometric-lock tile's layout so the
 * settings list reads as a consistent stack of toggles.
 */
@Composable
private fun MedicationRemindersCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag("settings_medication_reminders"),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Medication,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_medication_reminders_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.settings_medication_reminders_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .testTag("switch_medication_reminders")
                    .semantics {
                        contentDescription = if (enabled) {
                            "Medication reminders enabled. Double-tap to pause all reminders."
                        } else {
                            "Medication reminders paused. Double-tap to enable."
                        }
                    },
            )
        }
    }
}

private data class ThemeModeOption(
    val mode: ThemeMode,
    val label: String,
    val icon: ImageVector,
)

/**
 * "Lock with biometrics" switch tile.
 *
 * The switch is disabled (and the subtitle explains why) when the
 * device has no enrolled biometrics or no compatible hardware — we
 * never flip the underlying preference in that case because the
 * BiometricPrompt would immediately error out and we'd be left with
 * a pref that's `true` but a gate that can never be cleared.
 */
@Composable
private fun BiometricLockCard(
    enabled: Boolean,
    availability: BiometricAvailability,
    onToggle: (Boolean) -> Unit,
) {
    val canEnable = availability == BiometricAvailability.Available
    val subtitle = when {
        // When the lock is already on we keep the copy action-oriented
        // ("this is protecting you") rather than repeating the setup
        // hint, which is only useful before the user opts in.
        enabled -> stringResource(R.string.settings_biometric_lock_active)
        availability == BiometricAvailability.Available ->
            stringResource(R.string.settings_biometric_lock_active)
        availability == BiometricAvailability.NoneEnrolled ->
            stringResource(R.string.settings_biometric_lock_setup_hint)
        else -> stringResource(R.string.settings_biometric_lock_unavailable)
    }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag("settings_biometric_lock"),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_biometric_lock_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(12.dp))
            Switch(
                checked = enabled,
                // Allow toggling OFF even if availability has since
                // degraded (e.g. user unenrolled their only fingerprint
                // while the pref was on). Without this the user would
                // be locked into an on-state they can't escape from.
                enabled = canEnable || enabled,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .testTag("switch_biometric_lock")
                    .semantics {
                        contentDescription = if (enabled) {
                            "Biometric app lock enabled. Double-tap to disable."
                        } else {
                            "Biometric app lock disabled. Double-tap to require fingerprint or device PIN to open the app."
                        }
                    },
            )
        }
    }
}
