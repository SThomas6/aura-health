package com.example.mob_dev_portfolio.ui.log

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mob_dev_portfolio.data.photo.SymptomPhoto
import com.example.mob_dev_portfolio.data.photo.SymptomPhotoRepository

/**
 * Editor-side photo strip (add / remove).
 *
 * Shows "already attached" photos from the DB (edit mode) alongside
 * "pending" URIs the user has picked this session (which the VM will
 * flush through the attach pipeline on save). Remove is wired to two
 * different callbacks because the two kinds of photos have different
 * identity — one is a Room row id, the other is the source content
 * URI the user hasn't yet committed.
 *
 * The two CTAs (camera + gallery) are rendered alongside the
 * thumbnails so the affordance sits where the user's eye already is.
 * They disable themselves once the 3-photo cap is hit rather than
 * silently failing the ingest — the cap is the FR-PA-01 number and
 * users should know when they've hit it.
 */
@Composable
fun PhotoAttachmentsEditor(
    attachedPhotos: List<SymptomPhoto>,
    pendingPhotoUris: List<Uri>,
    capReached: Boolean,
    photoRepository: SymptomPhotoRepository,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    onRemovePending: (Uri) -> Unit,
    onRemoveAttached: (SymptomPhoto) -> Unit,
) {
    var confirmRemoveAttached by remember { mutableStateOf<SymptomPhoto?>(null) }
    var confirmRemovePending by remember { mutableStateOf<Uri?>(null) }
    var fullscreenBitmap by remember { mutableStateOf<Bitmap?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("photo_attachments_section"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Photo attachments", style = MaterialTheme.typography.titleMedium)
                val totalCount = attachedPhotos.size + pendingPhotoUris.size
                Text(
                    "Up to ${SymptomPhotoRepository.MAX_PHOTOS_PER_LOG} photos per log. " +
                        "Stored encrypted on this device; never sent to AI analysis. ($totalCount/${SymptomPhotoRepository.MAX_PHOTOS_PER_LOG})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (attachedPhotos.isNotEmpty() || pendingPhotoUris.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("photo_attachments_strip"),
                ) {
                    items(
                        items = attachedPhotos,
                        key = { photo -> "attached-${photo.id}" },
                    ) { photo ->
                        AttachedThumbnail(
                            photo = photo,
                            photoRepository = photoRepository,
                            onClick = { bitmap -> fullscreenBitmap = bitmap },
                            onRemoveClick = { confirmRemoveAttached = photo },
                        )
                    }
                    items(
                        items = pendingPhotoUris,
                        key = { uri -> "pending-${uri}" },
                    ) { uri ->
                        PendingThumbnail(
                            uri = uri,
                            onClick = { bitmap -> fullscreenBitmap = bitmap },
                            onRemoveClick = { confirmRemovePending = uri },
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(
                    onClick = onTakePhoto,
                    enabled = !capReached,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp)
                        .testTag("btn_take_photo"),
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Take photo")
                }
                FilledTonalButton(
                    onClick = onPickFromGallery,
                    enabled = !capReached,
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = 48.dp)
                        .testTag("btn_pick_gallery"),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery")
                }
            }
        }
    }

    // FR-PA-05 — remove-with-confirmation. Both paths (pending vs
    // attached) land in the same modal pattern so the user gets the
    // same affordance regardless of whether the photo has been
    // committed to disk yet.
    confirmRemoveAttached?.let { photo ->
        AlertDialog(
            onDismissRequest = { confirmRemoveAttached = null },
            title = { Text("Remove photo?") },
            text = { Text("This photo will be deleted from this log.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveAttached(photo)
                        confirmRemoveAttached = null
                    },
                    modifier = Modifier.testTag("btn_confirm_remove_photo"),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveAttached = null }) { Text("Cancel") }
            },
        )
    }

    confirmRemovePending?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRemovePending = null },
            title = { Text("Remove photo?") },
            text = { Text("This photo hasn't been saved yet; it will be discarded.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemovePending(uri)
                        confirmRemovePending = null
                    },
                    modifier = Modifier.testTag("btn_confirm_remove_pending_photo"),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemovePending = null }) { Text("Cancel") }
            },
        )
    }

    fullscreenBitmap?.let { bitmap ->
        FullscreenPhotoDialog(
            bitmap = bitmap,
            onDismiss = { fullscreenBitmap = null },
        )
    }
}

