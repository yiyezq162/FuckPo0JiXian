package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

/** Whole desktop check path against a fake Po0 and a fake LAN: identity, probe, write, verify. */
class DesktopControllerTest {
    private val home = DesktopLink("en0", "192.168.5.20", "192.168.5.1", "a4:11:22:33:44:55")
    private val cafe = DesktopLink("en0", "10.0.0.7", "10.0.0.1", "0c:11:22:33:44:66")

    /** Po0 plus ip.3322.net: both see [exit]; POST writes the exit into the slot, like the real API. */
    private class FakeInternet(var exit: String, var entries: MutableMap<Int, String>) : Transport {
        val calls = mutableListOf<String>()
        override suspend fun execute(method: String, url: String): HttpReply {
            calls += "$method ${url.substringAfterLast('/')}"
            val cidr = exit.substringBeforeLast('.') + ".0/24"
            return when {
                url == ProbeSource.IP3322.url -> HttpReply(200, exit)
                method == "POST" -> { entries[url.substringAfter("slot=").toInt()] = cidr; HttpReply(200, json(cidr)) }
                else -> HttpReply(200, json(cidr))
            }
        }
        fun json(current: String) = """{"enabled":true,"limit":5,"currentIp":"$current","whitelist":[${
            entries.entries.joinToString(",") { """{"ip":"${it.value}","slot":${it.key}}""" }}]}"""
    }

    /** What STUN reports; null means STUN is unavailable and the HTTPS probe is used. */
    private var stunExit: (() -> String?)? = null

    private fun setup(internet: FakeInternet, clock: () -> Long = System::currentTimeMillis, link: () -> DesktopLink): DesktopController {
        val dir = Files.createTempDirectory("fpjx")
        val vault = FileVault(dir.resolve("t")).apply { save("pgnfw_CONTROLLER_TEST") }
        val c = DesktopController(FileStore(dir), vault, link, stun = { stunExit?.invoke() }, now = clock) { _, _ -> internet }
        c.store.save(State(mode = Mode.AUTO, paused = false, accountContext = "acct", deviceName = "Mac",
            snapshot = Snapshot(Cidr("198.51.100.0/24"), listOf(Entry(Cidr("198.51.100.0/24"), 0)), 5),
            layout = SlotLayout(slots = listOf(
                ManagedSlot(0, "家", SlotPurpose.FIXED, Writer.LOCAL, listOf("home"), true, authorized = true, baseline = Cidr("198.51.100.0/24")),
                ManagedSlot(1, "外出", SlotPurpose.MOBILE, Writer.LOCAL, automatic = true, allowUnknownWifi = true, authorized = true),
                ManagedSlot(2, "手机", SlotPurpose.MOBILE, Writer.OTHER_DEVICE, owner = "小米 14")),
                identities = listOf(NetworkIdentity("home", "家", "gw:192.168.5.1", setOf(AuthorizedAp("a4:11:22:33:44:55", WifiSecurity.GATEWAY)))))))
        return c
    }

    @Test fun homeRouterUpdatesTheFixedSlotAndCafeUsesTheTravelSlot() = runBlocking {
        val internet = FakeInternet("203.0.113.9", mutableMapOf(0 to "198.51.100.0/24"))
        var link = home
        stunExit = { internet.exit }
        val c = setup(internet) { link }
        assertEquals("SLOT_UPDATED", c.runCheck(manual = true))
        assertEquals("203.0.113.0/24", internet.entries[0])
        assertEquals("203.0.113.0/24", c.store.load().layout!!.slots.first { it.number == 0 }.baseline?.value)

        link = cafe; internet.exit = "192.0.2.44"
        assertEquals("SLOT_UPDATED", c.runCheck(manual = true))
        assertEquals("192.0.2.0/24", internet.entries[1]); assertEquals("203.0.113.0/24", internet.entries[0])

        assertEquals("SLOT_CURRENT", c.runCheck(manual = true))
        assertEquals(2, internet.calls.count { it.startsWith("POST") })
    }

    @Test fun samePoolWithADifferentRouterIsNotHome() = runBlocking {
        val internet = FakeInternet("203.0.113.9", mutableMapOf(0 to "198.51.100.0/24"))
        stunExit = { internet.exit }
        val c = setup(internet) { home.copy(gatewayMac = "a4:11:22:33:44:99") }
        // An unknown router falls to the travel slot, never to the fixed "home" slot.
        assertEquals("SLOT_UPDATED", c.runCheck(manual = true))
        assertEquals("198.51.100.0/24", internet.entries[0]); assertEquals("203.0.113.0/24", internet.entries[1])
    }

