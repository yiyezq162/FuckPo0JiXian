package app.fuckpo0jixian.core

import java.io.Serializable

data class Cidr(val value: String) : Serializable {
    init {
        val parts = value.removeSuffix("/24").split('.')
        require(value.endsWith("/24") && parts.size == 4 && parts.last() == "0" &&
            parts.all { it.toIntOrNull() in 0..255 && it == it.toIntOrNull().toString() }) { "invalid_cidr" }
    }
    val masked: String get() = value.split('.').take(2).joinToString(".") + ".*.0/24"
}
enum class Mode { OBSERVE, AUTO }
enum class RuntimeMode { STANDARD, MODULE }
data class Entry(val cidr: Cidr, val slot: Int? = null) : Serializable
data class Snapshot(val current: Cidr, val entries: List<Entry>, val capacity: Int, val revision: String? = null) : Serializable {
    init {
        require(capacity in 1..10000 && entries.size <= capacity)
        require(entries.map { it.cidr }.distinct().size == entries.size)
    }
    val remaining get() = capacity - entries.size
    fun contains(cidr: Cidr) = entries.any { it.cidr == cidr }
}
/**
 * Po0 publishes no rate limit, so timing follows its official scripts: react to network changes within
 * seconds and fall back every 10 minutes (each device may pick 2–59, see [State.fallbackMinutes]).
 * [minIntervalMs] only stops automatic retry loops; manual checks skip it. Fallback runs compare the exit
 * locally and query Po0 only when something may have changed, or at least every [remoteRefreshMs] to notice
 * edits made by other devices. When the exit cannot be compared locally, Po0 is asked instead, but no more
 * often than every [fallbackMinutes], whatever interval the device picked.
 */
data class Policy(val debounceMs: Long = 3_000, val minIntervalMs: Long = 10_000,
                  val cacheMs: Long = 900_000, val fallbackMinutes: Long = 10,
                  val maxBackoffMs: Long = 3_600_000, val retentionMs: Long = 7 * 86_400_000L,
                  /** How long an exit observation counts as current evidence for writes and authorization. */
                  val freshnessMs: Long = 120_000,
                  val remoteRefreshMs: Long = 3_600_000, val probeIntervalMs: Long = 10_000,
                  /** A co-managed slot another device changed this recently is left alone. */
                  val sharedQuietMs: Long = 30 * 60_000L) : Serializable
data class Observation(val time: Long, val cidr: Cidr, val networkKind: String) : Serializable
data class Event(val time: Long, val code: String) : Serializable
/** The two-slot configuration of versions before 0.5; read only to migrate it into a [SlotLayout]. */
data class SlotPlan(val home: Cidr, val homeSlot: Int, val mobileSlot: Int,
                    val homeReady: Boolean = false, val lastMobile: Cidr? = null,
                    val pendingMobile: Cidr? = null) : Serializable {
    init { require(homeSlot >= 0 && mobileSlot >= 0 && homeSlot != mobileSlot) }
}
data class State(
    val mode: Mode = Mode.OBSERVE, val paused: Boolean = true, val demo: Boolean = false,
    val snapshot: Snapshot? = null,
    val lastCheck: Long = 0, val lastSuccess: Long = 0, val nextAllowed: Long = 0,
    val failures: Int = 0, val authBlocked: Boolean = false, val networkKey: String? = null,
    val status: String = "NOT_CHECKED", val observations: List<Observation> = emptyList(),
    val events: List<Event> = emptyList(),
    val domesticExit: DomesticExit? = null, val nextProbeAllowed: Long = 0, val probeStatus: String = "NOT_CHECKED",
    val slotPlan: SlotPlan? = null,
    val runtimeMode: RuntimeMode = RuntimeMode.STANDARD,
    val layout: SlotLayout? = null,
    val accountContext: String = java.util.UUID.randomUUID().toString(),
    val globalBlock: String? = null,
    /** How this device labels itself in exports and on other devices; display only. */
    val deviceName: String = "",
    /** Minutes between local exit comparisons while the network stays the same; this device only. */
    val fallbackMinutes: Int = FallbackInterval.DEFAULT,
    /** Durable server rate limit, independent of user-facing status or local retry policy. */
    val serverNotBefore: Long = 0,
    /** Po0 server of this account, from the pasted official link. Changing it is changing account. */
    val endpoint: String = Po0Credential.DEFAULT_ENDPOINT
) : Serializable {
    fun serverDeadline(): Long = maxOf(serverNotBefore, if (status == "HTTP_429") nextAllowed else 0)
    fun requestAllowed(time: Long, manual: Boolean) = time >= serverDeadline() && (manual || time >= nextAllowed)
    /** The setting as schedulers use it, always inside the allowed range. */
    val fallbackMs: Long get() = FallbackInterval.clamp(fallbackMinutes) * 60_000L
    /**
     * Applied when state is loaded. Waits saved before Retry-After was capped (or ahead of a clock that was since set
     * back) never hold longer than [Wire.MAX_RETRY_AFTER_MS]. A read error without a pending write used to block
     * globally; it retries on its own now, so such an old block is lifted (a block with a pending write stays).
     */
    fun loaded(now: Long): State {
        val latest = now + Wire.MAX_RETRY_AFTER_MS
        val readBlock = globalBlock in setOf("INVALID_RESPONSE", "SLOT_INVALID") && layout?.pending == null
        return copy(serverNotBefore = minOf(serverNotBefore, latest), nextAllowed = minOf(nextAllowed, latest),
            nextProbeAllowed = minOf(nextProbeAllowed, latest), globalBlock = if (readBlock) null else globalBlock)
    }
}

object FallbackInterval {
    const val MIN = 2
    const val MAX = 59
    const val DEFAULT = 10
    fun clamp(minutes: Int) = minutes.coerceIn(MIN, MAX)
}
