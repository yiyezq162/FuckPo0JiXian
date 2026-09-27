package app.fuckpo0jixian.core

/** Historical v1 fixture only. Product slot editing uses LayoutRules.saveSlot. */
object SlotConfiguration {
    fun prepare(s: State, home: Cidr, homeSlot: Int, mobileSlot: Int): SlotPlan {
        fun reject(code: String): Nothing = throw IllegalArgumentException(code)
        if (s.demo) reject("CONFIG_REAL_ONLY")
        val snap = s.snapshot ?: reject("CONFIG_QUERY_FIRST")
        if (homeSlot == mobileSlot || homeSlot !in 0 until snap.capacity || mobileSlot !in 0 until snap.capacity)
            reject("CONFIG_SLOT_RANGE")
        val entry = snap.entries.find { it.cidr == home } ?: reject("CONFIG_HOME_NOT_LISTED")
        if (entry.slot != null && entry.slot != homeSlot) reject("CONFIG_HOME_ALREADY_PINNED")
        if (snap.entries.any { it.slot == homeSlot && it.cidr != home }) reject("CONFIG_HOME_SLOT_BUSY")
        val old = s.slotPlan
        val retainMobile = old?.mobileSlot == mobileSlot
        val occupant = snap.entries.find { it.slot == mobileSlot }
        if (occupant != null && (!retainMobile || (occupant.cidr != old?.lastMobile && occupant.cidr != old?.pendingMobile)))
            reject("CONFIG_MOBILE_SLOT_BUSY")
        if (occupant == null && snap.remaining == 0) reject("CONFIG_CAPACITY_FULL")
        return SlotPlan(home, homeSlot, mobileSlot, entry.slot == homeSlot,
            if (retainMobile) old?.lastMobile else null, if (retainMobile) old?.pendingMobile else null)
    }
}
