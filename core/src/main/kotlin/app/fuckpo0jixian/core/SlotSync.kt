package app.fuckpo0jixian.core

data class SlotResult(val snapshot: Snapshot, val plan: SlotPlan, val code: String)

/** Historical v1 protocol fixture ONLY. Engine's SlotPlatform branch uses LayoutSync, never this object. */
object SlotSync {
    suspend fun update(before: Snapshot, plan: SlotPlan, platform: SlotPlatform,
                       observed: Cidr?, live: () -> Boolean,
                       journal: (SlotPlan) -> Unit): SlotResult {
        fun fail(code: String): Nothing = throw ApiFailure(code)
        fun checkLive() { if (!live()) fail("NETWORK_CHANGED") }
        fun validate(s: Snapshot) {
            val slots = s.entries.mapNotNull { it.slot }
            if (slots.distinct().size != slots.size || slots.any { it !in 0 until s.capacity } ||
                plan.homeSlot !in 0 until s.capacity || plan.mobileSlot !in 0 until s.capacity) fail("SLOT_INVALID")
        }
        validate(before)
        val home = before.entries.find { it.cidr == plan.home } ?: fail("HOME_GUARD_FAILED")
        val pinHome = !plan.homeReady && home.slot == null
        if (!pinHome && home.slot != plan.homeSlot) fail("HOME_GUARD_FAILED")
        var p = if (!plan.homeReady && home.slot == plan.homeSlot) plan.copy(homeReady = true) else plan
        if (!pinHome && before.contains(before.current)) return SlotResult(before, p, "PRESENT_CURRENT_CHECK")
        if (observed != before.current) fail("EGRESS_UNVERIFIED")
        val target = if (pinHome) plan.homeSlot else plan.mobileSlot
        val occupant = before.entries.find { it.slot == target }
        if (pinHome) {
            if (before.current != plan.home) fail("HOME_SETUP_REQUIRED")
            if (occupant != null) fail("SLOT_CONFLICT")
        } else {
            if (occupant != null && occupant.cidr != p.lastMobile && occupant.cidr != p.pendingMobile) fail("SLOT_CONFLICT")
            if (occupant == null && before.remaining == 0) fail("CAPACITY_FULL")
        }
        checkLive()
        val fresh = platform.query(); checkLive(); validate(fresh)
        if (fresh != before) fail("CONCURRENT_CHANGE")
        // Persist intent before POST, so a dropped response/process restart cannot orphan our slot.
        if (!pinHome) { p = p.copy(pendingMobile = before.current); journal(p) }
        val post = platform.writeSlot(target); checkLive()
        fun verify(s: Snapshot) {
            validate(s)
            if (s.capacity != before.capacity || s.current != before.current ||
                s.entries.none { it.cidr == before.current && it.slot == target } ||
                s.entries.none { it.cidr == plan.home && it.slot == plan.homeSlot } ||
                before.entries.filterNot { if (pinHome) it.cidr == plan.home else it.slot == plan.mobileSlot }
                    .any { it !in s.entries }) fail("SLOT_VERIFY_FAILED")
        }
        verify(post)
        val after = platform.query(); checkLive(); verify(after)
        p = if (pinHome) p.copy(homeReady = true) else p.copy(lastMobile = before.current, pendingMobile = null)
        return SlotResult(after, p, if (pinHome) "HOME_PINNED_VERIFIED" else "MOBILE_SLOT_VERIFIED")
    }
}
