package app.fuckpo0jixian.core

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * A single A-record DNS query and its answer, for resolving over a socket pinned to the physical network when the
 * system resolver answers with a proxy's fake IPs (198.18.0.0/15). Shared by the platforms.
 */
object DnsCodec {
    /** AliDNS and DNSPod, by address. */
    val servers = listOf("223.5.5.5", "119.29.29.29")

    fun encode(id: Int, host: String): ByteArray {
        val b = ByteBuffer.allocate(512)
        b.putShort(id.toShort()).putShort(0x0100).putShort(1).putShort(0).putShort(0).putShort(0)
        host.trimEnd('.').split('.').forEach { label -> require(label.length in 1..63); b.put(label.length.toByte()).put(label.toByteArray(Charsets.US_ASCII)) }
        b.put(0).putShort(1).putShort(1)
        return b.array().copyOf(b.position())
    }
    fun decode(r: ByteArray, id: Int): List<InetAddress> {
        val b = ByteBuffer.wrap(r)
        require((b.short.toInt() and 0xffff) == id)
        val flags = b.short.toInt(); require(flags and 0x8000 != 0 && flags and 0x000f == 0)
        val qd = b.short.toInt(); val an = b.short.toInt(); b.short; b.short
        fun skipName() { while (true) { val len = b.get().toInt() and 0xff; if (len == 0) return; if (len and 0xc0 == 0xc0) { b.get(); return }; b.position(b.position() + len) } }
        repeat(qd) { skipName(); b.position(b.position() + 4) }
        val out = mutableListOf<InetAddress>()
        repeat(an) {
            skipName()
            val type = b.short.toInt(); b.short; b.int; val len = b.short.toInt() and 0xffff
            if (type == 1 && len == 4) { val a = ByteArray(4); b.get(a); out += InetAddress.getByAddress(a) } else b.position(b.position() + len)
        }
        return out
    }
    /** Clash / sing-box fake-IP range; never a real server. */
    fun fakeIp(a: InetAddress) = a.address.size == 4 && (a.address[0].toInt() and 255) == 198 && (a.address[1].toInt() and 254) == 18
}
