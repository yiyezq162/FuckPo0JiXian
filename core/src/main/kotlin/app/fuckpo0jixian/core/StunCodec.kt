package app.fuckpo0jixian.core

import java.nio.ByteBuffer

/**
 * STUN binding request and reply (RFC 5389), shared by the platforms: each sends it over a socket pinned to the
 * physical network, so the reply shows the exit that cannot have gone through a proxy. Domestic servers only.
 */
object StunCodec {
    val servers = listOf("stun.chat.bilibili.com", "stun.miwifi.com", "stun.hitv.com")
    const val PORT = 3478
    private const val COOKIE = 0x2112A442

    fun request(id: ByteArray): ByteArray {
        require(id.size == 12)
        return ByteBuffer.allocate(20).putShort(0x0001).putShort(0).putInt(COOKIE).put(id).array()
    }

    /** XOR-MAPPED-ADDRESS, or MAPPED-ADDRESS from older servers; IPv4 only. */
    fun parse(reply: ByteArray, id: ByteArray): String? {
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
