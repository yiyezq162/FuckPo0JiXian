package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.StunCodec
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.security.SecureRandom

/**
 * The public IPv4 as seen from the LAN interface, via a STUN binding request (RFC 5389) on a pinned UDP socket.
 * With a Windows TUN running, pinned UDP still leaves directly while pinned TCP stalls, so this is the exit
 * observation that cannot have gone through a proxy. Domestic servers only; names resolved the same pinned way.
 */
internal object Stun {
    private val random = SecureRandom()

    /** The server that answered last, kept for when every resolver hands out a proxy's fake addresses. */
    @Volatile private var lastGood: InetAddress? = null

    /**
     * Names go to AliDNS / DNSPod first, then to the router ([gatewayIp]): a router running OpenClash can hijack queries to
     * public resolvers and answer with fake 198.18.x addresses while its own DNS still gives real ones (seen at home).
     */
    fun query(localIp: String, gatewayIp: String? = null): String? {
        val local = InetAddress.getByName(localIp)
        val resolvers = listOfNotNull("223.5.5.5", "119.29.29.29", gatewayIp)
        val tried = mutableSetOf<InetAddress>()
        for (host in StunCodec.servers) {
            val address = resolvers.firstNotNullOfOrNull { dns ->
                runCatching { Dns.query(host, dns, local) }.getOrNull()?.firstOrNull { !DesktopNetwork.tunAddress(it.hostAddress) }
            }
            if (address == null) { BoundTransport.record(host, "stun dns", null, null); continue }
            tried += address
            ask(host, address, local)?.let { return it }
        }
        return lastGood?.takeIf { it !in tried }?.let { ask("last stun", it, local) }
    }

    private fun ask(host: String, address: InetAddress, local: InetAddress): String? {
        val result = runCatching { ask(address, local) }
        BoundTransport.record(host, "stun " + if (result.getOrNull() != null) "ok" else "failed", null, result.exceptionOrNull())
        return result.getOrNull()?.also { lastGood = address }
    }

    private fun ask(server: InetAddress, local: InetAddress): String? = DatagramChannel.open().socket().use { s ->
        Egress.pin(s.channel, local)
        s.bind(InetSocketAddress(local, 0))
        s.soTimeout = 3_000
        val id = ByteArray(12).also(random::nextBytes)
        val q = request(id)
        s.send(DatagramPacket(q, q.size, InetSocketAddress(server, StunCodec.PORT)))
        val buf = ByteArray(1500); val p = DatagramPacket(buf, buf.size); s.receive(p)
        if (p.address != server) return null
        parse(buf.copyOf(p.length), id)
    }

    internal fun request(id: ByteArray): ByteArray = StunCodec.request(id)
    internal fun parse(reply: ByteArray, id: ByteArray): String? = StunCodec.parse(reply, id)
}
