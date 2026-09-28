package app.fuckpo0jixian

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as FuckPo0JiXianApp).controller.runCheck(false, fallback = inputData.getString(TRIGGER) == FALLBACK)
        return Result.success() // Engine owns backoff; no second retry scheduler.
    }
    /** Expedited work runs as a short foreground service before Android 12. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("runtime", "短时后台检查", NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(3, NotificationCompat.Builder(applicationContext, "runtime").setSmallIcon(R.drawable.ic_stat_fuckpo0jixian)
            .setContentTitle("去他妈的鸡险正在检查").setContentText("完成后自动退出").build())
    }
    companion object { const val TRIGGER = "trigger"; const val FALLBACK = "fallback" }
}
