package com.example.mob_dev_portfolio.ui.analysis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.ui.components.auraHeroGradient
import com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Form for kicking off a new AI analysis run.
 *
 * Reached from the Analysis tab's FAB (or the empty-state CTA on first
 * use). The screen collects the user's full name, date of birth, and any
 * additional context they want the model to consider, then enqueues the
 * background `AnalysisWorker`. The worker handles the network call and
 * persistence, and posts a notification on completion — that's why this
 * screen also handles the `POST_NOTIFICATIONS` runtime grant inline.
 *
 * The "ON DEVICE ONLY" pill and surrounding copy explicitly call out
 * that names + raw DOB are stripped before the request — the prompt
 * builder anonymises them to an age range. See the privacy posture
 * documented on the redesign hero card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    viewModel: AnalysisViewModel = viewModel(factory = AnalysisViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Transient errors (offline, timeout, API error) all route through the
    // same Snackbar so the UI stays non-blocking — consistent with the
    // pattern used in LogSymptomScreen.
    LaunchedEffect(state.transientError) {
        state.transientError?.let { message ->
            snackbarHost.showSnackbar(message)
            viewModel.onTransientErrorShown()
        }
    }

    // POST_NOTIFICATIONS runtime permission gate. The notification
    // itself is what delivers the analysis result once the worker
    // finishes; if the user declines we still run the analysis and
    // they can read it in-app, but we ask here — right before the
    // background work is enqueued — because that's exactly when the
    // system-guidance "ask in context" pattern applies.
    //
    // API 32 and below don't require the runtime grant (it's auto-
    // granted at install), so we short-circuit to the trigger.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Regardless of the user's answer we still fire the analysis:
        // the notification is a convenience, not a prerequisite, and
        // the result also lands in the in-app history.
        viewModel.triggerAnalysis()
    }
    val triggerWithPermission: () -> Unit = remember(context) {
        {
            val needsRuntimeGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val alreadyGranted = !needsRuntimeGrant || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (alreadyGranted) {
                viewModel.triggerAnalysis()
            } else {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("AI analysis") }) },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IntroCard()
            ProfileCard(
                fullName = state.fullName,
                dobMillis = state.dobEpochMillis,
                onFullNameChange = viewModel::onFullNameChange,
                onDobChange = viewModel::onDateOfBirthChange,
            )
            ContextCard(
                value = state.userContext,
                enabled = state.phase !is AnalysisPhase.Loading,
                onValueChange = viewModel::onUserContextChange,
            )
            TriggerRow(
                phase = state.phase,
                onTrigger = triggerWithPermission,
            )
            AnalysisResultArea(phase = state.phase)
        }
    }
}

@Composable
private fun IntroCard() {
    // Gradient hero modelled after the redesign's Analysis intro. The
    // "Gemini · Private" eyebrow sets expectations about where the
    // inference runs before the user even reads the body copy.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(auraHeroGradient())
            .padding(20.dp)
            .testTag("analysis_intro"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "GEMINI · PRIVATE",
                    color = Color.White.copy(alpha = 0.85f),
                    fontFamily = AuraMonoFamily,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "AI correlation check",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Your logs and the context you add below are sent to Gemini. Names and exact dates of birth are stripped before the request leaves this device — only an age range is shared.",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileCard(
    fullName: String,
    dobMillis: Long?,
    onFullNameChange: (String) -> Unit,
    onDobChange: (Long?) -> Unit,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Your details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                // Mono-tagged pill that restates the privacy posture —
                // matches the "on device only" badge from the prototype.
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = "ON DEVICE ONLY",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontFamily = AuraMonoFamily,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            OutlinedTextField(
                value = fullName,
                onValueChange = onFullNameChange,
                label = { Text("Full name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("analysis_name_field"),
            )
            OutlinedTextField(
                value = dobMillis?.let { formatDate(it) } ?: "",
                onValueChange = {}, // read-only — picker writes the value
                readOnly = true,
                label = { Text("Date of birth") },
                trailingIcon = {
                    TextButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.testTag("analysis_dob_pick"),
                    ) {
                        Text(if (dobMillis == null) "Set" else "Change")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("analysis_dob_field"),
            )
            Text(
                "We never send the date itself — just an age range like \"25-34\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dobMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onDobChange(pickerState.selectedDateMillis)
                    showDatePicker = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun ContextCard(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Additional context",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Optional — anything extra you'd like the AI to consider.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                label = { Text("What should the AI know?") },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("analysis_context_field"),
            )
        }
    }
}

@Composable
private fun TriggerRow(
    phase: AnalysisPhase,
    onTrigger: () -> Unit,
) {
    val loading = phase is AnalysisPhase.Loading
    Button(
        onClick = onTrigger,
        enabled = !loading,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("analysis_trigger"),
    ) {
        if (loading) {
            Text("Analysing…")
        } else {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Run AI analysis")
        }
    }
}

@Composable
private fun AnalysisResultArea(phase: AnalysisPhase) {
    when (phase) {
        is AnalysisPhase.Idle -> Unit
        is AnalysisPhase.Loading -> {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .testTag("analysis_loading_card"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("analysis_loading_indicator"),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Thinking through your logs…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        is AnalysisPhase.Success -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("analysis_result_card"),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "AI summary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // The headline reflects the two-bucket clinical guidance
                    // that also fronts the result notification — we show it
                    // here so a user opening the app directly (not via the
                    // notification) gets the same top-line takeaway.
                    Text(
                        phase.guidance.headline,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = when (phase.guidance) {
                            com.example.mob_dev_portfolio.data.ai.AnalysisGuidance.Clear ->
                                MaterialTheme.colorScheme.primary
                            com.example.mob_dev_portfolio.data.ai.AnalysisGuidance.SeekAdvice ->
                                MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.testTag("analysis_guidance_headline"),
                    )
                    // Route through our mini markdown renderer so headings and
                    // bullets render as formatted blocks instead of surfacing
                    // literal `**asterisks**` and `## hashes` to the user. Also
                    // strip the internal `GUIDANCE:` / `NHS_REFERENCE:` markers
                    // — the pill above already conveys guidance, and the NHS
                    // disclaimer below replays the reference in a consistent
                    // place every time.
                    MarkdownContent(
                        text = com.example.mob_dev_portfolio.data.ai
                            .AnalysisSummaryFormatter.stripInternalMarkers(phase.summaryText),
                    )
                    Text(
                        "Not a diagnosis. For full symptom information on any condition named, check www.nhs.uk — call 111 for urgent advice or 999 in an emergency.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("analysis_live_nhs_note"),
                    )
                }
            }
        }
    }
}

private fun formatDate(epochMillis: Long): String {
    val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return fmt.format(Date(epochMillis))
}
