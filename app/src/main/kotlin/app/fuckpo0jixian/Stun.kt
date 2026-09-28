package app.fuckpo0jixian

import android.net.Network
import app.fuckpo0jixian.core.DnsCodec
import app.fuckpo0jixian.core.StunCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * The public IPv4 of [Network] via a STUN binding request: one small UDP exchange instead of an HTTPS request, on a
 * socket bound to the physical network, so a VPN never carries it. Names are resolved through that network; when its
 * resolver answers with a proxy's fake IPs (198.18.0.0/15, e.g. OpenClash on the router) AliDNS / DNSPod are asked
 * directly over the same network instead. Null when no server answers; callers fall back to HTTPS.
 */
object Stun {
    private val random = SecureRandom()

    fun query(network: Network): String? {
        for (host in StunCodec.servers) {
            val server = resolve(network, host) ?: continue
            runCatching { ask(network, server) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun ask(network: Network, server: InetAddress): String? = DatagramSocket().use { socket ->
        network.bindSocket(socket)
        socket.soTimeout = 2_500
        val id = ByteArray(12).also(random::nextBytes)
        val request = StunCodec.request(id)
        socket.send(DatagramPacket(request, request.size, InetSocketAddress(server, StunCodec.PORT)))
        val buffer = ByteArray(1500)
        val reply = DatagramPacket(buffer, buffer.size)
        socket.receive(reply)
        if (reply.address != server) null else StunCodec.parse(buffer.copyOf(reply.length), id)
    }

    private fun resolve(network: Network, host: String): InetAddress? =
        runCatching { network.getAllByName(host) }.getOrNull()?.filterIsInstance<Inet4Address>()?.firstOrNull { !DnsCodec.fakeIp(it) }
            ?: DnsCodec.servers.firstNotNullOfOrNull { dns -> runCatching { query(network, host, dns) }.getOrNull()?.firstOrNull { !DnsCodec.fakeIp(it) } }

    private fun query(network: Network, host: String, dns: String): List<InetAddress> = DatagramSocket().use { socket ->
        network.bindSocket(socket)
        socket.soTimeout = 2_000
        val id = random.nextInt(0x10000)
        val request = DnsCodec.encode(id, host)
        socket.send(DatagramPacket(request, request.size, InetSocketAddress(InetAddress.getByName(dns), 53)))
        val buffer = ByteArray(1500)
        val reply = DatagramPacket(buffer, buffer.size)
        socket.receive(reply)
        DnsCodec.decode(buffer.copyOf(reply.length), id)
    }
}
