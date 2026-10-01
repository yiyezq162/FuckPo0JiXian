package app.fuckpo0jixian.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/**
 * Lifetime counters shown on the overview: how often this app actually changed the whitelist, how many checks it
 * completed and how many network changes it noticed. Kept apart from the 7-day log and never pruned; only removing the
 * app (or its data) resets them. "清空记录" leaves them alone. Counts only, no addresses or times of individual events.
 */
data class Tally(
    /** Writes Po0 confirmed: [SLOT_UPDATED], or an earlier write found applied later ([RECOVERED_VERIFIED]). */
    val ipUpdates: Long = 0,
    /** Checks that finished, including the cheap local comparison; not cancelled or overlapping ones. */
    val checks: Long = 0,
    val networkChanges: Long = 0,
    /** When counting started on this device; 0 until the first count. */
    val since: Long = 0,
    val lastUpdate: Long = 0,
) {
    fun afterCheck(code: String, now: Long): Tally {
        if (code in ignored) return this
        val updated = code in updates
        return copy(checks = checks + 1, ipUpdates = ipUpdates + if (updated) 1 else 0,
            lastUpdate = if (updated) now else lastUpdate, since = since.takeIf { it > 0 } ?: now)
    }
    fun afterNetworkChange(now: Long) = copy(networkChanges = networkChanges + 1, since = since.takeIf { it > 0 } ?: now)

    companion object {
        val updates = setOf("SLOT_UPDATED", "RECOVERED_VERIFIED")
        private val ignored = setOf("BUSY", "CANCELLED_NETWORK_OR_SETTINGS", "NETWORK_CHANGED")
        fun encode(t: Tally) = buildJsonObject {
            put("version", 1); put("ipUpdates", t.ipUpdates); put("checks", t.checks); put("networkChanges", t.networkChanges)
            put("since", t.since); put("lastUpdate", t.lastUpdate)
        }.toString()
        /** Unreadable or missing text starts from zero rather than failing the app. */
        fun decode(text: String?): Tally = runCatching {
            val o = Json.parseToJsonElement(text!!).jsonObject
            fun n(k: String) = o[k]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0) ?: 0
            Tally(n("ipUpdates"), n("checks"), n("networkChanges"), n("since"), n("lastUpdate"))
        }.getOrDefault(Tally())
    }
}

/** Serialized read-modify-write over a tiny file the platform supplies. */
class TallyStore(private val read: () -> String?, private val write: (String) -> Unit) {
    private val state = MutableStateFlow(Tally.decode(runCatching(read).getOrNull()))
    val flow: StateFlow<Tally> get() = state
    @Synchronized fun update(change: (Tally) -> Tally) {
        val next = change(state.value)
        if (next == state.value) return
        state.value = next
        runCatching { write(Tally.encode(next)) }
    }
}

/** "1.2 万" style for large counts, plain digits below ten thousand. */
fun compactCount(n: Long): String = when {
    n < 10_000 -> n.toString()
    n < 100_000_000 -> "%.1f 万".format(n / 10_000.0).replace(".0 万", " 万")
    else -> "%.1f 亿".format(n / 100_000_000.0).replace(".0 亿", " 亿")
}
