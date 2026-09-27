package app.allowmate.core

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class Po0ConfigurationTest {
    private val home = Cidr("198.51.100.0/24")
    private val mobile = Cidr("203.0.113.0/24")
    private val other = Cidr("192.0.2.0/24")
    private val plan = SlotPlan(home, 0, 1, true, mobile)
    private val state = State(mode = Mode.AUTO, paused = false, slotPlan = plan,
        snapshot = Snapshot(home, listOf(Entry(home, 0), Entry(mobile, 1), Entry(other, 2)), 5))
    @Test fun existingConfigurationPreservesOwnedMobileAndPendingJournal() {
        val s = state.copy(slotPlan = plan.copy(pendingMobile = Cidr("203.0.114.0/24")))
        assertEquals(s.slotPlan, SlotConfiguration.prepare(s, home, 0, 1))
    }
    @Test fun rejectOverlappingOutOfRangeAndExternalSlots() {
        for ((h, m) in listOf(0 to 0, -1 to 1, 0 to 5, 0 to 2, 2 to 1)) {
            assertFailsWith<IllegalArgumentException> { SlotConfiguration.prepare(state, home, h, m) }
        }
        assertFailsWith<IllegalArgumentException> { SlotConfiguration.prepare(state.copy(slotPlan = null), home, 0, 1) }
    }
    @Test fun newEmptySlotsRequireExistingHomeAndNeverInheritOtherSlotOwnership() {
        val s = state.copy(slotPlan = null, snapshot = Snapshot(home, listOf(Entry(home)), 5))
        assertEquals(SlotPlan(home, 0, 1), SlotConfiguration.prepare(s, home, 0, 1))
        assertFailsWith<IllegalArgumentException> { SlotConfiguration.prepare(s, other, 0, 1) }
        val changed = SlotConfiguration.prepare(state, home, 0, 3)
        assertNull(changed.lastMobile); assertNull(changed.pendingMobile)
    }
    @Test fun fullAccountCannotReserveNewSlot() {
        assertFailsWith<IllegalArgumentException> { SlotConfiguration.prepare(state.copy(snapshot = state.snapshot!!.copy(capacity = 3)), home, 0, 1 + 2) }
        assertFailsWith<IllegalArgumentException> { SlotConfiguration.prepare(state.copy(snapshot = Snapshot(home, listOf(Entry(home), Entry(other)), 2), slotPlan = null), home, 0, 1) }
    }
    @Test fun officialLinkExtractionNeverAcceptsAnotherHostOrCarriesSlotParameter() {
        assertEquals("pgnfw_TEST", Po0Credential.extract("  pgnfw_TEST  "))
        assertEquals("pgnfw_TEST", Po0Credential.extract("https://124.221.69.228/api/firewall/pgnfw_TEST/add?slot=3"))
        for (url in listOf("http://124.221.69.228/api/firewall/pgnfw_TEST/add", "https://example.com/api/firewall/pgnfw_TEST/add", "https://user@124.221.69.228/api/firewall/pgnfw_TEST/add", "pgnfw_TEST@1")) assertNull(Po0Credential.extract(url))
    }
    @Test fun connectionCheckIsReadOnlyWhilePausedInAutomaticMode() = runTest {
        var queries = 0; var writes = 0
        val p = object : SlotPlatform {
            override val capabilities = Capabilities()
            override suspend fun query(): Snapshot { queries++; return state.snapshot!!.copy(current = Cidr("203.0.114.0/24")) }
            override suspend fun writeSlot(slot: Int): Snapshot { writes++; error("must not write") }
            override suspend fun addIfUnchanged(expected: Snapshot): Snapshot { writes++; error("must not write") }
        }
        val store = MemoryStore(state.copy(paused = true))
        val result = Engine(store, { 1_000L }).check(p, NetworkSession("n", "test", true) { true }, manual = true, observeOnly = true)
        assertEquals("OBSERVED_MISSING", result); assertEquals(1, queries); assertEquals(0, writes)
        assertTrue(store.load().paused); assertEquals(Mode.AUTO, store.load().mode); assertEquals(plan, store.load().slotPlan)
    }
    @Test fun readOnlyMissingResultDoesNotSuppressAutomaticSyncAfterQuota() = runTest {
        val next = Cidr("203.0.114.0/24")
        var snapshot = state.snapshot!!.copy(current = next)
        var writes = 0
        var time = 1_000L
        val p = object : SlotPlatform {
            override val capabilities = Capabilities()
            override suspend fun query() = snapshot
            override suspend fun addIfUnchanged(expected: Snapshot): Snapshot = error("slotless forbidden")
            override suspend fun writeSlot(slot: Int): Snapshot {
                writes++
                snapshot = snapshot.copy(entries = snapshot.entries.filterNot { it.slot == slot } + Entry(next, slot))
                return snapshot
            }
        }
        val store = MemoryStore(state)
        val engine = Engine(store, { time })
        val network = NetworkSession("n", "test", false, next) { true }
        assertEquals("OBSERVED_MISSING", engine.check(p, network, manual = true, observeOnly = true))
        assertEquals(0, writes)
        assertEquals("RATE_LIMITED", engine.check(p, network))
        time = store.load().nextAllowed
        assertEquals("MOBILE_SLOT_VERIFIED", engine.check(p, network))
        assertEquals(1, writes); assertTrue(Entry(home, 0) in snapshot.entries)
    }
}
