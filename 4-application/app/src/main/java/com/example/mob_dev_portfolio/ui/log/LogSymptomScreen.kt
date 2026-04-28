package com.example.mob_dev_portfolio.ui.log

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mob_dev_portfolio.AuraApplication
import com.example.mob_dev_portfolio.data.ContextTagCatalog
import com.example.mob_dev_portfolio.data.SymptomCatalog
import com.example.mob_dev_portfolio.data.doctor.DoctorDiagnosis
import com.example.mob_dev_portfolio.data.photo.SymptomPhotoRepository
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val DateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Stages a fresh camera-capture target under `cacheDir/camera_temp/` and
 * returns a content URI routable through the app's FileProvider. The
 * camera app writes the JPEG here; the photo pipeline then reads it
 * back, sanitises it, and encrypts it into internal storage before the
 * intermediate file is eligible for OS cache eviction.
 *
 * A new UUID per call means two rapid-fire captures can't clobber each
 * other, and we never accumulate a pile of old files because the
 * pipeline consumes them on success; failed captures remain in the
 * cache subdir where the OS reclaims them on memory pressure.
 */
private fun createCameraTempUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera_temp").apply { if (!exists()) mkdirs() }
    val file = File(dir, "capture_${UUID.randomUUID()}.jpg")
    val authority = "${context.packageName}.fileprovider"
    return FileProvider.getUriForFile(context, authority, file)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSymptomScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: LogSymptomViewModel = viewModel(factory = LogSymptomViewModel.Factory),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val diagnoses by viewModel.diagnoses.collectAsStateWithLifecycle()
    val healthConditions by viewModel.healthConditions.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current

    // AppContainer hands us the photo repository for the thumbnails —
    // the VM owns *writes*, but the Compose thumbnail loader needs
    // read access without routing through state.
    val photoRepository = remember {
        (context.applicationContext as AuraApplication).container.symptomPhotoRepository
    }

    // The biometric-lock session flag. We tell it to ignore the next
    // `onStop` each time we deliberately start a sibling activity
    // (camera / gallery) so the user doesn't get re-prompted on return
    // and — just as importantly — the `AuraApp` composition isn't torn
    // down, which would wipe `pendingCameraUri` and strand the
    // launcher callback.
    val appLockController = remember {
        (context.applicationContext as AuraApplication).container.appLockController
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onLocationPermissionResult(granted)
    }

    // Gallery picker. PickVisualMedia is the permission-less modern
    // contract — it takes a content-URI grant scoped to the one
    // selected item. No runtime permission is ever requested.
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(viewModel::addPendingPhoto)
    }

    // Camera pipeline. The TakePicture contract writes the JPEG to a
    // URI we provide, so we stage a new file under cacheDir/camera_temp/
    // each time and hand a FileProvider URI to the intent. The URI is
    // kept in rememberSaveable so it survives process death behind the
    // camera app — Android can and does reclaim memory-hungry hosts
    // while a full-screen camera holds the screen, and losing the
    // target URI would mean silently dropping the capture.
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) viewModel.addPendingPhoto(uri)
        pendingCameraUri = null
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onCameraPermissionResult(granted)
        if (granted) {
            val uri = createCameraTempUri(context)
            pendingCameraUri = uri
            // Grant permission flow chains straight into the camera
            // activity — same onStop suppression rationale as the
            // direct-launch path below.
            appLockController.suppressNextRelock()
            cameraLauncher.launch(uri)
        }
    }

    // Seed permission state on first composition — covers the case where the
    // user granted it in a previous session or in system settings.
    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) viewModel.onLocationPermissionResult(true)
        val cameraAlreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (cameraAlreadyGranted) viewModel.onCameraPermissionResult(true)
    }

    LaunchedEffect(uiState.shouldRequestLocationPermission) {
        if (uiState.shouldRequestLocationPermission) {
            viewModel.onLocationPermissionRequestConsumed()
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    LaunchedEffect(uiState.savedConfirmation) {
        uiState.savedConfirmation?.let {
            snackbarHost.showSnackbar(it)
            viewModel.onConfirmationShown()
        }
    }

    LaunchedEffect(uiState.transientError) {
        uiState.transientError?.let {
            snackbarHost.showSnackbar(it)
            viewModel.onTransientErrorShown()
        }
    }

    val isEditing = uiState.editingId != 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit log" else "Log a symptom") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        when (val loadState = uiState.editLoadState) {
            is EditLoadState.Loading -> {
                EditLoadingState(modifier = Modifier.padding(padding).fillMaxSize())
                return@Scaffold
            }
            is EditLoadState.NotFound -> {
                EditNotFoundState(
                    onBack = onBack,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                )
                return@Scaffold
            }
            is EditLoadState.Failed -> {
                EditFailedState(
                    message = loadState.message,
                    onRetry = viewModel::retryLoadForEditing,
                    onBack = onBack,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                )
                return@Scaffold
            }
            else -> Unit
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (uiState.showErrorBanner && uiState.errors.isNotEmpty()) {
                ErrorBanner(
                    errors = uiState.errors.values.toList(),
                    onDismiss = viewModel::onErrorBannerDismissed,
                )
            }

            SectionHeader("Symptom", "What did you experience?")
            SymptomPicker(
                value = uiState.draft.symptomName,
                onValueChange = viewModel::onSymptomNameChange,
                isError = uiState.errors.containsKey(LogField.SymptomName),
                errorText = uiState.errors[LogField.SymptomName],
            )

            OutlinedTextField(
                value = uiState.draft.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text("Description") },
                placeholder = { Text("Briefly describe what you're feeling") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .testTag("field_description"),
                minLines = 3,
                isError = uiState.errors.containsKey(LogField.Description),
                supportingText = uiState.errors[LogField.Description]?.let { { Text(it) } },
            )

            HorizontalDivider()

            SectionHeader("When did it start?", "Defaults to now; tap to edit.")
            DateTimeRow(
                epochMillis = uiState.draft.startEpochMillis,
                onPick = viewModel::onStartDateTimeChange,
                errorText = uiState.errors[LogField.StartDateTime],
                testTagPrefix = "start",
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Symptom has ended", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Record when it stopped",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = uiState.draft.hasEnded,
                    onCheckedChange = viewModel::onHasEndedChange,
                    modifier = Modifier.testTag("switch_has_ended"),
                )
            }

            if (uiState.draft.hasEnded) {
                DateTimeRow(
                    epochMillis = uiState.draft.endEpochMillis ?: uiState.draft.startEpochMillis,
                    onPick = viewModel::onEndDateTimeChange,
                    errorText = uiState.errors[LogField.EndDateTime],
                    testTagPrefix = "end",
                )
            }

            HorizontalDivider()

            SectionHeader("Severity", "How bad did it feel from 1 to 10?")
            SeveritySlider(
                value = uiState.draft.severity,
                onValueChange = viewModel::onSeverityChange,
            )

            HorizontalDivider()

            SectionHeader("Context", "Factors that may have contributed.")
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ContextTagCatalog.tags.forEach { tag ->
                    val selected = uiState.draft.contextTags.contains(tag)
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.onToggleTag(tag) },
                        label = { Text(tag) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("tag_$tag"),
                    )
                }
            }

            OutlinedTextField(
                value = uiState.draft.medication,
                onValueChange = viewModel::onMedicationChange,
                label = { Text("Medication") },
                placeholder = { Text("What did you take? (optional)") },
                modifier = Modifier.fillMaxWidth().testTag("field_medication"),
            )

            OutlinedTextField(
                value = uiState.draft.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text("Additional context") },
                placeholder = { Text("Anything else worth remembering") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .testTag("field_notes"),
                minLines = 3,
            )

            if (diagnoses.isNotEmpty()) {
                HorizontalDivider()
                SectionHeader(
                    "Link to diagnosis",
                    "Tag this log to a doctor-confirmed diagnosis from a visit (optional).",
                )
                DiagnosisPicker(
                    diagnoses = diagnoses,
                    selectedId = uiState.selectedDiagnosisId,
                    onSelect = viewModel::onSelectDiagnosis,
                )
            }

            if (healthConditions.isNotEmpty()) {
                HorizontalDivider()
                SectionHeader(
                    "Group under a condition",
                    "Pin this log to one of your standing health conditions so it shows up grouped on the Symptoms list (optional).",
                )
                HealthConditionPicker(
                    conditions = healthConditions,
                    selectedId = uiState.selectedConditionId,
                    onSelect = viewModel::onSelectCondition,
                )
            }

            HorizontalDivider()

            LocationOptIn(
                attachLocation = uiState.draft.attachLocation,
                permissionGranted = uiState.locationPermissionGranted,
                capturedLocationName = uiState.draft.locationName,
                onToggle = viewModel::onAttachLocationChange,
            )

            HorizontalDivider()

            // FR-PA-01 / 02 / 03 / 05 — Photo attachments strip. The
            // editor composable owns the 3-photo cap UX, remove-with-
            // confirmation dialogs, and thumbnail rendering; we only
            // wire the picker launchers and VM callbacks here.
            val totalAttached = uiState.attachedPhotos.size + uiState.pendingPhotoUris.size
            val capReached = totalAttached >= SymptomPhotoRepository.MAX_PHOTOS_PER_LOG
            PhotoAttachmentsEditor(
                attachedPhotos = uiState.attachedPhotos,
                pendingPhotoUris = uiState.pendingPhotoUris,
                capReached = capReached,
                photoRepository = photoRepository,
                onTakePhoto = {
                    if (uiState.cameraPermissionGranted) {
                        val uri = createCameraTempUri(context)
                        pendingCameraUri = uri
                        // Suppress the biometric re-prompt that would
                        // otherwise fire when the camera activity takes
                        // foreground and our MainActivity hits onStop.
                        appLockController.suppressNextRelock()
                        cameraLauncher.launch(uri)
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onPickFromGallery = {
                    // Photo picker opens its own activity → onStop
                    // fires. Same suppression rationale as the camera
                    // path: otherwise the user re-authenticates and
                    // the callback lands in a freshly-composed screen
                    // that has already forgotten about the pick.
                    appLockController.suppressNextRelock()
                    galleryLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                onRemovePending = viewModel::removePendingPhoto,
                onRemoveAttached = viewModel::removeAttachedPhoto,
            )

            if (uiState.showCameraDeniedRationale) {
                AlertDialog(
                    onDismissRequest = viewModel::onCameraRationaleDismissed,
                    title = { Text("Camera access needed") },
                    text = {
                        Text(
                            "Grant the camera permission in Settings to take photos. " +
                                "You can still pick photos from your gallery without it.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = viewModel::onCameraRationaleDismissed,
                            modifier = Modifier.testTag("btn_camera_rationale_ok"),
                        ) { Text("OK") }
                    },
                    modifier = Modifier.testTag("dialog_camera_denied"),
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.save(onSaved) },
                enabled = !uiState.isSaving && !uiState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("btn_save"),
            ) {
                Text(
                    when {
                        uiState.isSaving -> "Saving…"
                        isEditing -> "Save changes"
                        else -> "Save log"
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Read-only dropdown for pinning the log to one of the doctor-flagged
 * diagnoses. "None" clears the link on save — passed up as a null
 * selection so the VM can issue a detach instead of an attach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosisPicker(
    diagnoses: List<DoctorDiagnosis>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = remember(selectedId, diagnoses) {
        diagnoses.firstOrNull { it.id == selectedId }?.label
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel?.ifBlank { "(unlabelled issue)" } ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text("Diagnosis link") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth()
                .testTag("field_diagnosis_link"),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
                modifier = Modifier.testTag("diagnosis_option_none"),
            )
            diagnoses.forEach { diagnosis ->
                DropdownMenuItem(
                    text = { Text(diagnosis.label.ifBlank { "(unlabelled issue)" }) },
                    onClick = {
                        expanded = false
                        onSelect(diagnosis.id)
                    },
                    modifier = Modifier.testTag("diagnosis_option_${diagnosis.id}"),
                )
            }
        }
    }
}

/**
 * Mirror of [DiagnosisPicker] for user-declared health conditions.
 * Two separate composables (rather than a generic one) because the
 * domain models live in different modules and the test tags differ —
 * keeping them parallel makes the rendering trivial to read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthConditionPicker(
    conditions: List<com.example.mob_dev_portfolio.data.condition.HealthCondition>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = remember(selectedId, conditions) {
        conditions.firstOrNull { it.id == selectedId }?.name
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text("Health condition") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth()
                .testTag("field_condition_link"),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
                modifier = Modifier.testTag("condition_option_none"),
            )
            conditions.forEach { condition ->
                DropdownMenuItem(
                    text = { Text(condition.name) },
                    onClick = {
                        expanded = false
                        onSelect(condition.id)
                    },
                    modifier = Modifier.testTag("condition_option_${condition.id}"),
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(errors: List<String>, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("error_banner"),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Please fix the following:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                errors.forEach { message ->
                    Text("• $message", style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Text("Dismiss")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SymptomPicker(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    errorText: String?,
) {
    var expanded by remember { mutableStateOf(false) }

    // "Custom mode" means the user has opted out of the preset list and
    // wants to type their own symptom — at that point (and only then) the
    // field becomes editable and the soft keyboard can appear. This is the
    // fix for "the keyboard takes over the screen before I've even chosen
    // anything": by default, tapping the field opens the dropdown, not the
    // IME.
    //
    // We seed custom mode to true when the caller hands us a value that
    // isn't in the preset list — e.g. when editing an existing log whose
    // symptom is "Earache" — so the field stays typeable on reopen.
    var customMode by remember(value) {
        mutableStateOf(value.isNotBlank() && value !in SymptomCatalog.presets)
    }

    // When the field is read-only we always want the full preset list in
    // the dropdown. In custom mode the user is typing freely, so we
    // narrow the list to matches (excluding their exact text so there's
    // never a redundant "Headache" item when they've typed "Headache").
    val dropdownOptions = remember(value, customMode) {
        val base = SymptomCatalog.presets + SymptomCatalog.OTHER
        if (!customMode || value.isBlank()) {
            base
        } else {
            base.filter {
                it.equals(SymptomCatalog.OTHER, ignoreCase = true) ||
                    (it.contains(value, ignoreCase = true) && !it.equals(value, ignoreCase = true))
            }
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded && dropdownOptions.isNotEmpty(),
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                // Only reachable while customMode == true — read-only mode
                // silently drops IME keystrokes, so this is a no-op path
                // when the user is picking from presets.
                onValueChange(it)
                expanded = true
            },
            // readOnly suppresses the soft keyboard even when the field is
            // focused. Combined with PrimaryNotEditable below, tapping the
            // field opens the dropdown rather than the IME.
            readOnly = !customMode,
            label = { Text("Symptom type") },
            placeholder = {
                Text(
                    if (customMode) "Type your symptom"
                    else "Choose a preset (or pick Other to type)",
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = isError,
            supportingText = errorText?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier
                .menuAnchor(
                    // PrimaryEditable while custom, PrimaryNotEditable
                    // while picking from presets. The NotEditable variant
                    // tells the anchor to treat taps as "open the menu",
                    // which is what gives us the "no keyboard until Other"
                    // behaviour the user asked for.
                    if (customMode) ExposedDropdownMenuAnchorType.PrimaryEditable
                    else ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = true,
                )
                .fillMaxWidth()
                .testTag("field_symptom"),
        )
        SymptomDropdown(
            expanded = expanded && dropdownOptions.isNotEmpty(),
            onDismiss = { expanded = false },
            options = dropdownOptions,
            onPick = { picked ->
                expanded = false
                if (picked == SymptomCatalog.OTHER) {
                    // Flip to custom mode and clear the field so the user
                    // starts with an empty input rather than seeing the
                    // literal word "Other" sitting in the box.
                    customMode = true
                    onValueChange("")
                } else {
                    // Any preset pick resets custom mode — if the user
                    // typed something and then changed their mind, we go
                    // back to read-only and the keyboard collapses.
                    customMode = false
                    onValueChange(picked)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExposedDropdownMenuBoxScope.SymptomDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<String>,
    onPick: (String) -> Unit,
) {
    ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(option) },
                onClick = { onPick(option) },
            )
        }
    }
}

@Composable
private fun DateTimeRow(
    epochMillis: Long,
    onPick: (Long) -> Unit,
    errorText: String?,
    testTagPrefix: String,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val local = remember(epochMillis) {
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PillButton(
                icon = Icons.Filled.CalendarMonth,
                label = local.format(DateFormatter),
                onClick = { showDatePicker = true },
                modifier = Modifier.weight(1f).testTag("${testTagPrefix}_date"),
            )
            PillButton(
                icon = Icons.Filled.Schedule,
                label = local.format(TimeFormatter),
                onClick = { showTimePicker = true },
                modifier = Modifier.testTag("${testTagPrefix}_time"),
            )
        }
        if (errorText != null) {
            Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showDatePicker) {
        DatePickerSheet(
            initial = local.toLocalDate(),
            onDismiss = { showDatePicker = false },
            onConfirm = { newDate ->
                val updated = LocalDateTime.of(newDate, local.toLocalTime())
                onPick(updated.atZone(zone).toInstant().toEpochMilli())
                showDatePicker = false
            },
        )
    }

    if (showTimePicker) {
        TimePickerSheet(
            initial = local.toLocalTime(),
            onDismiss = { showTimePicker = false },
            onConfirm = { newTime ->
                val updated = LocalDateTime.of(local.toLocalDate(), newTime)
                onPick(updated.atZone(zone).toInstant().toEpochMilli())
                showTimePicker = false
            },
        )
    }
}

@Composable
private fun PillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val initialMillis = initial.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = state.selectedDateMillis ?: initialMillis
                    val date = Instant.ofEpochMilli(picked).atZone(ZoneId.systemDefault()).toLocalDate()
                    onConfirm(date)
                },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("Cancel") }
        },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Select time", style = MaterialTheme.typography.titleLarge)
                TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun EditLoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("edit_loading"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("Loading log…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EditNotFoundState(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("edit_not_found"), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text("Log unavailable", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "This entry has been deleted or is no longer available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 48.dp).testTag("btn_edit_not_found_back"),
            ) { Text("Go back") }
        }
    }
}

@Composable
private fun EditFailedState(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.testTag("edit_failed"), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text("Couldn't load this log", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp).testTag("btn_edit_retry"),
            ) { Text("Try again") }
            TextButton(onClick = onBack) { Text("Go back") }
        }
    }
}

@Composable
private fun LocationOptIn(
    attachLocation: Boolean,
    permissionGranted: Boolean,
    capturedLocationName: String?,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("location_section"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Attach approximate location", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "We fetch a fresh coordinate only when you save, round it to ~1 km, and store the place name — never the exact fix.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = attachLocation,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("switch_attach_location"),
                )
            }
            if (attachLocation && !permissionGranted) {
                Text(
                    "Location permission is required. Toggle again to request it, or enable it in system settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("location_permission_warning"),
                )
            }
            if (attachLocation && permissionGranted && !capturedLocationName.isNullOrBlank()) {
                // Show the stored place name from a previous save. Raw coords
                // are never rendered here.
                Text(
                    "Saved location: $capturedLocationName",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("location_captured_preview"),
                )
            }
        }
    }
}

/**
 * Severity picker redesigned for the mint-clinical expressive theme.
 *
 * The large numeric readout is coloured by the severity scale (mint at
 * the low end, coral at the high end) so the value reinforces the
 * semantics even before the user reads the accompanying band label.
 * The Slider underneath carries the gradient track + coloured thumb; a
 * segmented row gives snap-targets for users who'd rather tap than drag.
 *
 * `testTag("severity_value")` and `testTag("severity_slider")` are kept
 * exactly where the legacy picker placed them so the UI tests that
 * drive them don't need to change.
 */
@Composable
private fun SeveritySlider(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = value.toString(),
                fontFamily = com.example.mob_dev_portfolio.ui.theme.AuraMonoFamily,
                fontWeight = FontWeight.Bold,
                color = com.example.mob_dev_portfolio.ui.theme.severityColor(value),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(72f, androidx.compose.ui.unit.TextUnitType.Sp)),
                modifier = Modifier.testTag("severity_value"),
            )
            Column(modifier = Modifier.padding(bottom = 14.dp)) {
                Text(
                    "SEVERITY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when {
                        value <= 3 -> "Mild"
                        value <= 7 -> "Moderate"
                        else -> "Severe"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        com.example.mob_dev_portfolio.ui.components.SeveritySlider(
            value = value,
            onValueChange = onValueChange,
            min = LogValidator.MIN_SEVERITY,
            max = LogValidator.MAX_SEVERITY,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("severity_slider"),
        )
        com.example.mob_dev_portfolio.ui.components.SeveritySegmentedRow(
            value = value,
            onValueChange = onValueChange,
            min = LogValidator.MIN_SEVERITY,
            max = LogValidator.MAX_SEVERITY,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Mild", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Moderate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Severe", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
