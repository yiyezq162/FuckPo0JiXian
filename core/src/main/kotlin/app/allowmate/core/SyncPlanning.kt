package app.allowmate.core

object SyncPlanning {
    fun delayMs(state: State, now: Long, stableRemaining: Long): Long? {
        if (state.paused || state.demo || state.authBlocked || stableRemaining == Long.MAX_VALUE) return null
        return maxOf(stableRemaining, (state.nextAllowed - now).coerceAtLeast(0))
    }
    fun needsFollowUp(code: String, state: State): Boolean = !state.paused && !state.authBlocked &&
        (code in setOf("RATE_LIMITED", "BUSY") || (state.failures > 0 && code == state.status && code !in setOf("NO_TOKEN", "NETWORK_CHANGED", "CANCELLED_NETWORK_OR_SETTINGS")))
}

data class FamiliarNetwork(val cidr: Cidr, val days: Int, val visits: Int, val lastSeen: Long, val common: Boolean)
object NetworkHistory {
    /** Recommendations only: never feeds ownership, whitelist range, deletion, or authorization. */
    fun summarize(observations: List<Observation>, now: Long, retentionMs: Long = Policy().retentionMs): List<FamiliarNetwork> {
        val recent = observations.filter { it.time in (now - retentionMs)..now }.sortedBy { it.time }
        val visits = mutableMapOf<Cidr, Int>()
        val lastVisit = mutableMapOf<Cidr, Long>()
        var previous: Cidr? = null
        recent.forEach { o ->
            val last = lastVisit[o.cidr]
            if (o.cidr != previous && (last == null || o.time - last >= 30 * 60_000L)) {
                visits[o.cidr] = (visits[o.cidr] ?: 0) + 1
                lastVisit[o.cidr] = o.time
            }
            previous = o.cidr
        }
        return recent.groupBy { it.cidr }.map { (cidr, rows) ->
            // UTC days are a deterministic observation heuristic, not a location or identity.
            val days = rows.map { it.time / 86_400_000L }.distinct().size
            val count = visits[cidr] ?: 1
            val span = rows.last().time - rows.first().time
            FamiliarNetwork(cidr, days, count, rows.last().time, (days >= 2 && span >= 12 * 3_600_000L) || count >= 3)
        }.sortedWith(compareByDescending<FamiliarNetwork> { it.common }.thenByDescending { it.days }.thenByDescending { it.visits }.thenByDescending { it.lastSeen })
    }
}
