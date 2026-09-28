package app.fuckpo0jixian

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Optional "常驻运行": a foreground service that does no work of its own. It only keeps the process alive, so the
 * controller's in-process network callbacks keep firing and a network change is checked within seconds instead of
 * waiting for the system (or the vendor's background freezer) to let a wake through. Plain Android, no vendor API:
 * a foreground-service process is the one state every Android version and ROM keeps running and online in Doze.
 * No wake lock, no polling. START_STICKY brings it back after the process is killed; any process start (boot,
 * update, network wake, fallback alarm) re-starts it too, because Controller.start() syncs it.
 */
object KeepAlive {
    private fun prefs(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("keep_alive", false)
    fun set(context: Context, value: Boolean) { prefs(context).edit().putBoolean("keep_alive", value).apply() }

    /** Runs the service while wanted; starting it from the background needs the battery exemption on Android 12+. */
    fun sync(context: Context, scheduled: Boolean) {
        val intent = Intent(context, KeepAliveService::class.java)
        if (enabled(context) && scheduled) runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { android.util.Log.w("FuckPo0JiXianKeepAlive", "START_REFUSED ${it.javaClass.simpleName}") }
        else context.stopService(intent)
    }
}

class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // No intent: the system restarted this sticky service after the process was killed.
        LifeLog.add(if (intent == null) "KEEPALIVE restarted-by-system" else "KEEPALIVE start")
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("keepalive", "常驻运行", NotificationManager.IMPORTANCE_MIN)
            .apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "keepalive").setSmallIcon(R.drawable.ic_stat_fuckpo0jixian)
            .setContentTitle("正在保证您的鸡险有效").setContentIntent(open)
            .setOngoing(true).setShowWhen(false).setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED).build()
        // startForegroundService() must be answered with startForeground() even when about to stop.
        if (Build.VERSION.SDK_INT >= 34) startForeground(4, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(4, notification)
        if (!KeepAlive.enabled(this)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }
}
