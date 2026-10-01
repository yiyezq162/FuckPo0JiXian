package app.fuckpo0jixian

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import app.fuckpo0jixian.core.LogRetention
import app.fuckpo0jixian.core.Redact
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Activity log behind 导出调试信息: when the process started and what woke it, why the previous process ended
 * (Android 11+ keeps the system's own exit records), fallback alarm lateness, Doze transitions, network changes,
 * what people changed, every saved state change, each check's result and the module's own timeline. Addresses are
 * cut to /16 and access point MACs dropped as each line is written ([Redact]); no Token, no Wi-Fi names. Each line
 * starts with an ISO UTC time, so sorting orders them even though an exit is only learned when the next process starts.
 * Kept for 7 days ([LogRetention]).
 */
object LifeLog {
    private val lock = Any()
    private var nextTrim = 0L
    private lateinit var file: File
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(appContext.noBackupFilesDir, "lifecycle.log")
    }

    fun add(text: String, at: Long = System.currentTimeMillis()) {
        if (!::file.isInitialized) return
        val line = "${Instant.ofEpochMilli(at).truncatedTo(ChronoUnit.SECONDS)} ${Redact.text(text.replace('\n', ' ')).take(240)}"
        synchronized(lock) {
            runCatching {
                file.appendText(line + "\n")
                // Trim hourly (and at the first line of each process), not on every append; the size check catches a burst.
                val now = System.currentTimeMillis()
                if (now >= nextTrim || file.length() > LogRetention.MAX_BYTES) {
                    nextTrim = now + LogRetention.TRIM_EVERY_MS
                    val lines = file.readLines()
                    val kept = LogRetention.keep(lines, now)
                    if (kept.size != lines.size) file.writeText(kept.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    /** Most recent lines, oldest first. */
    fun recent(limit: Int = LogRetention.MAX_LINES): List<String> = synchronized(lock) {
        if (!::file.isInitialized) return emptyList()
        // By time only, and stably: lines of the same second keep the order they were written in.
        LogRetention.keep(runCatching { file.readLines() }.getOrDefault(emptyList()), System.currentTimeMillis())
            .sortedBy { it.substringBefore(' ') }.takeLast(limit)
    }

    /** Process start: first the system's records of how earlier processes ended, then this start's conditions. */
    fun processStart() {
        recordExits()
        val power = appContext.getSystemService(PowerManager::class.java)
        val am = appContext.getSystemService(ActivityManager::class.java)
        val bucket = when (appContext.getSystemService(UsageStatsManager::class.java).appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"; UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"; UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            45 -> "restricted"; 5 -> "exempt"; else -> "other"
        }
        add("START pid=${android.os.Process.myPid()} bucket=$bucket batteryExempt=${power.isIgnoringBatteryOptimizations(appContext.packageName)} " +
            "bgRestricted=${am.isBackgroundRestricted} idle=${power.isDeviceIdleMode} keepAlive=${KeepAlive.enabled(appContext)}")
    }

    private fun recordExits() {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = appContext.getSharedPreferences("lifecycle", Context.MODE_PRIVATE)
        val seen = prefs.getLong("last_exit", 0)
        val exits = runCatching { appContext.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(appContext.packageName, 0, 32) }
            .getOrDefault(emptyList()).filter { it.timestamp > seen }.sortedBy { it.timestamp }
        exits.forEach { e ->
            val detail = e.description?.takeIf { it.isNotBlank() }?.let { " desc=${it.take(80)}" } ?: ""
            add("EXIT ${reason(e.reason)} status=${e.status} importance=${importance(e.importance)} pid=${e.pid}$detail", e.timestamp)
        }
        exits.lastOrNull()?.let { prefs.edit().putLong("last_exit", it.timestamp).apply() }
    }

    private fun reason(code: Int) = when (code) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"; ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"; ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"; ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"; ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"; ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"; ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"; 14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"; 16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($code)"
    }

    private fun importance(value: Int) = when {
        value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "fgs"
        value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        value <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        else -> "gone"
    } + "($value)"

    /** People-facing summary of the latest exit the system recorded, for Settings. */
    fun lastExit(): Pair<Long, String>? {
        // Only the system's exit records; "STATE EXIT <address> via STUN" lines are exit probes, not process deaths.
        val line = recent().lastOrNull { it.substringAfter(' ').startsWith("EXIT ") } ?: return null
        val at = runCatching { Instant.parse(line.substringBefore(' ')).toEpochMilli() }.getOrNull() ?: return null
        val code = line.substringAfter(" EXIT ").substringBefore(' ')
        val text = when (code) {
            "LOW_MEMORY" -> "内存不足"; "USER_REQUESTED", "USER_STOPPED" -> "被强行停止"; "SIGNALED", "OTHER" -> "被系统结束"
            "FREEZER" -> "冻结时被结束"; "EXCESSIVE_RESOURCE" -> "占用过多被结束"; "PACKAGE_UPDATED", "PACKAGE_STATE_CHANGE" -> "应用更新"
            "EXIT_SELF" -> "自行退出"; "CRASH", "CRASH_NATIVE", "ANR", "INIT_FAILURE" -> "异常退出"; "PERMISSION_CHANGE" -> "权限变更"
            else -> "原因未知"
        }
        return at to text
    }
}
