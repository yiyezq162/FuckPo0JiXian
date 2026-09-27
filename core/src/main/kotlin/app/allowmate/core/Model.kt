package app.allowmate.core

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
enum class Kind { FIXED, MOBILE }
data class Entry(val cidr: Cidr, val slot: Int? = null) : Serializable
data class Snapshot(val current: Cidr, val entries: List<Entry>, val capacity: Int, val revision: String? = null) : Serializable {
    init {
        require(capacity in 1..10000 && entries.size <= capacity)
        require(entries.map { it.cidr }.distinct().size == entries.size)
    }
    val remaining get() = capacity - entries.size
    fun contains(cidr: Cidr) = entries.any { it.cidr == cidr }
}
data class Profile(val id: String, val name: String, val kind: Kind, val cidr: Cidr? = null,
                   val networkHint: String? = null) : Serializable
data class Ownership(val cidr: Cidr, val authorized: Boolean = false, val protected: Boolean = true,
                     val lastUsed: Long = 0) : Serializable
data class Budget(val fixed: Int = 0, val mobile: Int = 0) : Serializable {
    init { require(fixed in 0..10000 && mobile in 0..10000 && fixed + mobile <= 10000) }
}
data class Policy(val debounceMs: Long = 15_000, val minIntervalMs: Long = 120_000,
                  val cacheMs: Long = 900_000, val fallbackMinutes: Long = 30,
                  val maxBackoffMs: Long = 3_600_000, val retentionMs: Long = 7 * 86_400_000L) : Serializable
data class Observation(val time: Long, val cidr: Cidr, val networkKind: String) : Serializable
data class Event(val time: Long, val code: String) : Serializable
/** Explicit user-authorized server slots; never inferred from list positions. */
data class SlotPlan(val home: Cidr, val homeSlot: Int, val mobileSlot: Int,
                    val homeReady: Boolean = false, val lastMobile: Cidr? = null,
                    val pendingMobile: Cidr? = null) : Serializable {
    init { require(homeSlot >= 0 && mobileSlot >= 0 && homeSlot != mobileSlot) }
}
data class State(
    val mode: Mode = Mode.OBSERVE, val paused: Boolean = true, val demo: Boolean = false,
    val budget: Budget = Budget(), val profiles: List<Profile> = emptyList(),
    val ownership: List<Ownership> = emptyList(), val snapshot: Snapshot? = null,
    val lastCheck: Long = 0, val lastSuccess: Long = 0, val nextAllowed: Long = 0,
    val failures: Int = 0, val authBlocked: Boolean = false, val networkKey: String? = null,
    val status: String = "NOT_CHECKED", val observations: List<Observation> = emptyList(),
    val events: List<Event> = emptyList(), val activeProfileId: String? = null,
    val domesticExit: DomesticExit? = null, val nextProbeAllowed: Long = 0, val probeStatus: String = "NOT_CHECKED",
    val slotPlan: SlotPlan? = null,
    val runtimeMode: RuntimeMode = RuntimeMode.STANDARD
) : Serializable

object Allocation {
    fun protected(s: State, cidr: Cidr): Boolean {
        val o = s.ownership.find { it.cidr == cidr }
        return o == null || !o.authorized || o.protected || s.profiles.any { it.kind == Kind.FIXED && it.cidr == cidr }
    }
    fun externalCount(s: State, snapshot: Snapshot): Int = snapshot.entries.count { e ->
        s.ownership.none { it.cidr == e.cidr && it.authorized }
    }
    fun reservedCount(s: State, snapshot: Snapshot): Int = snapshot.entries.count { e ->
        s.ownership.none { it.cidr == e.cidr && it.authorized } ||
            (protected(s, e.cidr) && s.profiles.none { it.cidr == e.cidr })
    }
    fun budgetFits(s: State, snap: Snapshot, budget: Budget = s.budget): Boolean =
        reservedCount(s, snap) + budget.fixed + budget.mobile <= snap.capacity
    fun eligibleEvictions(s: State, snap: Snapshot): List<Cidr> = snap.entries.map { it.cidr }
        .filter { !protected(s, it) && s.profiles.none { p -> p.cidr == it } }
        .sortedBy { cidr -> s.ownership.find { it.cidr == cidr }?.lastUsed ?: 0 }
    fun associate(s: State, profileId: String, cidr: Cidr, hint: String?, confirmed: Boolean): State {
        require(s.snapshot?.contains(cidr) == true) { "entry_not_observed" }
        val p = s.profiles.first { it.id == profileId }
        require(p.kind != Kind.FIXED || p.cidr == null || p.cidr == cidr || confirmed) { "fixed_drift_confirmation" }
        return s.copy(profiles = s.profiles.map { if (it.id == profileId) it.copy(cidr = cidr, networkHint = hint) else it })
    }
    fun claim(s: State, cidr: Cidr, confirmed: Boolean): State {
        require(confirmed && s.snapshot?.contains(cidr) == true)
        // Claim is local authorization only; it never asserts exclusive server ownership.
        return s.copy(ownership = s.ownership.filterNot { it.cidr == cidr } + Ownership(cidr, true, true))
    }
}
