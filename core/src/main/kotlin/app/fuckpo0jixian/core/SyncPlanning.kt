package app.fuckpo0jixian.core

object SyncPlanning {
    fun delayMs(state: State, now: Long, stableRemaining: Long): Long? {
        if (state.paused || state.demo || state.authBlocked || state.globalBlock != null || stableRemaining == Long.MAX_VALUE) return null
        return maxOf(stableRemaining, (state.nextAllowed - now).coerceAtLeast(0))
    }
    fun needsFollowUp(code: String, state: State): Boolean = !state.paused && !state.authBlocked && state.globalBlock == null &&
        (code in setOf("RATE_LIMITED", "BUSY") || (state.failures > 0 && code == state.status && code !in setOf("NO_TOKEN", "NETWORK_CHANGED", "CANCELLED_NETWORK_OR_SETTINGS")))
}

/** Outcomes after which nothing changes until the exit, network or configuration does. */
private val settled = setOf("SLOT_CURRENT", "SLOT_UPDATED", "PRESENT_CURRENT_CHECK", "RECOVERED_VERIFIED", "NO_TARGET",
    "MOBILE_MATCH", "UNKNOWN_WIFI", "COVERED_OTHER_SLOT", "SLOT_NOT_LOCAL", "TEMPORARY_HOLD", "UNMANAGED")

object LocalCheck {
    /**
     * The exit-probe throttle belongs to a network: a successful probe on the previous network must not hold back
     * the first probe on a new one, or the write right after a network change would be refused. Backoff after a
     * failed probe still applies everywhere.
     */
    fun probeAllowed(s: State, networkKey: String, now: Long): Boolean =
        now >= s.nextProbeAllowed || (s.probeStatus == "PROBE_OBSERVED" && s.domesticExit?.networkKey != networkKey)

    /**
     * A fallback tick is the local comparison itself, so it always observes the exit again unless an observation
     * on this network is only seconds old (a check just ran) or the probe is backing off after a failure.
     * Freshness alone is not enough: with a 2-minute interval the previous tick's result would still count as
     * fresh and the exit would never be compared.
     */
    fun fallbackProbe(s: State, networkKey: String, now: Long, policy: Policy = Policy()): Boolean {
        val known = s.domesticExit
        val recent = known != null && known.networkKey == networkKey && s.probeStatus == "PROBE_OBSERVED" &&
            now - known.time in 0 until policy.probeIntervalMs
        return !recent && probeAllowed(s, networkKey, now)
    }

    /**
     * Fallback runs may skip Po0 when the domestic exit just observed on this network matches the exit Po0
     * reported at the last successful check on the same network. Any doubt means a full remote check.
     * When no fresh local observation exists (the probe failed or is backing off), Po0 is asked at the default
     * cadence ([Policy.fallbackMinutes]) instead of on every tick, so a short interval never turns a broken probe
     * into a Po0 request every few minutes. Configuration edits reset lastSuccess, so they always reach Po0.
     */
    fun canSkipRemote(s: State, exit: DomesticExit?, networkKey: String?, now: Long, policy: Policy = Policy()): Boolean {
        val snapshot = s.snapshot ?: return false
        val settledHere = networkKey != null && !s.paused && !s.demo && !s.authBlocked && s.globalBlock == null &&
            s.failures == 0 && s.layout?.pending == null && s.status in settled &&
            s.lastSuccess > 0 && now - s.lastSuccess in 0 until policy.remoteRefreshMs && s.networkKey == networkKey
        if (!settledHere) return false
        val observed = exit != null && exit.networkKey == networkKey && now - exit.time in 0 until policy.freshnessMs
        return if (observed) exit!!.cidr == snapshot.current else now - s.lastSuccess < policy.fallbackMinutes * 60_000
    }
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
