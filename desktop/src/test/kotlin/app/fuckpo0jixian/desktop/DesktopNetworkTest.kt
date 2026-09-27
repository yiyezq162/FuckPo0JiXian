package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.WifiSecurity
import kotlin.test.*

class DesktopNetworkTest {
    @Test fun macRouteGivesGatewayAndInterface() {
        val out = """
               route to: default
            destination: default
                   mask: default
                gateway: 192.168.5.1
              interface: en6
                  flags: <UP,GATEWAY,DONE,STATIC,PRCLONING,GLOBAL>
        """.trimIndent()
        assertEquals("192.168.5.1" to "en6", DesktopNetwork.parseMacRoute(out))
        // A point-to-point VPN default route has no gateway address: no router identity.
        assertEquals(null to "utun4", DesktopNetwork.parseMacRoute("destination: default\n  interface: utun4\n"))
        assertNull(DesktopNetwork.parseMacRoute("route: writing to routing socket: not in table"))
    }

    @Test fun arpPicksTheGatewayOnTheRightInterfaceAndPadsOctets() {
        val mac = """
            ? (192.168.5.1) at a4:1:22:b:44:5 on en6 ifscope [ethernet]
            ? (192.168.5.1) at a4:1:22:b:44:99 on en1 ifscope [ethernet]
            ? (192.168.5.10) at 11:22:33:44:55:66 on en6 ifscope [ethernet]
        """.trimIndent()
        assertEquals("a4:01:22:0b:44:05", DesktopNetwork.parseArp(mac, "192.168.5.1", "en6"))
        assertEquals("a4:01:22:0b:44:99", DesktopNetwork.parseArp(mac, "192.168.5.1", "en1"))
        assertNull(DesktopNetwork.parseArp("? (192.168.5.1) at (incomplete) on en6 ifscope [ethernet]", "192.168.5.1", "en6"))
        val windows = """
            Interface: 192.168.1.100 --- 0x7
              Internet Address      Physical Address      Type
              192.168.1.1           A4-11-22-33-44-55     dynamic
              192.168.1.10          11-22-33-44-55-66     dynamic
        """.trimIndent()
        assertEquals("a4:11:22:33:44:55", DesktopNetwork.parseArp(windows, "192.168.1.1", null))
        assertNull(DesktopNetwork.parseArp(windows, "192.168.1.2", null))
    }

    @Test fun windowsRoutesIgnorePersistentAndLocalizedLines() {
        val out = """
            IPv4 路由表
            ===========================================================================
            活动路由:
            网络目标        网络掩码          网关       接口   跃点数
                      0.0.0.0          0.0.0.0      192.168.1.1    192.168.1.100     25
                      0.0.0.0        128.0.0.0         在链路上       198.18.0.1      0
            ===========================================================================
            永久路由:
              网络地址          网络掩码  网关地址  跃点数
                      0.0.0.0          0.0.0.0      192.168.1.1  默认
        """.trimIndent()
        val routes = DesktopNetwork.parseWindowsRoutes(out)
        assertEquals(2, routes.size)
        assertEquals(DesktopNetwork.WinRoute("0.0.0.0", "0.0.0.0", "192.168.1.1", "192.168.1.100", 25), routes[0])
        assertEquals("128.0.0.0", routes[1].mask)
    }

    @Test fun routerObservationIsUsableOnlyWithAGatewayMac() {
        val link = DesktopLink("en6", "192.168.5.20", "192.168.5.1", "a4:01:22:0b:44:05")
        val o = link.observation(1_000)!!
        assertEquals(WifiSecurity.GATEWAY, o.security)
        assertTrue(o.usable(1_000, link.key))
        assertNull(link.copy(gatewayMac = null).observation(1_000))
        assertEquals("other", link.copy(gatewayMac = null).kind)
    }

    @Test fun windowsSkipsClashTunDefaultRoute() {
        val routes = listOf(
            DesktopNetwork.WinRoute("0.0.0.0", "0.0.0.0", "198.18.0.2", "198.18.0.1", 0),
            DesktopNetwork.WinRoute("0.0.0.0", "0.0.0.0", "10.8.0.1", "10.8.0.6", 5),
            DesktopNetwork.WinRoute("0.0.0.0", "0.0.0.0", "192.168.1.1", "192.168.1.23", 25))
        val names = mapOf("10.8.0.6" to "wg0 WireGuard Tunnel", "192.168.1.23" to "Ethernet Realtek PCIe GbE")
        assertEquals("192.168.1.1", DesktopNetwork.pickWindowsDefault(routes, names::get) { it == "192.168.1.1" }?.gateway)
        // No ARP answer anywhere (e.g. arp failed): still never the TUN.
        assertEquals("192.168.1.1", DesktopNetwork.pickWindowsDefault(routes, names::get) { false }?.gateway)
        assertNull(DesktopNetwork.pickWindowsDefault(routes.take(1), names::get) { true })
    }

    /** Reads this machine's real routing table; skipped where there is no network. */
    @Test fun liveReadIsConsistent() {
        val link = DesktopNetwork.read()
        if (!link.online) return
        assertNotNull(link.iface)
        if (link.gatewayMac != null) assertTrue(link.gatewayMac!!.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")))
    }
}