    @Test fun probeAndPo0DisagreeMeansNoWrite() = runBlocking {
        val internet = object : Transport {
            val inner = FakeInternet("203.0.113.9", mutableMapOf(0 to "198.51.100.0/24"))
            override suspend fun execute(method: String, url: String) =
                if (url == ProbeSource.IP3322.url) HttpReply(200, "192.0.2.1") else inner.execute(method, url)
        }
        val base = setup(internet.inner) { home }
        val c = DesktopController(base.store, base.vault, { home }, stun = { null }) { _, _ -> internet }
        assertEquals("EGRESS_UNVERIFIED", c.runCheck(manual = true))
        assertTrue(internet.inner.calls.none { it.startsWith("POST") })
    }

    /**
     * A Mac running Clash once got a foreign exit from the HTTPS probe, and Po0 (asked through the tunnel) agreed.
     * Agreement without STUN must never write.
     */
    @Test fun httpsExitAloneNeverWrites() = runBlocking {
        val internet = FakeInternet("203.0.113.9", mutableMapOf(0 to "198.51.100.0/24"))
        stunExit = null
        val c = setup(internet) { home }
        assertEquals("EGRESS_UNVERIFIED", c.runCheck(manual = true))
        assertEquals(ProbeSource.IP3322, c.store.load().domesticExit?.source)
        assertTrue(internet.calls.none { it.startsWith("POST") })
    }

    @Test fun stunIsTheExitWhenItAnswers() = runBlocking {
        val internet = FakeInternet("203.0.113.9", mutableMapOf(0 to "198.51.100.0/24"))
        stunExit = { "203.0.113.9" }
        val c = setup(internet) { home }
        assertEquals("SLOT_UPDATED", c.runCheck(manual = true))
        assertEquals(ProbeSource.STUN, c.store.load().domesticExit?.source)
        assertTrue(internet.calls.none { it.contains("ip.3322.net") })
    }

    /** Po0 reached through a proxy sees the proxy's exit; the direct STUN view differs, so nothing is written. */
    @Test fun proxiedPo0NeverWritesWhenStunDisagrees() = runBlocking {
        val internet = FakeInternet("203.0.113.9", mutableMapOf(0 to "198.51.100.0/24"))
        stunExit = { "192.0.2.1" }
        val c = setup(internet) { home }
        assertEquals("EGRESS_UNVERIFIED", c.runCheck(manual = true))
        assertTrue(internet.calls.none { it.startsWith("POST") })
    }

    @Test fun pausedOfflineAndMissingTokenDoNothing() = runBlocking {
        val internet = FakeInternet("203.0.113.9", mutableMapOf(0 to "198.51.100.0/24"))
        val c = setup(internet) { DesktopLink(null, null, null, null) }
        assertEquals("OFFLINE", c.runCheck(manual = true))
        c.vault.clear(); c.credentialPresent.value = false
        assertEquals("NO_TOKEN", c.runCheck(manual = true))
        assertTrue(internet.calls.isEmpty())
    }

    /** Shortest interval: STUN compares the exit on every 2-minute tick, Po0 is only asked once it moved. */
    @Test fun twoMinuteFallbackComparesEveryTickWithoutAskingPo0() = runBlocking {
        var clock = 1_800_000_000_000L
        val internet = FakeInternet("198.51.100.9", mutableMapOf(0 to "198.51.100.0/24"))
        var stunCalls = 0
        stunExit = { stunCalls++; internet.exit }
        val c = setup(internet, { clock }) { home }
        c.store.save(c.store.load().copy(fallbackMinutes = 2))
        assertEquals("SLOT_CURRENT", c.runCheck(manual = true))
        val po0 = internet.calls.size
        val stun = stunCalls
        repeat(29) {
            clock += c.store.load().fallbackMs
            assertEquals("LOCAL_UNCHANGED", c.runCheck(manual = false, fallback = true))
        }
        assertEquals(po0, internet.calls.size, "no Po0 request while the exit stays put")
        assertEquals(stun + 29, stunCalls, "one STUN comparison per tick")
        clock += c.store.load().fallbackMs; internet.exit = "203.0.113.9"
        assertEquals("SLOT_UPDATED", c.runCheck(manual = false, fallback = true))
        assertEquals("203.0.113.0/24", internet.entries[0])
        assertTrue("\"fallbackMinutes\": 2" in c.debugText())
        assertTrue(c.activity.recent().any { it.contains("CHECK auto-fallback SLOT_UPDATED") })
    }

    @Test fun debugInfoNeverContainsTheRouterButTheShareDoes() {
        val c = setup(FakeInternet("203.0.113.9", mutableMapOf())) { home }
        runBlocking { c.runCheck(manual = true) }
        val debug = c.debugText()
        listOf("a4:11:22:33:44:55", "192.168.5.1", "192.168.5.20", "203.0.113.", "pgnfw_", "acct").forEach { assertFalse(it in debug, "debug leaked $it") }
        assertTrue("\"activity\": [" in debug && "192.168.*.*" in debug)
        val share = c.shareText()
        assertTrue("a4:11:22:33:44:55" in share && "gw:192.168.5.1" in share, "the division carries the bound router")
        assertFalse("pgnfw_" in share)
    }
}
