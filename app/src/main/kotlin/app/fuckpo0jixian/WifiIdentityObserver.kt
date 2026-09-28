package app.fuckpo0jixian

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.*
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import app.fuckpo0jixian.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * No scans and no location coordinates. Only transportInfo for the exact request Network.
 * Watches the physical network (the best non-VPN one), not the app's default: under a VPN the default is the
 * VPN itself, which carries no Wi-Fi identity.
 */
class WifiIdentityObserver(private val context: Context, private val key: (Network) -> String) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    @Volatile var latest: WifiObservation? = null
        private set
    private var callback: ConnectivityManager.NetworkCallback? = null
    fun permitted() = Build.VERSION.SDK_INT >= 31 &&
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        context.getSystemService(LocationManager::class.java).isLocationEnabled
    private fun decode(network: Network, caps: NetworkCapabilities): WifiObservation {
        val info = if (Build.VERSION.SDK_INT >= 31 && permitted()) caps.transportInfo as? WifiInfo else null
        val security = if (Build.VERSION.SDK_INT >= 31) when (info?.currentSecurityType) {
            WifiInfo.SECURITY_TYPE_PSK -> WifiSecurity.WPA2
            WifiInfo.SECURITY_TYPE_SAE -> WifiSecurity.WPA3
            WifiInfo.SECURITY_TYPE_EAP -> WifiSecurity.ENTERPRISE
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> WifiSecurity.ENTERPRISE_WPA3
            WifiInfo.SECURITY_TYPE_OPEN, WifiInfo.SECURITY_TYPE_OWE -> WifiSecurity.OPEN
            else -> WifiSecurity.UNKNOWN
        } else WifiSecurity.UNKNOWN
        return WifiObservation(key(network), info?.ssid?.removeSurrounding("\""), info?.bssid?.lowercase(), security,
            System.currentTimeMillis(), info != null)
    }
    fun start(changed: () -> Unit) {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        latest = null
        if (Build.VERSION.SDK_INT < 31) return
        val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                latest = decode(n, caps); changed()
            }
            override fun onLost(n: Network) { latest = null; changed() }
        }
        callback = cb
        runCatching { registerPhysical(cm, cb) }
    }
    suspend fun observe(network: Network): WifiObservation? {
        if (Build.VERSION.SDK_INT < 31) return null
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { continuation ->
                val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                        if (n != network || !continuation.isActive) return
                        val observation = decode(n, caps)
                        latest = observation
                        runCatching { cm.unregisterNetworkCallback(this) }
                        continuation.resume(observation)
                    }
                }
                continuation.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
                runCatching { registerPhysical(cm, cb) }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }
    fun stillMatches(o: WifiObservation?): Boolean = if (o == null) Build.VERSION.SDK_INT < 31 else
        o.sameIdentity(latest) && (!o.available || permitted()) && System.currentTimeMillis() - o.time in 0..60_000
    companion object {
        /** Wi-Fi or mobile data with internet; NOT_VPN spelled out although the builder already implies it. */
        val PHYSICAL: NetworkRequest = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        /** The network the system would use if no VPN were running. */
        @RequiresApi(31) fun registerPhysical(cm: ConnectivityManager, cb: ConnectivityManager.NetworkCallback) =
            cm.registerBestMatchingNetworkCallback(PHYSICAL, cb, Handler(Looper.getMainLooper()))
    }
}
