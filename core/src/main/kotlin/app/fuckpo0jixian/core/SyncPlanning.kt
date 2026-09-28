package app.fuckpo0jixian.core

object SyncPlanning {
    fun delayMs(state: State, now: Long, stableRemaining: Long): Long? {
        if (state.paused || state.demo || state.authBlocked || state.globalBlock != null || stableRemaining == Long.MAX_VALUE) return null
        return maxOf(stableRemaining, (maxOf(state.nextAllowed, state.serverDeadline()) - now).coerceAtLeast(0))
    }
    fun needsFollowUp(code: String, state: State): Boolean = !state.paused && !state.authBlocked && state.globalBlock == null &&
        (code in setOf("RATE_LIMITED", "BUSY") || (state.failures > 0 && code == state.status && code !in setOf("NO_TOKEN", "NETWORK_CHANGED", "CANCELLED_NETWORK_OR_SETTINGS")))
}

/** A problem worth a system notification after a check. [marker], when set, is remembered so it is sent once per configuration. */
data class Attention(val code: String, val marker: String?)

object Alerts {
    /** Checks stop on these for one slot or one account until someone acts. */
    val issues = setOf("HTTP_401", "HTTP_403", "CAPACITY_FULL", "SLOT_CONFLICT", "SLOT_VERIFY_FAILED", "COVERED_OTHER_SLOT",
        "IDENTITY_AMBIGUOUS", "PENDING_REVIEW")
    /** Read errors retry on their own; they deserve a notification only once they keep coming back. */
    private val lasting = setOf("INVALID_RESPONSE", "SLOT_INVALID", "PLATFORM_DISABLED")
    const val LASTING_FAILURES = 3

    /**
     * What to tell people after a check that started from [before]. Entering a global block always notifies: every
     * automatic check has stopped, and nobody would notice otherwise.
     */
    fun after(before: State, code: String, after: State): Attention? {
        val version = after.layout?.version
        return when {
            before.globalBlock == null && after.globalBlock != null -> Attention(after.globalBlock, null)
            after.layout == null -> null
            code in issues -> Attention(code, "NOTIFIED:$code:$version")
            code in lasting && after.failures >= LASTING_FAILURES -> Attention(code, "NOTIFIED:$code:$version")
            else -> null
        }
    }

    /** Records [alert]'s marker; true when people should be told, i.e. it was not sent for this configuration yet. */
    fun claim(store: StateStore, alert: Attention): Boolean {
        val marker = alert.marker ?: return true
        val latest = store.load()
        val layout = latest.layout ?: return false
        if (marker in layout.notices) return false
        store.save(latest.copy(layout = layout.copy(notices = (layout.notices + marker).toList().takeLast(100).toSet())))
        return true
    }
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

/** Decisions both apps' controllers make around a check, kept in one place so the two cannot drift apart. */
object CheckFlow {
    /** How long a manual update confirmation stays valid. */
    const val PERMIT_MS = 180_000L

    /** Whether this check may write, so it needs a fresh exit observation first. */
    fun mayWrite(s: State, observeOnly: Boolean, manual: Boolean, previewing: Boolean, permit: ManualPermit?, now: Long): Boolean =
        (!observeOnly || previewing) && (s.mode == Mode.AUTO || permit != null || previewing) && s.layout != null &&
            !s.paused && !s.authBlocked && s.globalBlock == null && s.requestAllowed(now, manual)

    /** The exit observed on [networkKey] recently enough to count as evidence for a write, if any. */
    fun freshExit(s: State, networkKey: String, now: Long, policy: Policy = Policy()): Cidr? = s.domesticExit?.takeIf {
        it.networkKey == networkKey && now - it.time in 0 until policy.freshnessMs && s.probeStatus == "PROBE_OBSERVED"
    }?.cidr

    /** A confirmation for a manual update of [slot], or null when the preview check does not allow one. */
    fun permit(code: String, after: State, slot: Int, networkKey: String, wifi: WifiObservation?, observed: Cidr?,
               stillCurrent: Boolean, now: Long): ManualPermit? {
        val target = after.layout?.slots?.find { it.number == slot } ?: return null
        val snapshot = after.snapshot ?: return null
        val allowed = code in setOf("PRESENT_CURRENT_CHECK", "OBSERVED_MISSING") && !after.paused && target.purpose == SlotPurpose.FIXED &&
            target.writer == Writer.LOCAL && target.authorized && observed == snapshot.current && stillCurrent
        return if (allowed) ManualPermit(after.accountContext, after.layout.version, slot, networkKey, wifi, snapshot, now + PERMIT_MS) else null
    }

    /**
     * How long a confirmed manual update waits before it runs. Manual checks skip the loop guard and local backoff,
     * so only a server-imposed wait holds it; null when that outlasts the confirmation, which would only expire.
     */
    fun confirmWait(s: State, permit: ManualPermit, now: Long): Long? =
        (s.serverDeadline() - now).coerceAtLeast(0).takeIf { now + it <= permit.expires }

    /** After a check that reached Po0, observe the exit again when it is stale for this network, or when asked by hand. */
    fun refreshExit(before: State, after: State, networkKey: String, manual: Boolean, now: Long, policy: Policy = Policy()): Boolean =
        !after.paused && after.lastCheck > before.lastCheck && now >= after.nextProbeAllowed &&
            (manual || after.domesticExit?.networkKey != networkKey || now - (after.domesticExit?.time ?: 0) >= policy.cacheMs)

    /** A read-only check found the exit missing while automatic sync is on: follow with a full check. */
    fun syncAfterReading(code: String, after: State, observeOnly: Boolean, previewing: Boolean): Boolean =
        observeOnly && !previewing && after.mode == Mode.AUTO && after.layout != null && !after.paused && code == "OBSERVED_MISSING"
}
