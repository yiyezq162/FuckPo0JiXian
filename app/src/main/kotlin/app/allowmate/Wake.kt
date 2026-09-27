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

    fun enable(context: Context, fallbackMinutes: Long) {
        // Re-registering the same PendingIntent replaces the previous request, so this is idempotent.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                operation(context, ACTION_NETWORK))
        }
        scheduleFallback(context, fallbackMinutes)
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
        if (intent.action == Wake.ACTION_FALLBACK) controller.fallbackTick()
    }
}
