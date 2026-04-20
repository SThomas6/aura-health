package com.example.mob_dev_portfolio.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    viewModel: AnalysisViewModel = viewModel(factory = AnalysisViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    // Transient errors (offline, timeout, API error) all route through the
    // same Snackbar so the UI stays non-blocking — consistent with the
    // pattern used in LogSymptomScreen.
    LaunchedEffect(state.transientError) {
        state.transientError?.let { message ->
            snackbarHost.showSnackbar(message)
            viewModel.onTransientErrorShown()
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
                onTrigger = viewModel::triggerAnalysis,
            )
            AnalysisResultArea(phase = state.phase)
        }
    }
}

@Composable
private fun IntroCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Spacer(Modifier.height(4.dp))
                Text(
                    "  AI correlation check",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Your logs and the context you add below are sent to Gemini. Names and exact dates of birth are stripped before the request leaves this device — only an age range is shared.",
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
            Text(
                "Your details (stored on device only)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
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
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier
                    .height(20.dp)
                    .testTag("analysis_loading_indicator"),
            )
            Spacer(Modifier.height(4.dp))
            Text("  Analysing…")
        } else {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
            Spacer(Modifier.height(4.dp))
            Text("  Run AI analysis")
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
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(24.dp))
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "   Thinking through your logs…",
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
                    // Route through our mini markdown renderer so headings and
                    // bullets render as formatted blocks instead of surfacing
                    // literal `**asterisks**` and `## hashes` to the user.
                    MarkdownContent(text = phase.summaryText)
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
