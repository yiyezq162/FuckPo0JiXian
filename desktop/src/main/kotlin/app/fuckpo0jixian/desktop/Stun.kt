package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.DomesticExit
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.security.SecureRandom

/**
 * The public IPv4 as seen from the LAN interface, via a STUN binding request (RFC 5389) on a pinned UDP socket.
 * With a Windows TUN running, pinned UDP still leaves directly while pinned TCP stalls, so this is the exit
 * observation that cannot have gone through a proxy. Domestic servers only; names resolved the same pinned way.
 */
internal object Stun {
    private val servers = listOf("stun.miwifi.com", "stun.chat.bilibili.com", "stun.hitv.com")
    private const val COOKIE = 0x2112A442
    private val random = SecureRandom()

    fun query(localIp: String): String? {
        val local = InetAddress.getByName(localIp)
        for (host in servers) {
            val address = listOf("223.5.5.5", "119.29.29.29").firstNotNullOfOrNull { dns ->
                runCatching { Dns.query(host, dns, local) }.getOrNull()?.firstOrNull { !DesktopNetwork.tunAddress(it.hostAddress) }
            }
            if (address == null) { BoundTransport.record(host, "stun dns", null, null); continue }
            val result = runCatching { ask(address, local) }
            BoundTransport.record(host, "stun " + if (result.getOrNull() != null) "ok" else "failed", null, result.exceptionOrNull())
            result.getOrNull()?.let { return it }
        }
        return null
    }

    private fun ask(server: InetAddress, local: InetAddress): String? = DatagramChannel.open().socket().use { s ->
        Egress.pin(s.channel, local)
        s.bind(InetSocketAddress(local, 0))
        s.soTimeout = 3_000
        val id = ByteArray(12).also(random::nextBytes)
        val q = request(id)
        s.send(DatagramPacket(q, q.size, InetSocketAddress(server, 3478)))
        val buf = ByteArray(1500); val p = DatagramPacket(buf, buf.size); s.receive(p)
        if (p.address != server) return null
        parse(buf.copyOf(p.length), id)
    }

    internal fun request(id: ByteArray): ByteArray =
        ByteBuffer.allocate(20).putShort(0x0001).putShort(0).putInt(COOKIE).put(id).array()

    /** XOR-MAPPED-ADDRESS, or MAPPED-ADDRESS from older servers; IPv4 only. */
    internal fun parse(reply: ByteArray, id: ByteArray): String? {
        val b = ByteBuffer.wrap(reply)
        if (reply.size < 20 || b.short.toInt() != 0x0101) return null
        val length = b.short.toInt() and 0xffff
        if (b.int != COOKIE || !reply.copyOfRange(8, 20).contentEquals(id) || reply.size < 20 + length) return null
        b.position(20)
        var mapped: String? = null
        while (b.position() + 4 <= 20 + length) {
            val type = b.short.toInt() and 0xffff; val len = b.short.toInt() and 0xffff
            val start = b.position()
            if ((type == 0x0020 || type == 0x0001) && len >= 8 && reply[start + 1].toInt() == 1) {
                var a = ByteBuffer.wrap(reply, start + 4, 4).int
                if (type == 0x0020) a = a xor COOKIE
                val ip = listOf(a ushr 24, a ushr 16, a ushr 8, a).joinToString(".") { (it and 0xff).toString() }
                if (type == 0x0020) return ip.takeIf(DomesticExit::validIpv4)
                mapped = ip
            }
            b.position(start + (len + 3) / 4 * 4)
        }
        return mapped?.takeIf(DomesticExit::validIpv4)
    }
}
