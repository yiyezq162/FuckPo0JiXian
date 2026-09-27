package app.fuckpo0jixian

import android.app.*
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.fuckpo0jixian.core.RuntimePolicy
import kotlinx.coroutines.*

/** Fixed, non-exported entry; 90s active-time timeout, idle exit, no wake lock. */
class RuntimeSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active: Job? = null
    private var idleBlocked = false
    private val idleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (getSystemService(PowerManager::class.java).isDeviceIdleMode) {
                idleBlocked = true
                android.util.Log.i("FuckPo0JiXianRuntime", "DEGRADED_DOZE_STOP uid=${android.os.Process.myUid()}")
                active?.cancel()
                // Do not wait for a suspended coroutine or disk write to remove FGS.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, idleReceiver, IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("runtime", "短时后台检查", NotificationManager.IMPORTANCE_LOW))
        startForeground(2, NotificationCompat.Builder(this, "runtime").setSmallIcon(R.drawable.ic_stat_fuckpo0jixian)
            .setContentTitle("去他妈的鸡险正在检查").setContentText("完成后自动退出").build())
        if (active?.isActive == true) return START_NOT_STICKY
        val controller = (application as FuckPo0JiXianApp).controller
        active = scope.launch {
            val begin = SystemClock.elapsedRealtime()
            try {
                withTimeout(90_000) {
                    val ticket = intent?.getStringExtra("ticket") ?: return@withTimeout
                    if (intent.action != "app.fuckpo0jixian.RUNTIME_CHECK" || ticket.length != 36 ||
                        !RuntimePolicy.enabled(controller.store.load())) return@withTimeout
                    val epoch = controller.runtime.claim(ticket) ?: return@withTimeout
                    if (getSystemService(PowerManager::class.java).isDeviceIdleMode) {
                        idleBlocked = true
                        android.util.Log.i("FuckPo0JiXianRuntime", "DEGRADED_DOZE_ENTRY uid=${android.os.Process.myUid()}")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        withContext(NonCancellable) { controller.runtime.record("DEGRADED_DOZE", SystemClock.elapsedRealtime() - begin, false) }
                        return@withTimeout
                    }
                    val readOnly = !controller.runtime.validated(epoch)
                    val before = controller.store.load().lastSuccess
                    val requests = NetworkTransport.requests.get()
                    // The module's periodic wake asks for the cheap local comparison; network events do a full check.
                    val fallback = intent.getStringExtra("trigger") == "fallback"
                    val code = controller.runCheck(false, observeOnly = readOnly, enhanced = true, fallback = fallback)
                    val complete = controller.store.load().lastSuccess > before && NetworkTransport.requests.get() > requests &&
                        code in setOf("PRESENT_CURRENT_CHECK", "OBSERVED_MISSING", "FIXED_DRIFT", "MOBILE_SLOT_VERIFIED", "HOME_PINNED_VERIFIED")
                    if (readOnly && complete) controller.runtime.validate(epoch)
                    controller.runtime.record("$code · HTTP尝试 ${NetworkTransport.requests.get() - requests}", SystemClock.elapsedRealtime() - begin,
                        complete, readOnly)
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable) { runCatching { controller.runtime.record(if (idleBlocked) "DEGRADED_DOZE" else "CANCELLED_OR_TIMEOUT", SystemClock.elapsedRealtime() - begin, false) } }
            } catch (_: Exception) {
                controller.runtime.status.value = "增强执行失败 · 已降级为标准调度"
            } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { active?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { unregisterReceiver(idleReceiver); scope.cancel(); super.onDestroy() }
}
