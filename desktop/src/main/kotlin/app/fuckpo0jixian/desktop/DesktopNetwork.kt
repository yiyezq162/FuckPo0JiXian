package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.WifiObservation
import app.fuckpo0jixian.core.WifiSecurity
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

enum class Os { MAC, WINDOWS, OTHER }
val os: Os = System.getProperty("os.name").lowercase().let {
    when { it.startsWith("mac") -> Os.MAC; it.startsWith("windows") -> Os.WINDOWS; else -> Os.OTHER }
}

/**
 * The network a desktop is on, identified by its router: default gateway plus the gateway's MAC address.
 * Reading them needs no permission (unlike Wi-Fi names on macOS 14+ and Windows 11 24H2), and it works the
 * same for Ethernet and Wi-Fi. Like an SSID it resists mistakes, not a deliberately cloned router.
 */
data class DesktopLink(val iface: String?, val localIp: String?, val gatewayIp: String?, val gatewayMac: String?) {
    val key: String get() = listOf(iface, localIp, gatewayIp, gatewayMac).joinToString("|")
    val online get() = localIp != null
    val kind get() = if (gatewayMac != null) "lan" else "other"
    val label get() = gatewayIp?.let { "路由器 $it" } ?: if (online) "未识别的网络" else "未连接"
    fun observation(now: Long): WifiObservation? =
        gatewayMac?.let { WifiObservation(key, "gw:$gatewayIp", it, WifiSecurity.GATEWAY, now) }
}

object DesktopNetwork {
    private val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    /** Interfaces a VPN / TUN creates; the physical network is the one underneath. */
    private val tunnel = Regex("""^(utun|ipsec|ppp|tun|tap|gif|stf)\d*""")

    /** Cheap enough to poll every few seconds; no request leaves the computer. */
    fun read(): DesktopLink {
        val route = runCatching { defaultRoute() }.getOrNull()
        val gateway = route?.gateway
        val mac = gateway?.let { gw -> runCatching { gatewayMac(gw, route.iface) }.getOrNull() }
        return DesktopLink(route?.iface, route?.localIp, gateway, mac)
    }

    data class Route(val gateway: String?, val iface: String?, val localIp: String?)

    private fun defaultRoute(): Route? = when (os) {
        Os.MAC, Os.OTHER -> {
            // A TUN that replaced the default route hides the physical one; the routing table still lists it.
            val primary = parseMacRoute(run("route", "-n", "get", "default"))?.takeIf { (gw, iface) -> gw != null && iface != null && !tunnel.matches(iface) }
            (primary ?: parseNetstatDefault(run("netstat", "-rn", "-f", "inet")).firstOrNull { !tunnel.matches(it.second) })
                ?.let { (gw, iface) -> Route(gw, iface, iface?.let(::interfaceIpv4)) }
        }
        Os.WINDOWS -> parseWindowsRoutes(run("route", "print", "-4", "0.0.0.0")).let { routes ->
            // A VPN/TUN usually takes over with 0.0.0.0/1 + 128.0.0.0/1; the path check catches that too.
            routes.filter { it.mask == "0.0.0.0" && ipv4.matches(it.gateway) }.minByOrNull { it.metric }
                ?.let { Route(it.gateway, NetworkInterface.getByInetAddress(InetAddress.getByName(it.iface))?.name, it.iface) }
        }
    }

    private fun interfaceIpv4(name: String): String? = NetworkInterface.getByName(name)?.inetAddresses?.toList()
        ?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress

    private fun gatewayMac(gateway: String, iface: String?): String? = when (os) {
        Os.WINDOWS -> parseArp(run("arp", "-a", gateway), gateway, null)
        else -> parseArp(run("arp", "-n", gateway), gateway, iface)
    }

    internal fun parseMacRoute(output: String): Pair<String?, String?>? {
        fun field(name: String) = Regex("""^\s*$name:\s*(\S+)""", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)
        val iface = field("interface") ?: return null
        return field("gateway")?.takeIf { ipv4.matches(it) } to iface
    }

    /** "default  192.168.5.1  UGScg  en6" lines of `netstat -rn -f inet`, in priority order. */
    internal fun parseNetstatDefault(output: String): List<Pair<String, String>> = output.lines().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size >= 4 && parts[0] == "default" && ipv4.matches(parts[1])) parts[1] to parts[3] else null
    }

    data class WinRoute(val destination: String, val mask: String, val gateway: String, val iface: String, val metric: Int)
    internal fun parseWindowsRoutes(output: String): List<WinRoute> = output.lines().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size != 5 || !ipv4.matches(parts[0]) || !ipv4.matches(parts[1]) || !ipv4.matches(parts[3])) return@mapNotNull null
        WinRoute(parts[0], parts[1], parts[2], parts[3], parts[4].toIntOrNull() ?: return@mapNotNull null)
    }

    /** macOS prints "? (192.168.1.1) at a4:1:22:.. on en0 ..."; Windows "192.168.1.1  a4-01-22-..  dynamic". */
    internal fun parseArp(output: String, gateway: String, iface: String?): String? = output.lines().firstNotNullOfOrNull { line ->
        if (!Regex("""(^|[\s(])${Regex.escape(gateway)}([\s)]|$)""").containsMatchIn(line)) return@firstNotNullOfOrNull null
        if (iface != null && Regex("""\son\s(\S+)""").find(line)?.groupValues?.get(1)?.let { it != iface } == true) return@firstNotNullOfOrNull null
        Regex("""(?i)\b([0-9a-f]{1,2}([:-])[0-9a-f]{1,2}(\2[0-9a-f]{1,2}){4})\b""").find(line)?.groupValues?.get(1)
            ?.split(':', '-')?.joinToString(":") { it.lowercase().padStart(2, '0') }
            ?.takeUnless { it == "ff:ff:ff:ff:ff:ff" || it == "00:00:00:00:00:00" }
    }

    private fun run(vararg command: String): String {
        // Outputs are a few KB, well under the pipe buffer, so waiting first cannot deadlock.
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); error("timeout") }
        return p.inputStream.bufferedReader().use { it.readText() }
    }
}
