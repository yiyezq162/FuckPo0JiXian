package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.ApiFailure
import app.fuckpo0jixian.core.DomesticProbe
import app.fuckpo0jixian.core.ProbeSource
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import kotlin.test.*

class BoundTransportTest {
    private fun http(text: String) = BoundTransport.parse(text.replace("\n", "\r\n").byteInputStream())

    @Test fun parsesLengthChunkedAndErrorReplies() {
        val plain = http("HTTP/1.1 200 OK\nContent-Length: 13\n\n{\"a\":\"hello\"}")
        assertEquals(200, plain.status); assertEquals("{\"a\":\"hello\"}", plain.body)
        val chunked = BoundTransport.parse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n1.2.\r\n3\r\n3.4\r\n0\r\n\r\n".byteInputStream())
        assertEquals("1.2.3.4", chunked.body)
        val limited = http("HTTP/1.1 429 Too Many Requests\nRetry-After: 120\nContent-Length: 5\n\nslow!")
        assertEquals(429, limited.status); assertEquals("", limited.body); assertEquals("120", limited.retryAfter)
        assertFailsWith<ApiFailure> { http("HTTP/1.1 200 OK\nContent-Length: 999999999\n\n") }
        assertFailsWith<ApiFailure> { http("garbage") }
    }

    @Test fun traceKeepsAddressesToSixteenBits() {
        assertEquals("connect to 124.221.*.* failed", BoundTransport.maskIps("connect to 124.221.69.228 failed"))
    }

    @Test fun stunReadsXorMappedAddress() {
        val id = ByteArray(12) { it.toByte() }
        assertEquals(20, Stun.request(id).size)
        // Binding success with one XOR-MAPPED-ADDRESS for 203.0.113.9:54321.
        val cookie = 0x2112A442
        val body = java.nio.ByteBuffer.allocate(12).putShort(0x0020).putShort(8).put(0).put(1)
            .putShort((54321 xor (cookie ushr 16)).toShort()).putInt(((203 shl 24) or (0 shl 16) or (113 shl 8) or 9) xor cookie).array()
        val reply = java.nio.ByteBuffer.allocate(20 + body.size).putShort(0x0101).putShort(body.size.toShort()).putInt(cookie).put(id).put(body).array()
        assertEquals("203.0.113.9", Stun.parse(reply, id))
        assertNull(Stun.parse(reply, ByteArray(12)))
        assertNull(Stun.parse(reply.copyOf(10), id))
    }

    @Test fun dnsQueryRoundTrips() {
        val q = Dns.encode(0x1234, "ip.3322.net")
        // Build a reply: header with answer count 1, the question, then one compressed A record.
        val answer = byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 203.toByte(), 0, 113, 7)
        val reply = q.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 1 } + answer
        assertEquals(listOf(InetAddress.getByName("203.0.113.7")), Dns.decode(reply, 0x1234))
        assertFailsWith<IllegalArgumentException> { Dns.decode(reply, 0x9999) }
    }

    @Test fun dohJsonKeepsOnlyARecords() {
        val ali = """{"Status":0,"Question":{"name":"ip.3322.net.","type":1},"Answer":[{"name":"ip.3322.net.","TTL":1387,"type":1,"data":"118.184.169.32"}]}"""
        val dnspod = """{"Status":0,"Answer":[{"name":"a.example.","type":5,"TTL":60,"data":"b.example."},{"name":"b.example.","type":1,"TTL":60,"data":"203.0.113.9"}]}"""
        assertEquals(listOf(InetAddress.getByName("118.184.169.32")), Dns.parseJson(ali))
        assertEquals(listOf(InetAddress.getByName("203.0.113.9")), Dns.parseJson(dnspod))
        assertFailsWith<IllegalArgumentException> { Dns.parseJson("""{"Status":2,"Answer":[{"data":"203.0.113.9"}]}""") }
    }

    @Test fun netstatDefaultsSkipNothingButKeepOrder() {
        val out = """
            Routing tables

            Internet:
            Destination        Gateway            Flags               Netif Expire
            default            198.18.0.1         UGScg               utun0
            default            192.168.5.1        UGScIg                en6
            default            link#23            UCSIg             bridge0
        """.trimIndent()
        assertEquals(listOf("198.18.0.1" to "utun0", "192.168.5.1" to "en6"), DesktopNetwork.parseNetstatDefault(out))
    }

    /** On Windows (CI included) the pin must really apply, and a pinned request must get an answer. */
    @Test fun windowsPinsSocketsToTheLanInterface() = runBlocking {
        if (os != Os.WINDOWS) return@runBlocking
        val link = DesktopNetwork.read()
        if (!link.online) return@runBlocking
        java.nio.channels.SocketChannel.open().use { assertTrue(Egress.pin(it, InetAddress.getByName(link.localIp))) }
        java.nio.channels.DatagramChannel.open().use { assertTrue(Egress.pin(it, InetAddress.getByName(link.localIp))) }
        assertEquals(200, BoundTransport(link.localIp!!).execute("GET", "https://1.1.1.1/cdn-cgi/trace").status)
        assertEquals(200, BoundTransport(link.localIp!!, allowTunnel = true).execute("GET", "https://1.1.1.1/cdn-cgi/trace").status)
        System.err.println("WINDOWS pinned request ok via ${link.iface}; stun=${Stun.query(link.localIp!!) != null}")
        System.err.println(BoundTransport.trace().joinToString("\n"))
    }

    /** Real request out of the LAN interface. Opt-in: FUCKPO0JIXIAN_LIVE=1. */
    @Test fun liveProbeLeavesThroughTheLanInterface() = runBlocking {
        if (System.getenv("FUCKPO0JIXIAN_LIVE") != "1") return@runBlocking
        val link = DesktopNetwork.read()
        val exit = DomesticProbe.parse(BoundTransport(link.localIp!!).execute("GET", ProbeSource.IP3322.url), 0, link.key, ProbeSource.IP3322)
        System.err.println("LIVE exit=${exit.cidr.masked} gateway=${link.gatewayMac != null}")
    }
}
