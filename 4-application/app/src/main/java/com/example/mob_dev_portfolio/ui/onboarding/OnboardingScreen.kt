package com.example.mob_dev_portfolio.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.ui.components.auraHeroGradient
import com.example.mob_dev_portfolio.ui.health.HealthDataSettingsViewModel
import kotlinx.coroutines.launch

/**
 * First-launch onboarding.
 *
 * Each step is shown in sequence (rather than a horizontal pager) because
 * the prompts are *independent but ordered* — a page-swipe gesture would
 * let users skip a request without reading the rationale, and the UX goal
 * is specifically to front-load the "why" before Android's system dialog
 * appears. A next-button-driven flow keeps the rationale and the grant
 * request visually glued together.
 *
 * Steps:
 *  1. Welcome card.
 *  2. Notifications (API 33+ only — skipped automatically on older OSes,
 *     where the manifest entry is sufficient).
 *  3. Approximate location — rationale for the weather/context tagging
 *     on symptom logs.
 *  4. Health Connect — launches the per-metric permission contract with
 *     the same catalogue used by the Settings screen.
 *  5. Done — a "Start tracking" CTA persists `onboardingComplete=true`
 *     which is what triggers [MainActivity] to swap to [AuraApp].
 *
 * Each permission card surfaces the *current* grant status so re-running
 * the flow (in manual-test scenarios, or after a user clears the flag)
 * is idempotent — the Allow button changes to "Granted" once the prompt
 * returns successfully, rather than re-firing the system dialog.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    healthDataViewModel: HealthDataSettingsViewModel = viewModel(
        factory = HealthDataSettingsViewModel.Factory,
    ),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Mirror the same UI state the Settings screen uses so the onboarding
    // HC step flips `connectionActive` + `integrationEnabled` via the VM,
    // triggering the auto-seeder on first grant. Without this wiring the
    // system permission dialog would grant at the HC provider but the
    // app's internal toggles would stay off — the exact "clicked Allow but
    // nothing happened" bug the user reported.
    val healthState by healthDataViewModel.state.collectAsStateWithLifecycle()

    // Step index survives recomposition + process death so a mid-flow
    // rotation or system-kill doesn't bump the user back to the welcome
    // card. We don't persist step to DataStore because the onboarding is
    // by definition short-lived — if the process is killed *and* the
    // DataStore flag never flipped, restarting from the welcome card is
    // the right call.
    var step by rememberSaveable { mutableIntStateOf(0) }

    // Grant status is mirrored locally so the card UI can show "Granted"
    // without waiting on a VM round-trip. `remember` (not
    // rememberSaveable) is fine — on process death these seed from the
    // checkSelfPermission LaunchedEffect below.
    var notificationsGranted by remember { mutableStateOf(false) }
    var locationGranted by remember { mutableStateOf(false) }
    // Derived — the VM's `connectionActive` is the source of truth for
    // "HC step complete". Using the VM flag rather than a local boolean
    // means a mid-onboarding rotation or back-navigation doesn't lose the
    // granted state (the flag is DataStore-backed).
    val healthConnectGranted = healthState.connectionActive

    // Seed the booleans on first composition so a user returning to the
    // flow with perms already granted sees "Granted" chips rather than
    // fresh "Allow" buttons.
    LaunchedEffect(Unit) {
        notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-API-33 the manifest entry is enough — skip the prompt.
            true
        }
        locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        // Health Connect grants live in the provider, not the app — we
        // optimistically set false and let the launcher callback flip it.
        // Re-checking on every launch would require a coroutine round-trip
        // through HealthConnectClient; the Settings screen already does
        // this so users can always revisit it later.
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        locationGranted = granted
    }

    val healthConnectLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { grants ->
        // Hand the full grant set to the VM — it reconciles into
        // `connectionActive`, `integrationEnabled`, and fires the sample
        // seeder. The UI reads `healthConnectGranted` off the VM state,
        // so this is the only wiring needed.
        healthDataViewModel.onPermissionsResult(grants)
    }

    // Request the same permission bundle the Settings screen uses — reads
    // for every catalogue metric *and* the write perms the auto-seeder
    // needs. Requesting them together means the user sees one system
    // dialog and the seeder can run silently once grants land.
    val healthConnectPermissions = remember { healthDataViewModel.allCatalogPermissions() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .testTag("onboarding_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepIndicator(current = step, total = totalSteps())
            when (step) {
                0 -> WelcomeCard()
                1 -> PermissionCard(
                    icon = Icons.Filled.Notifications,
                    title = "Stay in the loop",
                    rationale = "Aura runs a weekly AI check-in on your logs. You'll " +
                        "only get a notification if it spots something worth a look — " +
                        "never for an \"all clear\". You can change this in system " +
                        "settings any time.",
                    granted = notificationsGranted,
                    onRequest = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notificationsGranted = true
                        }
                    },
                    testTag = "onboarding_notifications",
                )
                2 -> PermissionCard(
                    icon = Icons.Filled.LocationOn,
                    title = "Approximate location",
                    rationale = "When you log a symptom, Aura can tag it with your " +
                        "rough area so patterns like weather, altitude or commute " +
                        "become easier to spot. We only read a coarse fix — never a " +
                        "precise GPS coordinate — and you can decline without losing " +
                        "any other feature.",
                    granted = locationGranted,
                    onRequest = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    testTag = "onboarding_location",
                )
                3 -> PermissionCard(
                    icon = Icons.Filled.Favorite,
                    title = "Health Connect",
                    rationale = "Let Aura read vitals like steps, heart rate and sleep " +
                        "from your phone's Health Connect hub. The AI analysis uses these " +
                        "alongside your symptom logs to spot trends you might miss. You " +
                        "pick which metrics to share on the next screen, and you can " +
                        "revoke access at any time from Settings → Health data.",
                    granted = healthConnectGranted,
                    onRequest = { healthConnectLauncher.launch(healthConnectPermissions) },
                    testTag = "onboarding_health_connect",
                )
                4 -> HealthConditionsStepCard()
                else -> ReadyCard()
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(visible = step in 1..(totalSteps() - 2), enter = fadeIn(), exit = fadeOut()) {
                    TextButton(
                        onClick = { step = (step + 1).coerceAtMost(totalSteps() - 1) },
                        modifier = Modifier.testTag("onboarding_skip"),
                    ) {
                        Text("Skip for now")
                    }
                }
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = {
                        if (step < totalSteps() - 1) {
                            step += 1
                        } else {
                            // Persist the completion flag first, then call
                            // back — MainActivity collects the flow and
                            // swaps the UI once it flips. Using a detached
                            // scope would be wrong here: we want the write
                            // to be tied to the composition's scope so a
                            // navigation-away cancels any in-flight edit.
                            scope.launch {
                                (context.applicationContext as AuraApplication)
                                    .container.uiPreferencesRepository
                                    .setOnboardingComplete(true)
                                onFinish()
                            }
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("onboarding_next"),
                ) {
                    Text(if (step < totalSteps() - 1) "Next" else "Start tracking")
                }
            }
        }
    }
}

/** 5 steps: welcome, notifications, location, health connect, ready. */
private fun totalSteps(): Int = 6

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .background(
                        color = if (i <= current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(2.dp),
                    )
                    .weight(1f, fill = true)
                    .testTag("onboarding_step_${i}_${if (i <= current) "on" else "off"}"),
            )
        }
    }
}

