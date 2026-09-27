package app.allowmate

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
 * - an allow-while-idle alarm chain for the fallback, which Doze lets through at most every ~9 minutes.
 * Starting the process is enough for network wakes: Controller.start() re-registers the live callback,
 * which reports the current network and schedules the usual debounced check.
 */
object Wake {
    const val ACTION_NETWORK = "app.allowmate.WAKE_NETWORK"
    const val ACTION_FALLBACK = "app.allowmate.WAKE_FALLBACK"
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
    fun enable(context: Context, fallbackMinutes: Long) {
        armNetwork(context)
        scheduleFallback(context, fallbackMinutes)
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
            .onFailure { android.util.Log.w("AllowMateWake", "NETWORK_WAKE_FAILED ${it.javaClass.simpleName}") }
    }


    fun scheduleFallback(context: Context, minutes: Long) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + minutes * 60_000, operation(context, ACTION_FALLBACK))
    }

    fun disable(context: Context) {
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(operation(context, ACTION_NETWORK)) }
        context.getSystemService(AlarmManager::class.java).cancel(operation(context, ACTION_FALLBACK))
    }
}

/** Boot, app update and network wakes only need the process started; the fallback alarm also runs a check. */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = (context.applicationContext as AllowMateApp).controller
        // Network wakes need nothing more: starting the process registers the live callback, which
        // schedules the check, and the check re-arms the wake for the next change once it settles.
        if (intent.action == Wake.ACTION_FALLBACK) controller.fallbackTick()
    }
}
