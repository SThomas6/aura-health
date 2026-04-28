package com.example.mob_dev_portfolio.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Strong biometric class-3 with a PIN/pattern/password fallback. Using
 * the combined set means users without fingerprint/face enrolment can
 * still pass the gate via their screen-lock credential — Android hands
 * us a system credential prompt instead of the fingerprint sensor UI.
 *
 * Kept at module scope so availability checks (for the Settings toggle)
 * and the actual prompt use the exact same flag set; mismatches there
 * produce the notorious "authenticators differ" IllegalArgumentException.
 */
const val ALLOWED_AUTHENTICATORS: Int =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

/**
 * Full-screen lock gate shown in place of [com.example.mob_dev_portfolio.ui.AuraApp]
 * whenever the biometric-lock preference is on and the current session
 * hasn't been unlocked yet.
 *
 * The composable owns a [BiometricPrompt] instance bound to the hosting
 * FragmentActivity's lifecycle; on mount we auto-trigger the prompt so
 * the user doesn't have to tap an extra button on every resume. If they
 * cancel, a retry button re-raises the prompt.
 *
 * @param onUnlock Invoked from the authentication-success callback. The
 *     caller is expected to flip session state so the gate composable
 *     stops being emitted on the next recomposition.
 */
@Composable
fun BiometricLockScreen(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current

    // `error` is shown under the retry button when the user cancels or
    // the prompt reports a non-recoverable failure. `null` means no
    // message — either we haven't shown the prompt yet or the last
    // attempt is still in flight.
    var error by remember { mutableStateOf<String?>(null) }
    // Tracks whether we currently have a prompt on screen. Guards against
    // the auto-launch firing twice across config changes / recompositions.
    var inFlight by remember { mutableStateOf(false) }

    val prompt = remember(activity, lifecycleOwner) {
        if (activity == null) return@remember null
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    inFlight = false
                    error = null
                    onUnlock()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    inFlight = false
                    // Negative-button / user-cancel / timeout all surface
                    // here; we just retag them for the retry affordance
                    // rather than treating them as hard failures.
                    error = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED,
                        -> "Authentication cancelled."
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                        -> "Too many attempts. Try again later."
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                        -> "No way to authenticate on this device."
                        else -> errString.toString()
                    }
                }
                // onAuthenticationFailed intentionally not overridden —
                // the library keeps the prompt visible and lets the user
                // retry in-place; there's nothing we want to show yet.
            },
        )
    }

    val promptInfo = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AuraHealth")
            .setSubtitle("Verify it's you to view your health data")
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            // setNegativeButtonText is explicitly forbidden when
            // DEVICE_CREDENTIAL is one of the allowed authenticators —
            // the system provides a "Use PIN" fallback instead, so we
            // leave it unset.
            .setConfirmationRequired(false)
            .build()
    }

    /**
     * Re-raise the prompt on every ON_START — that way a user who
     * cancels, backgrounds the app, and returns gets a fresh prompt
     * without tapping retry first. Guarded on `inFlight` so rapid
     * lifecycle ticks don't stack two prompts.
     */
    DisposableEffect(lifecycleOwner, prompt) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && prompt != null && !inFlight) {
                inFlight = true
                prompt.authenticate(promptInfo)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Kick off immediately on first composition too — the ON_START
    // observer above handles subsequent resumes, but it won't fire when
    // the gate composable is first mounted inside an already-started
    // activity.
    LaunchedEffect(prompt) {
        if (prompt != null && !inFlight) {
            inFlight = true
            prompt.authenticate(promptInfo)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("biometric_lock_screen"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                "AuraHealth is locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                "Authenticate to view your symptom logs, AI analyses, and connected health data.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = {
                    if (prompt != null && !inFlight) {
                        inFlight = true
                        error = null
                        prompt.authenticate(promptInfo)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("btn_biometric_unlock"),
            ) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Unlock")
            }
            error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("biometric_lock_error"),
                )
            }
        }
    }
}

/**
 * Walks the [android.content.ContextWrapper] chain until it hits a
 * [FragmentActivity]. Returns `null` for preview / test contexts that
 * don't have one — callers render a no-op UI in that case so the
 * preview surface doesn't crash.
 */
private tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Availability classification returned by [biometricAvailability]. Used
 * by the Settings toggle to decide whether to enable the switch and,
 * if disabled, what explanatory text to show.
 */
enum class BiometricAvailability {
    /** Ready to go — either strong biometrics are enrolled, or the
     *  device credential (PIN/pattern/password) can satisfy the prompt. */
    Available,

    /** Hardware exists but the user hasn't enrolled anything yet. We
     *  surface a "Set up in Settings" hint. */
    NoneEnrolled,

    /** Hardware is missing or disabled at the OS level — nothing the
     *  user can do from the app. */
    Unavailable,
}

/**
 * Runs a [BiometricManager.canAuthenticate] check against the same
 * flag set the prompt uses. Exposed as a function (not computed in the
 * ViewModel) so Settings and the onboarding flow can share it without
 * taking a DI dependency.
 */
fun biometricAvailability(context: Context): BiometricAvailability {
    val status = BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)
    return when (status) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NoneEnrolled
        else -> BiometricAvailability.Unavailable
    }
}
