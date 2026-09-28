package app.fuckpo0jixian.core

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class Po0ConfigurationTest {
    private val home = Cidr("198.51.100.0/24")
    private val mobile = Cidr("203.0.113.0/24")
    private val other = Cidr("192.0.2.0/24")
    private val plan = SlotPlan(home, 0, 1, true, mobile)
    private val state = State(mode = Mode.AUTO, paused = false, slotPlan = plan,
        snapshot = Snapshot(home, listOf(Entry(home, 0), Entry(mobile, 1), Entry(other, 2)), 5))
    @Test fun officialLinkCarriesItsServerButNeverASlotParameterOrCredentials() {
        assertEquals(Po0Link("pgnfw_TEST", null), Po0Credential.parse("  pgnfw_TEST  "))
        assertEquals(Po0Link("pgnfw_TEST", "https://124.221.69.228"), Po0Credential.parse("https://124.221.69.228/api/firewall/pgnfw_TEST/add?slot=3"))
        assertEquals(Po0Link("pgnfw_TEST", "https://po0.example.com:8443"), Po0Credential.parse("https://PO0.example.com:8443/api/firewall/pgnfw_TEST"))
        assertEquals("https://example.com", Po0Credential.parse("https://example.com:443/api/firewall/pgnfw_TEST/")?.endpoint)
        for (url in listOf("http://124.221.69.228/api/firewall/pgnfw_TEST/add", "https://user@124.221.69.228/api/firewall/pgnfw_TEST/add",
                "https://124.221.69.228/api/firewall/pgnfw_TEST#x", "https://124.221.69.228/other/pgnfw_TEST", "pgnfw_TEST@1"))
            assertNull(Po0Credential.parse(url), url)
    }
    @Test fun requestsGoToTheLinkedServer() = kotlinx.coroutines.test.runTest {
        var seen = ""
        Po0Platform({ "pgnfw_TEST" }, Transport { _, url -> seen = url; HttpReply(500, "") }, endpoint = "https://po0.example.com:8443")
            .runCatching { query() }
        assertEquals("https://po0.example.com:8443/api/firewall/pgnfw_TEST", seen)
        assertFailsWith<IllegalArgumentException> { Po0Platform({ null }, Transport { _, _ -> error("") }, endpoint = "http://x") }
    }
    @Test fun connectionCheckIsReadOnlyWhilePausedInAutomaticMode() = runTest {
        var queries = 0; var writes = 0
        val p = object : SlotPlatform {
            override suspend fun query(): Snapshot { queries++; return state.snapshot!!.copy(current = Cidr("203.0.114.0/24")) }
            override suspend fun writeSlot(slot: Int): Snapshot { writes++; error("must not write") }
        }
        val store = MemoryStore(state.copy(paused = true))
        val result = Engine(store, { 1_000L }).check(p, NetworkSession("n", "test") { true }, manual = true, observeOnly = true)
        assertEquals("OBSERVED_MISSING", result); assertEquals(1, queries); assertEquals(0, writes)
        assertTrue(store.load().paused); assertEquals(Mode.AUTO, store.load().mode); assertEquals(plan, store.load().slotPlan)
    }
    @Test fun readOnlyMissingResultDoesNotSuppressAutomaticSyncAfterQuota() = runTest {
        val next = Cidr("203.0.114.0/24")
        var snapshot = state.snapshot!!.copy(current = next)
        var writes = 0
        var time = 1_000L
        val p = object : SlotPlatform {
            override suspend fun query() = snapshot
            override suspend fun writeSlot(slot: Int): Snapshot {
                writes++
                snapshot = snapshot.copy(entries = snapshot.entries.filterNot { it.slot == slot } + Entry(next, slot))
                return snapshot
            }
        }
        val store = MemoryStore(state)
        val engine = Engine(store, { time })
        val network = NetworkSession("n", "cellular", next) { true }
        assertEquals("OBSERVED_MISSING", engine.check(p, network, manual = true, observeOnly = true))
        assertEquals(0, writes)
        assertEquals("RATE_LIMITED", engine.check(p, network))
        time = store.load().nextAllowed
        assertEquals("SLOT_UPDATED", engine.check(p, network))
        assertEquals(1, writes); assertTrue(Entry(home, 0) in snapshot.entries)
    }
}