@Composable
private fun WelcomeCard() {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(auraHeroGradient())
                .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.WavingHand,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Text(
                    "Welcome to Aura",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "A private, on-device companion for tracking symptoms and " +
                        "spotting patterns over time. Before we begin, we'll walk " +
                        "through the few permissions Aura uses — and why.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    rationale: String,
    granted: Boolean,
    onRequest: () -> Unit,
    testTag: String,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "Granted — you're all set",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("${testTag}_allow"),
                ) {
                    Text("Allow", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Onboarding step that lets the user record any chronic / pre-existing
 * health conditions (e.g. "Type 2 Diabetes", "Asthma"). Goes straight
 * into the [com.example.mob_dev_portfolio.data.condition.HealthConditionRepository]
 * so the AI's already-explained context bundle picks it up from the
 * very first analysis run.
 *
 * Inline editor (no nav round-trip) so a user mid-onboarding doesn't
 * lose the step indicator or get bumped back into a sub-flow. Skipping
 * is a no-op — the bottom-bar Next button advances regardless.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HealthConditionsStepCard() {
    val context = LocalContext.current
    val repository = remember {
        (context.applicationContext as AuraApplication).container.healthConditionRepository
    }
    val conditions by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_conditions"),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.HealthAndSafety,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                "Any pre-existing conditions?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Tell Aura about any chronic or already-diagnosed health " +
                    "issues — diabetes, asthma, migraine disorders, etc. The AI " +
                    "uses these as background context, and you'll be able to " +
                    "group your symptom logs under each one. Skip if you'd " +
                    "rather add them later from Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("e.g. Type 2 Diabetes") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("onboarding_conditions_input"),
                )
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = {
                        val name = draft.trim()
                        if (name.isNotBlank()) {
                            scope.launch { repository.upsert(name = name) }
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.testTag("onboarding_conditions_add"),
                ) { Text("Add") }
            }
            if (conditions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    conditions.forEach { c ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(c.name) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { scope.launch { repository.delete(c.id) } },
                                    modifier = Modifier
                                        .size(24.dp)
                                        .testTag("onboarding_conditions_remove_${c.id}"),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyCard() {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(auraHeroGradient())
                .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Text(
                    "You're ready to go",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "You can revisit every permission later under Settings. " +
                        "Tap \"Start tracking\" below to jump into your first log.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}
