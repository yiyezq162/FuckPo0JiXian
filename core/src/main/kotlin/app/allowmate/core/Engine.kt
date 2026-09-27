package app.allowmate.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

interface StateStore { fun load(): State; fun save(state: State) }
class MemoryStore(var state: State = State()) : StateStore {
    override fun load() = state
    override fun save(state: State) { this.state = state }
}
data class NetworkSession(val key: String, val kind: String, val verifiedEgress: Boolean,
                          val observedCidr: Cidr? = null, val wifi: WifiObservation? = null,
                          val manualPermit: ManualPermit? = null, val revalidate: suspend () -> Boolean = { true },
                          val stillCurrent: () -> Boolean)

class Engine(private val store: StateStore, private val now: () -> Long = System::currentTimeMillis,
             val policy: Policy = Policy()) {
    private val mutex = Mutex()
    fun state() = store.load()
    private fun save(s: State, code: String): State {
        val t = now()
        val next = s.copy(status = code, events = (s.events.filter { it.time >= t - policy.retentionMs } + Event(t, code)).takeLast(200),
            observations = s.observations.filter { it.time >= t - policy.retentionMs }.takeLast(500))
        store.save(next)
        return next
    }
    suspend fun check(platform: Platform, network: NetworkSession, manual: Boolean = false, observeOnly: Boolean = false): String {
        if (!mutex.tryLock()) return "BUSY"
        try {
            // Real Po0 and the demo use exactly the same slot authorization path.
            // Generic Profile/Budget support below is retained only for historical non-slot fixtures.
            if (platform is SlotPlatform) return LayoutSync(store, now, policy).check(platform, network, observeOnly, manual)
            var s = store.load()
            val t = now()
            if (s.paused && !observeOnly) return "PAUSED"
            if (s.authBlocked) return "AUTH_PAUSED"
            if (t < s.nextAllowed) return "RATE_LIMITED"
            val pendingAutoSync = s.mode == Mode.AUTO && s.status == "OBSERVED_MISSING"
            if (!manual && !pendingAutoSync && s.failures == 0 && s.networkKey == network.key && s.lastSuccess > 0 && t - s.lastSuccess < policy.cacheMs) return "CACHED_OBSERVATION"
            if (!network.stillCurrent()) return "NETWORK_CHANGED"
            // Durable reservation before I/O: process death cannot bypass the global limiter.
            s = s.copy(lastCheck = t, nextAllowed = t + policy.minIntervalMs, networkKey = network.key)
            store.save(s)
            fun live() { if (!network.stillCurrent()) throw ApiFailure("NETWORK_CHANGED") }
            try {
                val before = platform.query(); live()
                s = s.copy(snapshot = before, observations = s.observations + Observation(t, before.current, network.kind))
                fun success(code: String): String {
                    val refreshed = s.ownership.map { if (it.cidr == before.current) it.copy(lastUsed = t) else it }
                    save(s.copy(lastSuccess = t, failures = 0, ownership = refreshed), code)
                    return code
                }
                val selected = s.profiles.find { it.id == s.activeProfileId }
                val fixed = if (selected != null) listOf(selected).filter { it.kind == Kind.FIXED }
                    else s.profiles.filter { it.kind == Kind.FIXED && it.networkHint == network.key }
                if (fixed.any { it.cidr != null && it.cidr != before.current }) return success("FIXED_DRIFT")
                if (before.contains(before.current)) return success("PRESENT_CURRENT_CHECK")
                if (observeOnly || s.mode == Mode.OBSERVE) return success("OBSERVED_MISSING")
                if (before.remaining == 0) return success("CAPACITY_FULL")
                if (!platform.capabilities.atomicAddWithoutEviction) return success("UNSAFE_SERVER_ADD")
                if (!network.verifiedEgress) return success("EGRESS_UNVERIFIED")
                if (!Allocation.budgetFits(s, before)) return success("BUDGET_CONFLICT")
                val managedMobile = before.entries.count { e -> s.ownership.any { it.cidr == e.cidr && it.authorized } &&
                    s.profiles.none { it.kind == Kind.FIXED && it.cidr == e.cidr } }
                val addingFixed = selected?.kind == Kind.FIXED && selected.cidr == null
                val assignedFixed = s.profiles.filter { it.kind == Kind.FIXED }.mapNotNull { it.cidr }.distinct().size
                if (addingFixed && assignedFixed >= s.budget.fixed) return success("FIXED_BUDGET_FULL")
                if (!addingFixed && s.budget.mobile <= managedMobile) return success("MOBILE_BUDGET_FULL")
                live()
                val fresh = platform.query(); live()
                if (fresh.current != before.current || fresh.entries != before.entries || fresh.capacity != before.capacity || fresh.revision != before.revision)
                    throw ApiFailure("CONCURRENT_CHANGE")
                val post = platform.addIfUnchanged(fresh); live()
                fun maintained(after: Snapshot) = after.current == before.current && after.contains(before.current) &&
                    before.entries.all { it in after.entries }
                if (!maintained(post)) throw ApiFailure("VERIFY_FAILED")
                val after = platform.query(); live()
                if (!maintained(after)) throw ApiFailure("VERIFY_FAILED")
                s = s.copy(snapshot = after, ownership = s.ownership.filterNot { it.cidr == before.current } +
                    Ownership(before.current, authorized = true, protected = addingFixed, lastUsed = t),
                    profiles = s.profiles.map { if (it.id == selected?.id) it.copy(cidr = before.current, networkHint = network.key) else it })
                return success("ADDED_VERIFIED")
            } catch (e: CancellationException) {
                save(s, "CANCELLED_NETWORK_OR_SETTINGS"); throw e
            } catch (e: Exception) {
                val f = e as? ApiFailure
                val n = s.failures + 1
                val backoff = (60_000L * (1L shl (n - 1).coerceIn(0, 6))).coerceAtMost(policy.maxBackoffMs)
                val wait = maxOf(policy.minIntervalMs, backoff, f?.retryAfterMs ?: 0)
                val next = if (Long.MAX_VALUE - t < wait) Long.MAX_VALUE else t + wait
                val code = f?.code ?: "NETWORK_OR_STORAGE_ERROR"
                save(s.copy(failures = n, authBlocked = f?.http in listOf(401, 403), nextAllowed = next,
                    paused = s.paused || code in setOf("HOME_GUARD_FAILED", "SLOT_CONFLICT", "SLOT_INVALID", "SLOT_VERIFY_FAILED")), code)
                return code
            }
        } finally { mutex.unlock() }
    }
}

/** Monotonic times supplied by caller; duplicate callbacks do not reset a stable deadline. */
class Debouncer(private val delayMs: Long = Policy().debounceMs) {
    private var key: String? = null
    private var deadline = Long.MAX_VALUE
    private var emitted = false
    fun changed(next: String?, time: Long): Boolean {
        if (key == next) return false
        key = next; deadline = if (next == null) Long.MAX_VALUE else time + delayMs; emitted = false
        return true
    }
    fun remaining(time: Long): Long = if (key == null) Long.MAX_VALUE else (deadline - time).coerceAtLeast(0)
    fun takeDue(time: Long): String? {
        if (emitted || time < deadline) return null
        emitted = true
        return key
    }
}
