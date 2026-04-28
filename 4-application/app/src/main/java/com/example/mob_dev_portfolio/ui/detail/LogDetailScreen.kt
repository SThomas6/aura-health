package com.example.mob_dev_portfolio.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.SymptomLog
import com.example.mob_dev_portfolio.data.doctor.LogDoctorAnnotation
import com.example.mob_dev_portfolio.data.photo.SymptomPhoto
import com.example.mob_dev_portfolio.data.photo.SymptomPhotoRepository
import com.example.mob_dev_portfolio.ui.log.PhotoAttachmentsGallery
import com.example.mob_dev_portfolio.ui.theme.AuraInk
import com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily
import com.example.mob_dev_portfolio.ui.theme.severityColor
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailScreen(
    id: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onDeleted: () -> Unit,
    onOpenVisit: (Long) -> Unit = {},
    viewModel: LogDetailViewModel = viewModel(factory = LogDetailViewModel.factory(id)),
) {
    val log by viewModel.log.collectAsStateWithLifecycle()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val doctorAnnotation by viewModel.doctorAnnotation.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val photoRepository = remember {
        (context.applicationContext as AuraApplication).container.symptomPhotoRepository
    }

    LaunchedEffect(ui.deleted) {
        if (ui.deleted) onDeleted()
    }

    LaunchedEffect(ui.deleteError) {
        ui.deleteError?.let { message ->
            val result = snackbarHost.showSnackbar(
                message = message,
                actionLabel = "Retry",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.retryDelete()
            } else {
                viewModel.dismissDeleteError()
            }
        }
    }

    // "End now" undo flow. The ViewModel emits a one-shot signal carrying
    // the prior end time so the snackbar can offer a perfect restore.
    LaunchedEffect(ui.justEnded) {
        val signal = ui.justEnded ?: return@LaunchedEffect
        val result = snackbarHost.showSnackbar(
            message = "Symptom ended",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoEnd(signal)
        } else {
            viewModel.consumeJustEnded()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log details") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        when (val current = log) {
            is DetailLogState.Loading ->
                LoadingState(modifier = Modifier.padding(padding).fillMaxSize())
            is DetailLogState.NotFound ->
                if (ui.deleted) {
                    Spacer(Modifier.padding(padding))
                } else {
                    NotFoundState(
                        onBack = onBack,
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    )
                }
            is DetailLogState.Loaded -> DetailContent(
                log = current.log,
                photos = photos,
                photoRepository = photoRepository,
                doctorAnnotation = doctorAnnotation,
                isDeleting = ui.isDeleting,
                onEdit = { onEdit(id) },
                onDelete = viewModel::requestDelete,
                onEndNow = viewModel::endNow,
                onOpenVisit = onOpenVisit,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (ui.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Delete this log?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDelete,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("btn_confirm_delete"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::cancelDelete,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("btn_cancel_delete"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("dialog_delete_confirm"),
        )
    }
}

/**
 * Quick-action card shown only on ongoing symptom logs (no end time).
 *
 * Reasons for the dedicated affordance:
 *  - Live symptoms are how the data set goes stale — the user logs a
 *    headache, gets distracted, never returns to mark it ended, and
 *    the timeline turns into an unbounded "headache for 14 days" record.
 *  - The full editor takes ~6 taps to set an end time. This is one tap.
 *  - Undo is offered via snackbar so a fat-finger doesn't lose the
 *    "still happening" state.
 *
 * If the user wants a *specific* past end time (rather than "now"),
 * the Edit affordance below is still the right path.
 */
@Composable
private fun OngoingEndNowCard(onEndNow: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detail_end_now_card"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "This symptom is still ongoing",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Mark it ended now or pick a custom time via Edit.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(
                onClick = onEndNow,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .testTag("detail_end_now_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("End now")
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NotFoundState(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("detail_not_found"), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                "Log unavailable",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "This entry has been deleted or could not be found.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 48.dp).testTag("btn_not_found_back"),
            ) { Text("Go back") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    log: SymptomLog,
    photos: List<SymptomPhoto>,
    photoRepository: SymptomPhotoRepository,
    doctorAnnotation: LogDoctorAnnotation?,
    isDeleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEndNow: () -> Unit,
    onOpenVisit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(log.startEpochMillis).atZone(zone).toLocalDateTime()
    val end = log.endEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("detail_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        doctorAnnotation?.let { annotation ->
            DoctorAnnotationBadge(
                annotation = annotation,
                onClick = { onOpenVisit(annotation.visit.id) },
            )
        }

        // Ongoing-symptom shortcut: one tap stamps `endEpochMillis = now`
        // without sending the user through the full editor. Hidden when
        // the log already has an end time. Undo is available on the
        // snackbar fired immediately afterwards.
        if (log.endEpochMillis == null) {
            OngoingEndNowCard(onEndNow = onEndNow)
        }

        SeverityHeroCard(
            symptomName = log.symptomName,
            severity = log.severity,
            startLabel = start.format(DateTimeFormat),
            durationLabel = formatDuration(log.startEpochMillis, log.endEpochMillis),
        )

        DetailRow(label = "Description", value = log.description, testTag = "detail_description")
        DetailRow(label = "Started", value = start.format(DateTimeFormat), testTag = "detail_start")
        end?.let {
            DetailRow(label = "Ended", value = it.format(DateTimeFormat), testTag = "detail_end")
        }
        if (log.medication.isNotBlank()) {
            DetailRow(label = "Medication", value = log.medication, testTag = "detail_medication")
        }
        if (log.contextTags.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Context",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    log.contextTags.forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                text = tag,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
        if (log.notes.isNotBlank()) {
            DetailRow(label = "Notes", value = log.notes, testTag = "detail_notes")
        }
        if (log.locationLatitude != null && log.locationLongitude != null) {
            // Raw coordinates are intentionally NOT rendered — we show only the
            // pre-computed place name. If the DB row is null (pre-migration or
            // failed geocoding), the "Location unavailable" fallback is shown.
            DetailRow(
                label = "Approximate location",
                value = log.locationName?.takeIf { it.isNotBlank() } ?: "Location unavailable",
                testTag = "detail_location",
            )
        }

        // FR-PA-04 — photo gallery. Read-only strip with fullscreen on
        // tap; add/remove lives on the edit screen. Only renders when
        // there's at least one photo, so logs without attachments look
        // exactly as before.
        if (photos.isNotEmpty()) {
            PhotoAttachmentsGallery(
                photos = photos,
                photoRepository = photoRepository,
            )
        }

        val envLines = buildEnvironmentalLines(log)
        if (envLines.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .testTag("detail_environment"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Environmental conditions",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    envLines.forEach { (label, value) ->
                        Text(
                            "$label: $value",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onEdit,
            enabled = !isDeleting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("btn_edit"),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Edit log")
        }

        OutlinedButton(
            onClick = onDelete,
            enabled = !isDeleting,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("btn_delete"),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isDeleting) "Deleting…" else "Delete log")
        }

        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Build label → value pairs for whichever environmental fields are non-null
 * on this log. A log with no location (and therefore no fetch) produces an
 * empty list and the environment card is hidden altogether.
 */
private fun buildEnvironmentalLines(log: SymptomLog): List<Pair<String, String>> {
    val lines = mutableListOf<Pair<String, String>>()
    log.weatherDescription?.takeIf { it.isNotBlank() }?.let {
        lines += "Weather" to it
    }
    log.temperatureCelsius?.let {
        lines += "Temperature" to "${"%.1f".format(it)} °C"
    }
    log.humidityPercent?.let {
        lines += "Humidity" to "$it%"
    }
    log.pressureHpa?.let {
        lines += "Pressure" to "${"%.1f".format(it)} hPa"
    }
    log.airQualityIndex?.let {
        lines += "Air quality (EAQI)" to it.toString()
    }
    return lines
}

/**
 * Hero card for the detail screen. Sits at the top of the scroll and is
 * the "this is how bad it was" readout — gradient paints from Aura ink
 * (deep mint) through primary into the severity colour so the hue
 * telegraphs the severity before the numbers register. The severity
 * number is rendered in the mono family to match the rest of the app's
 * quantitative readouts.
 */
@Composable
private fun SeverityHeroCard(
    symptomName: String,
    severity: Int,
    startLabel: String,
    durationLabel: String?,
) {
    val sev = severityColor(severity.coerceIn(1, 10))
    val gradient = Brush.linearGradient(
        colors = listOf(AuraInk, MaterialTheme.colorScheme.primary, sev),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(gradient)
            .padding(20.dp)
            .testTag("detail_hero"),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "SYMPTOM",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    symptomName,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("detail_symptom"),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    startLabel,
                    color = Color.White.copy(alpha = 0.85f),
                    fontFamily = AuraMonoFamily,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!durationLabel.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            "Lasted $durationLabel",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            fontFamily = AuraMonoFamily,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = severity.toString(),
                    color = Color.White,
                    fontFamily = AuraMonoFamily,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "/ 10",
                    color = Color.White.copy(alpha = 0.75f),
                    fontFamily = AuraMonoFamily,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Pretty-prints the elapsed time between `start` and `end`. Returns null
 * when the log is still ongoing (no end timestamp) so the caller can
 * skip rendering the "Lasted …" badge altogether.
 */
private fun formatDuration(startMillis: Long, endMillis: Long?): String? {
    if (endMillis == null || endMillis <= startMillis) return null
    val duration = Duration.ofMillis(endMillis - startMillis)
    val hours = duration.toHours()
    val minutes = (duration.toMinutes() % 60).toInt()
    return when {
        hours >= 24 -> "${duration.toDays()}d ${hours % 24}h"
        hours >= 1 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

/**
 * Tap-through badge shown above the hero when this log has been
 * cleared by a doctor visit or linked to one of its diagnoses. Tapping
 * it opens the source visit so the user can see why the AI treats the
 * log differently.
 */
@Composable
private fun DoctorAnnotationBadge(
    annotation: LogDoctorAnnotation,
    onClick: () -> Unit,
) {
    val (icon, headline, subtext, tag) = when (annotation) {
        is LogDoctorAnnotation.Cleared -> BadgeContent(
            icon = Icons.Filled.CheckCircle,
            headline = "Reviewed & cleared",
            subtext = "The AI ignores this log in future analyses.",
            tag = "detail_annotation_cleared",
        )
        is LogDoctorAnnotation.LinkedToDiagnosis -> BadgeContent(
            icon = Icons.Filled.MedicalServices,
            headline = "Linked to: ${annotation.diagnosis.label.ifBlank { "(unlabelled issue)" }}",
            subtext = "The AI treats this as already-explained context.",
            tag = "detail_annotation_linked",
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtext,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private data class BadgeContent(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val headline: String,
    val subtext: String,
    val tag: String,
)

@Composable
private fun DetailRow(label: String, value: String, testTag: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).testTag(testTag),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
