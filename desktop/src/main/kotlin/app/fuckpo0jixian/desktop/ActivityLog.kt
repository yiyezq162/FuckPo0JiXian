package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.LogRetention
import app.fuckpo0jixian.core.Redact
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Activity log behind 导出调试信息, the desktop twin of Android's LifeLog: network changes, sleep and wake, what
 * people changed, every saved state change and each check's result. Addresses are cut to /16 and router MACs
 * dropped as each line is written; no Token. Kept for 7 days ([LogRetention]) in the data folder.
 */
class ActivityLog(dir: Path) {
    private val file = dir.resolve("activity.log")
    private val lock = Any()
    private var nextTrim = 0L

    fun add(text: String, at: Long = System.currentTimeMillis()) {
        val line = "${Instant.ofEpochMilli(at).truncatedTo(ChronoUnit.SECONDS)} ${Redact.text(text.replace('\n', ' ')).take(240)}\n"
        synchronized(lock) {
            runCatching {
                Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                // Trim hourly (and at the first line after launch), not on every append; the size check catches a burst.
                val now = System.currentTimeMillis()
                if (now >= nextTrim || Files.size(file) > LogRetention.MAX_BYTES) {
                    nextTrim = now + LogRetention.TRIM_EVERY_MS
                    val lines = Files.readAllLines(file)
                    val kept = LogRetention.keep(lines, now)
                    if (kept.size != lines.size) writeAtomically(file, kept.joinToString("\n", postfix = "\n").toByteArray())
                }
            }
        }
    }

    /** Most recent lines, oldest first. */
    fun recent(limit: Int = LogRetention.MAX_LINES): List<String> = synchronized(lock) {
        LogRetention.keep(runCatching { Files.readAllLines(file) }.getOrDefault(emptyList()), System.currentTimeMillis()).takeLast(limit)
    }
}
