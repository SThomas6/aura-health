package com.example.mob_dev_portfolio.ui.health

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.data.health.HealthConnectMetric
import com.example.mob_dev_portfolio.data.health.HealthConnectService
import com.example.mob_dev_portfolio.data.health.HealthMetricCategory

/**
 * Per-metric Health Connect opt-in screen.
 *
 * Three paths through the UI:
 *
 *   1. **SDK missing or out of date** — we replace the toggles with a
 *      single "Install Health Connect" CTA that fires a Play Store
 *      intent. Flipping toggles is meaningless in this state, so we
 *      hide them entirely to avoid the user toggling preferences that
 *      then silently do nothing.
 *
 *   2. **SDK available, no grants yet** — a top-of-screen "Connect"
 *      button fires the permission-request contract for all catalogue
 *      metrics in one dialog. The toggles remain visible and clickable;
 *      flipping one implies a grant request for that metric only.
 *
 *   3. **SDK available, some grants held** — the master switch is on,
 *      per-metric rows show a green check for granted metrics, and
 *      the "Disconnect" action at the bottom clears both the toggles
 *      and the in-app state (Health Connect itself keeps the grants
 *      until the user revokes them via the system UI; this is Google's
 *      model, not ours).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthDataSettingsScreen(
    onBack: () -> Unit,
    viewModel: HealthDataSettingsViewModel = viewModel(factory = HealthDataSettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // The Health Connect permission contract returns the set of grants
    // currently held (after the user's decision), NOT the delta. We push
    // that straight into the VM so grant reconciliation happens once.
    // The set we request includes both read perms for every catalogue
    // metric and write perms for the auto-seeder; the VM silently seeds
    // two weeks of demo data the first time grants land so the dashboard
    // has graphs to render out of the box.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        viewModel.onPermissionsResult(granted)
    }

    // Refresh grants whenever the screen resumes — the user may have
    // revoked permissions in the system Health Connect app since we
    // last checked.
    LaunchedEffect(Unit) { viewModel.refreshGrants() }

    // Surface transient messages (e.g. "Disconnected") as a snackbar.
    LaunchedEffect(state.transientMessage) {
        val msg = state.transientMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.dismissMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health data") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("health_settings_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IntroCard()

            when (state.sdkStatus) {
                HealthConnectService.Status.ProviderMissing,
                HealthConnectService.Status.ProviderUpdateRequired -> InstallPromptCard(
                    status = state.sdkStatus,
                    onInstall = {
                        val intent: Intent = (viewModel.state.value.sdkStatus.let {
                            // Pull the intent from the service via the VM's
                            // container — done via a free function here to
                            // avoid plumbing the service into the screen.
                            (context.applicationContext as com.example.mob_dev_portfolio.AuraApplication)
                                .container.healthConnectService.buildInstallIntent()
                        }) ?: return@InstallPromptCard
                        context.startActivity(intent)
                    },
                )
                HealthConnectService.Status.Unsupported -> UnsupportedCard()
                HealthConnectService.Status.Available -> {
                    MasterToggleCard(
                        connectionActive = state.connectionActive,
                        integrationEnabled = state.integrationEnabled,
                        onConnect = {
                            // Always fire the permission contract. On a
                            // fresh connect the user sees the HC dialog;
                            // on reconnect after Disconnect the previous
                            // grants were revoked so the dialog also
                            // appears. The seeder runs in
                            // onPermissionsResult once grants land.
                            permissionLauncher.launch(viewModel.allCatalogPermissions())
                        },
                        onToggleAiInclude = viewModel::setIntegrationEnabled,
                        onDisconnect = viewModel::disconnect,
                    )
                    MetricCategoriesSection(
                        rows = state.metricRows,
                        onMetricToggle = { metric, enabled ->
                            viewModel.setMetricEnabled(metric, enabled)
                            // If the user is flipping a metric on, prompt
                            // for that single permission straight away so
                            // the toggle doesn't land in a "enabled, not
                            // granted" limbo with no obvious way to fix it.
                            if (enabled && !state.metricRows.first { it.metric == metric }.granted) {
                                permissionLauncher.launch(setOf(metric.readPermission))
                            }
                        },
                        onGrantRow = { metric ->
                            permissionLauncher.launch(setOf(metric.readPermission))
                        },
                    )
                }
            }

            PrivacyFooter()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IntroCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Health data integration",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "AuraHealth can read metrics from Android Health Connect to give the AI richer context — without sending raw readings off your device.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Only aggregated summaries (7-day totals, 24-hour-preceding windows) are included in the analysis prompt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstallPromptCard(
    status: HealthConnectService.Status,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (status == HealthConnectService.Status.ProviderUpdateRequired)
                    "Health Connect needs updating"
                else "Install Health Connect to continue",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Health Connect is Google's on-device hub for health metrics. AuraHealth reads from it — without it, the AI analysis runs on your symptom logs alone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onInstall,
                modifier = Modifier.testTag("health_install_cta"),
            ) {
                Text(
                    if (status == HealthConnectService.Status.ProviderUpdateRequired)
                        "Update" else "Install",
                )
            }
        }
    }
}

@Composable
private fun UnsupportedCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Not supported on this device",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Your Android version doesn't support Health Connect. The AI analysis still works on your symptom logs.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MasterToggleCard(
    connectionActive: Boolean,
    integrationEnabled: Boolean,
    onConnect: () -> Unit,
    onToggleAiInclude: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header block — describes the connection state, not the AI
            // inclusion state. Those are orthogonal.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (connectionActive) "Health Connect is linked"
                    else "Health Connect is not linked",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (connectionActive)
                        "Your dashboard charts read live from Health Connect. You can still stop the AI from using these readings below."
                    else
                        "Connect to grant read access and have AuraHealth seed two weeks of demo data so the dashboard has something to show.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Primary CTA — drives the connection state only.
            if (!connectionActive) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("health_connect_cta"),
                ) { Text("Connect Health Connect") }
            } else {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("health_disconnect_cta"),
                ) { Text("Disconnect") }
            }

            // Secondary toggle — only meaningful when connected. We
            // leave it visible either way so the user can see the AI
            // opt-in exists before they connect.
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Include in AI analysis",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (connectionActive)
                            "Feed recent readings into the AI prompt alongside your symptom logs."
                        else
                            "Connect Health Connect first to enable this option.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = integrationEnabled,
                    onCheckedChange = onToggleAiInclude,
                    enabled = connectionActive,
                    modifier = Modifier.testTag("health_master_switch"),
                )
            }
        }
    }
}

@Composable
private fun MetricCategoriesSection(
    rows: List<HealthMetricRowState>,
    onMetricToggle: (HealthConnectMetric, Boolean) -> Unit,
    onGrantRow: (HealthConnectMetric) -> Unit,
) {
    val grouped = rows.groupBy { it.metric.category }
    HealthMetricCategory.entries.forEach { category ->
        val subset = grouped[category].orEmpty()
        if (subset.isEmpty()) return@forEach
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = category.displayLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                subset.forEachIndexed { idx, row ->
                    MetricRow(
                        row = row,
                        onToggle = { onMetricToggle(row.metric, it) },
                        onGrant = { onGrantRow(row.metric) },
                    )
                    if (idx < subset.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MetricRow(
    row: HealthMetricRowState,
    onToggle: (Boolean) -> Unit,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("health_metric_${row.metric.storageKey}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.metric.displayLabel, style = MaterialTheme.typography.bodyLarge)
            // The first two arms cover every `row.enabled == true` case,
            // so the third arm is reached only when `enabled == false` —
            // we can use `else` instead of the redundant `!row.enabled`.
            val subtitle = when {
                row.enabled && row.granted -> "Enabled and permission granted."
                row.enabled && !row.granted -> "Enabled — permission needed."
                else -> "Toggle on to include in the AI analysis."
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.enabled && !row.granted) {
            TextButton(onClick = onGrant) { Text("Grant") }
        }
        Switch(
            checked = row.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag("health_toggle_${row.metric.storageKey}"),
        )
    }
}

@Composable
private fun PrivacyFooter() {
    Text(
        text = "Permissions can be revoked any time from the Health Connect app. AuraHealth only reads — it never writes back.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

