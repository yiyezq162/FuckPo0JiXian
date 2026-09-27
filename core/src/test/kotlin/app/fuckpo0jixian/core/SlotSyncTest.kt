package app.fuckpo0jixian.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SlotSyncTest {
    private val home = Cidr("198.51.100.0/24")
    private val mobile = Cidr("203.0.113.0/24")
    private val other = Cidr("192.0.2.0/24")
    private val plan = SlotPlan(home, 0, 1, homeReady = true)
    private class Fake(var snap: Snapshot) : SlotPlatform {
        override val capabilities = Capabilities()
        var writes = 0
        var mutate: ((Snapshot) -> Snapshot)? = null
        var dropReply = false
        override suspend fun query() = snap
        override suspend fun addIfUnchanged(expected: Snapshot): Snapshot = error("slotless forbidden")
        override suspend fun writeSlot(slot: Int): Snapshot {
            writes++
            snap = snap.copy(entries = snap.entries.filterNot { it.cidr == snap.current || it.slot == slot } + Entry(snap.current, slot))
            snap = mutate?.invoke(snap) ?: snap
            if (dropReply) throw ApiFailure("NETWORK_TLS_OR_RESPONSE_ERROR")
            return snap
        }
    }
    private fun fake(current: Cidr = mobile, entries: List<Entry> = listOf(Entry(home, 0), Entry(other)), capacity: Int = 5) =
        Fake(Snapshot(current, entries, capacity))
    private suspend fun sync(f: Fake, p: SlotPlan = plan, source: Cidr? = f.snap.current, live: () -> Boolean = { true },
                             journal: (SlotPlan) -> Unit = {}) = SlotSync.update(f.snap, p, f, source, live, journal)

    @Test fun pinHomePreservesEveryOtherEntryEvenAtCapacity() = runTest {
        val f = fake(home, listOf(Entry(home), Entry(other)), 2)
        val r = sync(f, plan.copy(homeReady = false))
        assertEquals("HOME_PINNED_VERIFIED", r.code); assertTrue(r.plan.homeReady)
        assertEquals(setOf(Entry(home, 0), Entry(other)), r.snapshot.entries.toSet())
    }
    @Test fun addMobileAndRotateOnlyItsSlotAtFullCapacity() = runTest {
        val f = fake(capacity = 3)
        val first = sync(f)
        assertEquals("MOBILE_SLOT_VERIFIED", first.code)
        f.snap = f.snap.copy(current = Cidr("203.0.114.0/24"))
        val second = sync(f, first.plan)
        assertEquals(3, second.snapshot.entries.size)
        assertTrue(Entry(home, 0) in second.snapshot.entries && Entry(other) in second.snapshot.entries)
        assertEquals(f.snap.current, second.plan.lastMobile)
    }
    @Test fun homeReturnDoesNotOverwriteMobileSlot() = runTest {
        val f = fake(home, listOf(Entry(home, 0), Entry(mobile, 1)))
        assertEquals("PRESENT_CURRENT_CHECK", sync(f, plan.copy(lastMobile = mobile)).code)
        assertEquals(0, f.writes)
    }
    @Test fun unknownSlotOccupantIsNeverOverwritten() = runTest {
        val f = fake(entries = listOf(Entry(home, 0), Entry(other, 1)))
        assertEquals("SLOT_CONFLICT", assertFailsWith<ApiFailure> { sync(f) }.code); assertEquals(0, f.writes)
    }
    @Test fun homeSlotMissingOrMovedStopsAllMobileWrites() = runTest {
        for (rows in listOf(listOf(Entry(other)), listOf(Entry(home)), listOf(Entry(home, 2)))) {
            val f = fake(entries = rows)
            assertEquals("HOME_GUARD_FAILED", assertFailsWith<ApiFailure> { sync(f) }.code); assertEquals(0, f.writes)
        }
    }
    @Test fun fullWithoutOwnedMobileSlotStops() = runTest {
        val f = fake(capacity = 2)
        assertEquals("CAPACITY_FULL", assertFailsWith<ApiFailure> { sync(f) }.code); assertEquals(0, f.writes)
    }
    @Test fun domesticApiMismatchNeverWrites() = runTest {
        val f = fake()
        assertEquals("EGRESS_UNVERIFIED", assertFailsWith<ApiFailure> { sync(f, source = home) }.code); assertEquals(0, f.writes)
    }
    @Test fun homeSetupRequiresHomeSourceAndEmptySlot() = runTest {
        val f = fake(entries = listOf(Entry(home), Entry(other)))
        assertEquals("HOME_SETUP_REQUIRED", assertFailsWith<ApiFailure> { sync(f, plan.copy(homeReady = false)) }.code)
        f.snap = f.snap.copy(current = home, entries = listOf(Entry(home), Entry(other, 0)))
        assertEquals("SLOT_CONFLICT", assertFailsWith<ApiFailure> { sync(f, plan.copy(homeReady = false)) }.code)
        assertEquals(0, f.writes)
    }
    @Test fun networkChangeBeforeWriteStops() = runTest {
        val f = fake()
        assertEquals("NETWORK_CHANGED", assertFailsWith<ApiFailure> { sync(f, live = { false }) }.code); assertEquals(0, f.writes)
    }
    @Test fun concurrentChangeInPreReadStops() = runTest {
        val f = fake(); val before = f.snap
        f.snap = f.snap.copy(entries = f.snap.entries + Entry(Cidr("192.0.3.0/24")))
        assertEquals("CONCURRENT_CHANGE", assertFailsWith<ApiFailure> {
            SlotSync.update(before, plan, f, mobile, { true }, {})
        }.code); assertEquals(0, f.writes)
    }
    @Test fun unexpectedHomeOrExternalLossFailsVerification() = runTest {
        for (cidr in listOf(home, other)) {
            val f = fake(); f.mutate = { it.copy(entries = it.entries.filterNot { e -> e.cidr == cidr }) }
            assertEquals("SLOT_VERIFY_FAILED", assertFailsWith<ApiFailure> { sync(f) }.code)
        }
    }
    @Test fun droppedReplyJournalSurvivesRestartAndAllowsNextMobileChange() = runTest {
        val f = fake(); f.dropReply = true
        var saved = plan
        assertFailsWith<ApiFailure> { sync(f, journal = { saved = it }) }
        saved = StateCodec.decode(StateCodec.encode(State(slotPlan = saved))).slotPlan!!
        assertEquals(mobile, saved.pendingMobile)
        f.dropReply = false; f.snap = f.snap.copy(current = Cidr("203.0.114.0/24"))
        assertEquals("MOBILE_SLOT_VERIFIED", sync(f, saved).code)
        assertTrue(Entry(home, 0) in f.snap.entries)
    }
    @Test fun engineIsolatesLegacyFixedRecordWithoutGlobalHomeGuard() = runTest {
        val f = fake(entries = listOf(Entry(other)))
        val store = MemoryStore(State(mode = Mode.AUTO, paused = false, slotPlan = plan))
        assertEquals("NO_TARGET", Engine(store, { 1_000 }).check(f, NetworkSession("n", "unknown", false, mobile) { true }))
        assertFalse(store.load().paused); assertEquals(0, f.writes)
        assertEquals("IDENTITY_REQUIRED", store.load().layout!!.slots.first().status)
    }
    @Test fun observeNeverWritesEvenWithConfiguredSlots() = runTest {
        val f = fake()
        val store = MemoryStore(State(mode = Mode.OBSERVE, paused = false, slotPlan = plan))
        assertEquals("OBSERVED_MISSING", Engine(store, { 1_000 }).check(f, NetworkSession("n", "mobile", false, mobile) { true }))
        assertEquals(0, f.writes)
    }
    @Test fun slotAdapterUsesOnlyExplicitSlotPostAndDropsResponseSecrets() = runTest {
        var calls = 0
        val p = Po0Platform({ "pgnfw_TEST_ONLY" }, Transport { method, url ->
            calls++; assertEquals("POST", method)
            assertEquals("https://124.221.69.228/api/firewall/pgnfw_TEST_ONLY/add?slot=1", url)
            HttpReply(200, """{"enabled":true,"limit":5,"currentIp":"203.0.113.0/24","whitelist":[{"ip":"198.51.100.0/24","slot":0},{"ip":"203.0.113.0/24","slot":1}],"token":"SECRET_RESPONSE"}""")
        })
        val snap = p.writeSlot(1)
        assertFalse(StateCodec.encode(State(snapshot = snap)).contains("SECRET_RESPONSE")); assertEquals(1, calls)
    }
}
