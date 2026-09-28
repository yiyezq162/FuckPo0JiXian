package app.fuckpo0jixian

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock

/**
 * Wake paths that need no root and survive process death (not force-stop):
 * - a PendingIntent network registration, which the system fires when a new network becomes available;
 * - an allow-while-idle alarm chain for the fallback at the configured interval (2–59 minutes), which Doze
 *   lets through at most every ~9 minutes.
 * Starting the process is enough for network wakes: Controller.start() re-registers the live callback,
 * which reports the current network and schedules the usual debounced check.
 */
object Wake {
    const val ACTION_NETWORK = "app.fuckpo0jixian.WAKE_NETWORK"
    const val ACTION_FALLBACK = "app.fuckpo0jixian.WAKE_FALLBACK"
    private fun operation(context: Context, action: String) = PendingIntent.getBroadcast(context, action.hashCode(),
        Intent(context, WakeReceiver::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /**
     * PendingIntent network requests are one-shot: the system sends the intent once a matching network is
     * available and releases the request a few seconds later. So arm for the *next* change only:
     * on Wi-Fi, mobile data becoming the foreground network; on mobile data, Wi-Fi connecting; offline, any
     * network. It is armed only once the network has settled (after each check, at start, on open and on
     * every fallback tick): mid-switch both networks can exist, which would fire it instantly. A wake that
     * arrives while the process is alive is ignored, so it can never loop. Re-registering the same
     * PendingIntent replaces the previous request; package updates cancel it, hence the repeated arming.
     */
    fun enable(context: Context, fallbackMs: Long) {
        armNetwork(context)
        scheduleFallback(context, fallbackMs)
    }

    private fun armNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).apply {
            when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                    // Cellular that lingers behind Wi-Fi is a background network; it only gains FOREGROUND when it takes over.
                    addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR); addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
                }
                caps != null -> addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }.build()
        runCatching { cm.registerNetworkCallback(request, operation(context, ACTION_NETWORK)) }
            .onFailure { android.util.Log.w("FuckPo0JiXianWake", "NETWORK_WAKE_FAILED ${it.javaClass.simpleName}") }
    }


    fun scheduleFallback(context: Context, delayMs: Long) {
        // Wall-clock target, so the log can show how late the system delivered it.
        context.getSharedPreferences("lifecycle", Context.MODE_PRIVATE).edit()
            .putLong("fallback_due", System.currentTimeMillis() + delayMs).apply()
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs, operation(context, ACTION_FALLBACK))
    }

    fun disable(context: Context) {
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(operation(context, ACTION_NETWORK)) }
        context.getSystemService(AlarmManager::class.java).cancel(operation(context, ACTION_FALLBACK))
    }
}

/** Boot, app update and network wakes only need the process started; the fallback alarm also runs a check. */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = (context.applicationContext as FuckPo0JiXianApp).controller
        LifeLog.add("WAKE " + when (intent.action) {
            Wake.ACTION_FALLBACK -> "fallback late=" + context.getSharedPreferences("lifecycle", Context.MODE_PRIVATE).getLong("fallback_due", 0)
                .takeIf { it > 0 }?.let { "${(System.currentTimeMillis() - it) / 1000}s" }
            Wake.ACTION_NETWORK -> "network"; Intent.ACTION_BOOT_COMPLETED -> "boot"; Intent.ACTION_MY_PACKAGE_REPLACED -> "updated"
            else -> intent.action
        })
        // Network wakes need nothing more: starting the process registers the live callback, which
        // schedules the check, and the check re-arms the wake for the next change once it settles.
        if (intent.action == Wake.ACTION_FALLBACK) controller.fallbackTick()
    }
}
