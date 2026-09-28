package app.fuckpo0jixian.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Configurations saved by versions before 0.5 (a [SlotPlan]) are only ever migrated, never written from. */
class LegacyPlanTest {
    private val home = Cidr("198.51.100.0/24")
    private val mobile = Cidr("203.0.113.0/24")
    private val other = Cidr("192.0.2.0/24")
    private val plan = SlotPlan(home, 0, 1, homeReady = true)
    private class Fake(var snap: Snapshot) : SlotPlatform {
        var writes = 0
        override suspend fun query() = snap
        override suspend fun writeSlot(slot: Int): Snapshot { writes++; error("must not write") }
    }
    private fun fake(entries: List<Entry> = listOf(Entry(home, 0), Entry(other))) = Fake(Snapshot(mobile, entries, 5))
    @Test fun engineIsolatesLegacyFixedRecordWithoutGlobalHomeGuard() = runTest {
        val f = fake(entries = listOf(Entry(other)))
        val store = MemoryStore(State(mode = Mode.AUTO, paused = false, slotPlan = plan))
        assertEquals("NO_TARGET", Engine(store, { 1_000 }).check(f, NetworkSession("n", "unknown", mobile) { true }))
        assertFalse(store.load().paused); assertEquals(0, f.writes)
        assertEquals("IDENTITY_REQUIRED", store.load().layout!!.slots.first().status)
    }
    @Test fun observeNeverWritesEvenWithConfiguredSlots() = runTest {
        val f = fake()
        val store = MemoryStore(State(mode = Mode.OBSERVE, paused = false, slotPlan = plan))
        assertEquals("OBSERVED_MISSING", Engine(store, { 1_000 }).check(f, NetworkSession("n", "mobile", mobile) { true }))
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
