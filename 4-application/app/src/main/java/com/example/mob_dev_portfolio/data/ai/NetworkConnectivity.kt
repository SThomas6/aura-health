package com.example.mob_dev_portfolio.data.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Abstraction around "is the device actually on the internet right now?".
 *
 * Extracted behind an interface for one reason: the AC requires that tapping
 * the analysis trigger while offline surfaces a non-blocking error instead
 * of firing the request. We want the ViewModel to short-circuit on that
 * check **without** touching OkHttp, so the offline path can be unit-tested
 * synchronously with a fake that returns `false`.
 */
fun interface NetworkConnectivity {
    fun isOnline(): Boolean
}

/**
 * Real implementation that inspects the system ConnectivityManager.
 *
 * Uses [NetworkCapabilities] (API 23+) — the older `activeNetworkInfo`
 * property is deprecated on API 29+ and removed on some OEM ROMs.
 * Requires `ACCESS_NETWORK_STATE` permission.
 *
 * A null result from the manager (no active network, or the query fails on
 * a dodgy OEM ROM) is treated as offline — being conservative here is
 * preferable to flashing a request that instantly fails with an opaque IO
 * error.
 */
class AndroidNetworkConnectivity(
    private val context: Context,
) : NetworkConnectivity {

    override fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        // NET_CAPABILITY_INTERNET == "this network claims to route to the internet"
        // NET_CAPABILITY_VALIDATED == "Android has confirmed that claim"
        // We require both so a captive-portal Wi-Fi doesn't report as online.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