/**
 * Read-only photo strip for the detail screen. No edit affordances —
 * users go through the "Edit log" CTA to add or remove. Tapping a
 * thumbnail opens the fullscreen dialog (FR-PA-04).
 */
@Composable
fun PhotoAttachmentsGallery(
    photos: List<SymptomPhoto>,
    photoRepository: SymptomPhotoRepository,
) {
    if (photos.isEmpty()) return
    var fullscreenBitmap by remember { mutableStateOf<Bitmap?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Photos",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("photo_gallery_strip"),
        ) {
            items(
                items = photos,
                key = { photo -> "gallery-${photo.id}" },
            ) { photo ->
                AttachedThumbnail(
                    photo = photo,
                    photoRepository = photoRepository,
                    onClick = { bitmap -> fullscreenBitmap = bitmap },
                    onRemoveClick = null,
                )
            }
        }
    }

    fullscreenBitmap?.let { bitmap ->
        FullscreenPhotoDialog(
            bitmap = bitmap,
            onDismiss = { fullscreenBitmap = null },
        )
    }
}

@Composable
private fun AttachedThumbnail(
    photo: SymptomPhoto,
    photoRepository: SymptomPhotoRepository,
    onClick: (Bitmap) -> Unit,
    onRemoveClick: (() -> Unit)?,
) {
    // Decrypt + decode into a local state. Keying on id means rotating
    // the device doesn't re-run the IO for the same photo, and Flow
    // updates swap in new bitmaps cleanly. Errors are swallowed and
    // surface as the fallback placeholder — we never crash the editor
    // on a corrupt file.
    var bitmap by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(photo.id) { mutableStateOf(false) }

    LaunchedEffect(photo.id) {
        val bytes = photoRepository.readBytes(photo)
        if (bytes == null) {
            failed = true
        } else {
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            failed = bitmap == null
        }
    }

    ThumbnailFrame(
        bitmap = bitmap,
        failed = failed,
        onClick = { bitmap?.let(onClick) },
        onRemoveClick = onRemoveClick,
    )
}

@Composable
private fun PendingThumbnail(
    uri: Uri,
    onClick: (Bitmap) -> Unit,
    onRemoveClick: () -> Unit,
) {
    // Pending URIs bypass the encrypted store (they haven't been
    // committed yet). We load them through the standard content
    // resolver — same stream path the attach pipeline will use on
    // save — so the preview the user sees matches what will end up
    // on disk.
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                bitmap = BitmapFactory.decodeStream(stream)
            }
            if (bitmap == null) failed = true
        } catch (_: Exception) {
            failed = true
        }
    }

    ThumbnailFrame(
        bitmap = bitmap,
        failed = failed,
        onClick = { bitmap?.let(onClick) },
        onRemoveClick = onRemoveClick,
    )
}

@Composable
private fun ThumbnailFrame(
    bitmap: Bitmap?,
    failed: Boolean,
    onClick: () -> Unit,
    onRemoveClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Photo attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            failed -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Unavailable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        if (onRemoveClick != null) {
            IconButton(
                onClick = onRemoveClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .testTag("btn_remove_photo"),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove photo",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * FR-PA-04 — fullscreen preview. Semi-transparent scrim, tap or the
 * system back button dismisses. We render the bitmap at
 * aspect-preserving fit so a portrait photo doesn't stretch on a
 * landscape tablet.
 */
@Composable
private fun FullscreenPhotoDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss)
                .testTag("photo_fullscreen"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Photo attachment fullscreen",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f))
                    .padding(16.dp),
            )
            IconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .testTag("btn_close_fullscreen"),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }
    }
}

/**
 * Padding row subtitled line used by the editor photo section — kept
 * here rather than inlined so typography tweaks land in one place.
 * Not currently referenced externally but left intentionally so tests
 * (and future copy changes) can reach it.
 */
@Suppress("unused")
@Composable
private fun PhotoSectionHint(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .height(6.dp)
                .width(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}
