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

    private fun setup(internet: FakeInternet, link: () -> DesktopLink): DesktopController {
        val dir = Files.createTempDirectory("fpjx")
        val vault = FileVault(dir.resolve("t")).apply { save("pgnfw_CONTROLLER_TEST") }
        val c = DesktopController(FileStore(dir), vault, link) { internet }
        c.store.save(State(mode = Mode.AUTO, paused = false, accountContext = "acct", deviceName = "Mac",
            snapshot = Snapshot(Cidr("198.51.100.0/24"), listOf(Entry(Cidr("198.51.100.0/24"), 0)), 5),
            layout = SlotLayout(slots = listOf(
                ManagedSlot(0, "家", SlotPurpose.FIXED, Writer.LOCAL, "home", true, authorized = true, baseline = Cidr("198.51.100.0/24")),
                ManagedSlot(1, "外出", SlotPurpose.MOBILE, Writer.LOCAL, automatic = true, allowUnknownWifi = true, authorized = true),
                ManagedSlot(2, "手机", SlotPurpose.MOBILE, Writer.OTHER_DEVICE, owner = "小米 14")),
                identities = listOf(NetworkIdentity("home", "家", "gw:192.168.5.1", setOf(AuthorizedAp("a4:11:22:33:44:55", WifiSecurity.GATEWAY)))))))
        return c
    }

    @Test fun homeRouterUpdatesTheFixedSlotAndCafeUsesTheTravelSlot() = runBlocking {
        val internet = FakeInternet("203.0.113.9", mutableMapOf(0 to "198.51.100.0/24"))
        var link = home
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
        val c = DesktopController(base.store, base.vault, { home }) { internet }
        assertEquals("EGRESS_UNVERIFIED", c.runCheck(manual = true))
        assertTrue(internet.inner.calls.none { it.startsWith("POST") })
    }

    @Test fun pausedOfflineAndMissingTokenDoNothing() = runBlocking {
        val internet = FakeInternet("203.0.113.9", mutableMapOf(0 to "198.51.100.0/24"))
        val c = setup(internet) { DesktopLink(null, null, null, null) }
        assertEquals("OFFLINE", c.runCheck(manual = true))
        c.vault.clear(); c.credentialPresent.value = false
        assertEquals("NO_TOKEN", c.runCheck(manual = true))
        assertTrue(internet.calls.isEmpty())
    }

    @Test fun exportNeverContainsTheRouter() {
        val c = setup(FakeInternet("203.0.113.9", mutableMapOf())) { home }
        val text = c.exportText()
        assertFalse("a4:11:22:33:44:55" in text); assertFalse("192.168.5.1" in text); assertFalse("pgnfw_" in text)
        assertTrue("已绑定路由器" in text)
    }
}
