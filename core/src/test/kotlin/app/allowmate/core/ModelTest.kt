package app.allowmate.core

import kotlin.test.*

class ModelTest {
    private val cidr = Cidr("203.0.113.0/24")
    private val snap = Snapshot(cidr, listOf(Entry(cidr)), 7)
    @Test fun invalidNetworksRejected() {
        listOf("1.2.3.4/24", "1.2.0.0/16", "256.1.1.0/24", "01.2.3.0/24", "::/24").forEach { assertFailsWith<IllegalArgumentException> { Cidr(it) } }
    }
    @Test fun duplicatePlatformEntriesRejected() { assertFailsWith<IllegalArgumentException> { snap.copy(entries = listOf(Entry(cidr), Entry(cidr))) } }
    @Test fun sharedProfileConsumesOneEntry() {
        var s = State(snapshot = snap, profiles = listOf(Profile("a", "一", Kind.FIXED), Profile("b", "二", Kind.MOBILE)))
        s = Allocation.associate(s, "a", cidr, "net", false)
        s = Allocation.associate(s, "b", cidr, "net", false)
        assertEquals(1, s.snapshot!!.entries.size); assertEquals(2, s.profiles.count { it.cidr == cidr })
    }
    @Test fun claimRequiresExplicitConfirmationAndKeepsProtected() {
        val s = State(snapshot = snap)
        assertFails { Allocation.claim(s, cidr, false) }
        assertTrue(Allocation.protected(Allocation.claim(s, cidr, true), cidr))
    }
    @Test fun lowerBudgetDoesNotDeleteAnything() {
        val s = State(snapshot = snap, budget = Budget(2, 3)).copy(budget = Budget())
        assertEquals(snap, s.snapshot)
    }
    @Test fun fixedDriftRequiresConfirmationEvenSame16() {
        val next = Cidr("203.0.114.0/24")
        val s = State(snapshot = Snapshot(next, listOf(Entry(next), Entry(cidr)), 7), profiles = listOf(Profile("a", "固定", Kind.FIXED, cidr)))
        assertFails { Allocation.associate(s, "a", next, "same-ssid", false) }
        assertEquals(next, Allocation.associate(s, "a", next, "same-ssid", true).profiles.single().cidr)
    }
    @Test fun evictionRequiresOwnershipUnprotectedUnreferencedAndLru() {
        val a = Cidr("192.0.2.0/24"); val b = Cidr("198.51.100.0/24")
        val snapshot = Snapshot(cidr, listOf(Entry(cidr), Entry(a), Entry(b)), 8)
        var s = State(snapshot = snapshot, ownership = listOf(Ownership(cidr, true, false, 30), Ownership(a, true, false, 10)))
        assertEquals(listOf(a, cidr), Allocation.eligibleEvictions(s, snapshot))
        s = s.copy(profiles = listOf(Profile("shared", "流动", Kind.MOBILE, a)))
        assertEquals(listOf(cidr), Allocation.eligibleEvictions(s, snapshot))
        s = s.copy(ownership = s.ownership.map { it.copy(protected = true) })
        assertTrue(Allocation.eligibleEvictions(s, snapshot).isEmpty())
    }
    @Test fun capacityIsDynamicAndUnknownEntriesReserved() {
        val s = State(snapshot = snap, budget = Budget(2, 5))
        assertFalse(Allocation.budgetFits(s, snap)); assertTrue(Allocation.budgetFits(s, snap, Budget(2, 4)))
    }
    @Test fun locallyClaimedProtectedUnallocatedStillReservesCapacity() {
        val s = Allocation.claim(State(snapshot = snap, budget = Budget(2, 5)), cidr, true)
        assertEquals(0, Allocation.externalCount(s, snap))
        assertEquals(1, Allocation.reservedCount(s, snap))
        assertFalse(Allocation.budgetFits(s, snap))
    }
    @Test fun debounceCoalescesAndDuplicatesDoNotStarve() {
        val d = Debouncer(15_000); d.changed("a", 0); d.changed("a", 10_000)
        assertNull(d.takeDue(14_999)); assertEquals("a", d.takeDue(15_000)); assertNull(d.takeDue(16_000))
        d.changed("b", 16_000); d.changed("c", 20_000)
        assertNull(d.takeDue(31_000)); assertEquals("c", d.takeDue(35_000))
        d.changed(null, 36_000); assertNull(d.takeDue(100_000))
    }
    @Test fun durableRoundTripPreservesAllSafetyState() {
        val state = State(mode = Mode.AUTO, paused = false, demo = true, budget = Budget(2, 3),
            profiles = listOf(Profile("id", "名字", Kind.FIXED, cidr, "net")), ownership = listOf(Ownership(cidr, true, true, 9)),
            snapshot = snap.copy(revision = "r1"), lastCheck = 10, lastSuccess = 8, nextAllowed = 1000, failures = 3,
            authBlocked = true, networkKey = "net", status = "AUTH_PAUSED", observations = listOf(Observation(2, cidr, "wifi")), events = listOf(Event(3, "FAIL")), activeProfileId = "id")
        assertEquals(state, StateCodec.decode(StateCodec.encode(state)))
        assertFails { StateCodec.decode("{}") }
    }
}
