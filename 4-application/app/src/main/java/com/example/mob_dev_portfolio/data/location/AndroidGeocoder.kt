package com.example.mob_dev_portfolio.data.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Android-backed [ReverseGeocoder] that supports API 31+ while keeping the
 * API 35 code path free of deprecated calls.
 *
 * The platform split:
 * - API 33+ (Tiramisu and up) uses the async
 *   [Geocoder.getFromLocation] overload that takes a
 *   [Geocoder.GeocodeListener]. This is the non-deprecated path and is the
 *   only branch ever executed on your primary target (API 35).
 * - API 31–32 falls back to the synchronous overload dispatched on
 *   [Dispatchers.IO] so the UI thread is never blocked. The sync call is
 *   marked deprecated starting at API 33, but it is still the **only**
 *   option for pre-Tiramisu devices — so we guard it behind an
 *   `SDK_INT < 33` check and scope [`@Suppress("DEPRECATION")`] to that
 *   branch. On API 35 the suppressed code is unreachable at runtime.
 *
 * If the device has no geocoder service available (emulators without Play
 * Services, stripped-down OEM images), [Geocoder.isPresent] returns false
 * and we fall back to [ReverseGeocoder.UNAVAILABLE].
 *
 * All platform exceptions ([IOException] from the sync overload,
 * [IllegalArgumentException] for out-of-range inputs, platform errors
 * reported via `onError`) are caught and degrade silently to the fallback
 * string — we never crash the save flow on a geocoding failure.
 */
class AndroidGeocoder(
    private val context: Context,
    private val geocoderFactory: (Context) -> Geocoder = { Geocoder(it) },
) : ReverseGeocoder {

    override suspend fun reverseGeocode(latitude: Double, longitude: Double): String {
        if (!Geocoder.isPresent()) return ReverseGeocoder.UNAVAILABLE
        val geocoder = geocoderFactory(context.applicationContext)

        val address: Address? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                awaitFirstAddressAsync(geocoder, latitude, longitude)
            } else {
                fetchFirstAddressLegacy(geocoder, latitude, longitude)
            }
        } catch (illegal: IllegalArgumentException) {
            Log.w(TAG, "Geocoder rejected input (lat=$latitude, lng=$longitude)", illegal)
            return ReverseGeocoder.UNAVAILABLE
        } catch (io: IOException) {
            // Only thrown by the legacy sync overload; the async listener
            // surfaces IO failures through onError instead.
            Log.w(TAG, "Geocoder IO failure (lat=$latitude, lng=$longitude)", io)
            return ReverseGeocoder.UNAVAILABLE
        }
        return ReverseGeocoder.format(address?.toParts())
    }

    private fun Address.toParts(): ReverseGeocoder.AddressParts =
        ReverseGeocoder.AddressParts(
            locality = locality,
            adminArea = adminArea,
            subAdminArea = subAdminArea,
            countryName = countryName,
        )

    /** API 33+ path — non-deprecated listener overload. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun awaitFirstAddressAsync(
        geocoder: Geocoder,
        latitude: Double,
        longitude: Double,
    ): Address? = suspendCancellableCoroutine { cont ->
        val listener = object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) {
                if (cont.isActive) cont.resume(addresses.firstOrNull())
            }

            override fun onError(errorMessage: String?) {
                if (!cont.isActive) return
                Log.w(TAG, "Geocoder onError: ${errorMessage ?: "<no message>"}")
                cont.resume(null)
            }
        }
        geocoder.getFromLocation(latitude, longitude, 1, listener)
        // Platform exposes no cancellation handle for getFromLocation; leaving
        // this here to document that intent and make cancellation observable
        // to the coroutine machinery.
        cont.invokeOnCancellation { /* no-op */ }
    }

    /**
     * API 31–32 path — sync overload moved off the UI thread via
     * [Dispatchers.IO]. The deprecation suppression is scoped to this one
     * call; the annotation is a soft warning, not a runtime restriction,
     * and the API is still supported on every Android version we target.
     */
    @Suppress("DEPRECATION")
    private suspend fun fetchFirstAddressLegacy(
        geocoder: Geocoder,
        latitude: Double,
        longitude: Double,
    ): Address? = withContext(Dispatchers.IO) {
        geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
    }

    private companion object {
        const val TAG = "AndroidGeocoder"
    }
}
