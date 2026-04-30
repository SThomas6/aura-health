package com.example.mob_dev_portfolio.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Empty-state composables shown by the symptom editor while loading,
 * when the underlying log has been deleted, or when the read failed
 * with a recoverable error. Extracted from [LogSymptomScreen] so the
 * screen file isn't fighting both the form layout and three full
 * empty-state views in the same place.
 *
 * All three are leaf composables — no Flow wiring, no `remember`,
 * just modifier + callbacks. The `testTag`s match what the editor's
 * existing instrumented tests assert against.
 */

@Composable
internal fun EditLoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("edit_loading"), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text("Loading log…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun EditNotFoundState(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("edit_not_found"), contentAlignment = Alignment.Center) {
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
                "This entry has been deleted or is no longer available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onBack,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("btn_edit_not_found_back"),
            ) { Text("Go back") }
        }
    }
}

@Composable
internal fun EditFailedState(
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
            Text(
                "Couldn't load this log",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("btn_edit_retry"),
            ) { Text("Try again") }
            TextButton(onClick = onBack) { Text("Go back") }
        }
    }
}
