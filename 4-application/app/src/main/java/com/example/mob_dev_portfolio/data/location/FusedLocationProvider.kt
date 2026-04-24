package com.example.mob_dev_portfolio.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Privacy-preserving location provider.
 *
 * Uses **only** [FusedLocationProviderClient.getCurrentLocation] — a single-shot
 * request — and never [FusedLocationProviderClient.getLastLocation] (stale
 * cache) or [FusedLocationProviderClient.requestLocationUpdates] (background
 * polling). The [CancellationTokenSource] is wired to the coroutine so if the
 * caller cancels, the Play Services request is cancelled too: nothing ever
 * runs in the background after this suspend function returns.
 *
 * We request [Priority.PRIORITY_BALANCED_POWER_ACCURACY] (≈100m accuracy) —
 * sufficient for 2 decimal places (~1.1 km grid) and pairs naturally with
 * the [Manifest.permission.ACCESS_COARSE_LOCATION] permission in the manifest.
 */
class FusedLocationProvider(
    private val context: Context,
    private val clientFactory: (Context) -> FusedLocationProviderClient = {
        LocationServices.getFusedLocationProviderClient(it)
    },
) : LocationProvider {

    override suspend fun fetchCurrentLocation(): LocationResult {
        if (!hasCoarseLocationPermission()) return LocationResult.PermissionDenied

        val client = clientFactory(context.applicationContext)
        val cancellationSource = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(0L) // Reject cached fixes — we want a fresh read at save time.
            // Hard ceiling on how long Play Services will wait for a fix
            // before giving up. Without this, indoor GPS on some devices
            // (notably Samsung) blocks for ~30s while the fused provider
            // falls back through every sensor. 5s matches the env-fetch
            // SLA so the whole save pipeline never exceeds ~10s.
            .setDurationMillis(CURRENT_LOCATION_TIMEOUT_MILLIS)
            .build()

        return try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { cancellationSource.cancel() }
                val task = client.getCurrentLocation(request, cancellationSource.token)
                task.addOnSuccessListener { location ->
                    if (location == null) {
                        cont.resume(LocationResult.Unavailable)
                    } else {
                        cont.resume(
                            LocationResult.Coordinates(
                                latitude = location.latitude,
                                longitude = location.longitude,
                            ),
                        )
                    }
                }
                task.addOnFailureListener { error ->
                    cont.resume(
                        LocationResult.Failed(error.message ?: "Location request failed"),
                    )
                }
                task.addOnCanceledListener {
                    if (cont.isActive) cont.resume(LocationResult.Unavailable)
                }
            }
        } catch (security: SecurityException) {
            // Permission revoked between check and call — surface as denied, not crash.
            LocationResult.PermissionDenied
        }
    }

    private fun hasCoarseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CURRENT_LOCATION_TIMEOUT_MILLIS: Long = 5_000L
    }
}
