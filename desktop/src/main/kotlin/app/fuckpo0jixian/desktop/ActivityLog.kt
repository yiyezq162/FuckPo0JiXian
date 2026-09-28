package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.Redact
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Activity log behind 导出调试信息, the desktop twin of Android's LifeLog: network changes, sleep and wake, what
 * people changed, every saved state change and each check's result. Addresses are cut to /16 and router MACs
 * dropped as each line is written; no Token. Kept to the latest few thousand lines in the data folder.
 */
class ActivityLog(dir: Path) {
    private val file = dir.resolve("activity.log")
    private val lock = Any()

    fun add(text: String, at: Long = System.currentTimeMillis()) {
        val line = "${Instant.ofEpochMilli(at).truncatedTo(ChronoUnit.SECONDS)} ${Redact.text(text.replace('\n', ' ')).take(240)}\n"
        synchronized(lock) {
            runCatching {
                Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                // Trim in batches so the file is rewritten about once every 500 lines, not on every append.
                if (Files.size(file) > KEEP * 140L) {
                    val lines = Files.readAllLines(file)
                    if (lines.size > KEEP + 500) writeAtomically(file, lines.takeLast(KEEP).joinToString("\n", postfix = "\n").toByteArray())
                }
            }
        }
    }

    /** Most recent lines, oldest first. */
    fun recent(limit: Int = KEEP): List<String> = synchronized(lock) {
        runCatching { Files.readAllLines(file) }.getOrDefault(emptyList()).filter { it.isNotBlank() }.takeLast(limit)
    }

    private companion object { const val KEEP = 3_000 }
}
