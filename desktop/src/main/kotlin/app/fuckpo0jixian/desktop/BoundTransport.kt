package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.ApiFailure
import app.fuckpo0jixian.core.HttpReply
import app.fuckpo0jixian.core.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * HTTPS over sockets bound to the LAN interface's own address, the desktop equivalent of Android's
 * Network.openConnection. macOS scoped routing and the Windows strong host model then send them out of that
 * interface even while a VPN / TUN (Clash, sing-box …) owns the default route, and the system proxy is never used.
 * Names are resolved the same way, against domestic public DNS, so a TUN's fake-IP DNS cannot redirect them.
 * DNS-over-HTTPS comes first: a TUN with strict routing (Windows WFP) drops port 53 outside the tunnel, but not 443.
 * Minimal on purpose: GET/POST without body, no redirects, no cookies, 256 KB cap, certificate and host checked.
 */
class BoundTransport(private val localIp: String) : Transport {
    private val local = InetAddress.getByName(localIp)

    override suspend fun execute(method: String, url: String): HttpReply = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null)
        val host = uri.host
        val port = if (uri.port == -1) 443 else uri.port
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        fetch(resolve(host), host, port, method, path)
    }

    private suspend fun fetch(address: InetAddress, host: String, port: Int, method: String, path: String): HttpReply {
        requests.incrementAndGet()
        val raw = SocketChannel.open().socket()
        // Closing the socket is the only way to interrupt a blocking read when the check is cancelled.
        val closer = currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { raw.close() } }
        var stage = "connect"
        var pinned = false
        try {
            try {
                pinned = Egress.pin(raw.channel, local)
                raw.bind(InetSocketAddress(local, 0))
                raw.connect(InetSocketAddress(address, port), 10_000)
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; note(host, stage, pinned, e); throw ApiFailure("NETWORK_CONNECT_FAILED") }
            raw.soTimeout = 10_000
            if (raw.localAddress != local) throw ApiFailure("NETWORK_CONNECT_FAILED")
            stage = "tls"
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            return tls.use { socket ->
                socket.startHandshake()
                stage = "response"
                val request = "$method $path HTTP/1.1\r\nHost: $host\r\nAccept: application/json\r\nUser-Agent: FuckPo0JiXian\r\n" +
                    (if (method == "POST") "Content-Length: 0\r\n" else "") + "Connection: close\r\n\r\n"
                socket.outputStream.apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
                parse(socket.inputStream).also { note(host, "ok", pinned, null) }
            }
        } catch (e: ApiFailure) { throw e }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; note(host, stage, pinned, e); throw ApiFailure("NETWORK_TLS_OR_RESPONSE_ERROR") }
        finally { closer?.dispose(); runCatching { raw.close() } }
    }

    private suspend fun resolve(host: String): InetAddress {
        if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return InetAddress.getByName(host)
        val now = System.currentTimeMillis()
        resolved[host]?.takeIf { it.localIp == localIp && now - it.time < CACHE_MS }?.let { return it.address }
        var via = "doh"
        val address = dohServers.firstNotNullOfOrNull { server ->
            runCatching { Dns.parseJson(fetch(InetAddress.getByName(server), server, 443, "GET", "/resolve?name=$host&type=1").body) }
                .getOrNull()?.firstOrNull { !fakeIp(it) }
        } ?: udpServers.firstNotNullOfOrNull { server -> via = "udp"; runCatching { Dns.query(host, server, local) }.getOrNull()?.firstOrNull { !fakeIp(it) } }
            // Last resort: the system resolver, unless it answers with a TUN's fake address.
            ?: run { via = "system"; runCatching { InetAddress.getAllByName(host) }.getOrDefault(emptyArray()).filterIsInstance<Inet4Address>().firstOrNull { !fakeIp(it) } }
            ?: run { note(host, "dns", false, null); throw ApiFailure("NETWORK_DNS_FAILED") }
        note(host, "dns-$via", false, null)
        resolved[host] = Resolved(address, localIp, now)
        return address
    }
    private fun fakeIp(a: InetAddress) = a.address.let { (it[0].toInt() and 0xff) == 198 && (it[1].toInt() and 0xfe) == 18 }

    private class Resolved(val address: InetAddress, val localIp: String, val time: Long)

    private fun note(host: String, stage: String, pinned: Boolean, error: Exception?) {
        val cause = error?.let { e -> generateSequence<Throwable>(e) { it.cause }.take(3).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(120)}" } }
        val line = "${java.time.Instant.now()} ${maskIps(host)} $stage" + (if (os == Os.WINDOWS && !stage.startsWith("dns")) " pin=$pinned" else "") +
            (cause?.let { " ${maskIps(it)}" } ?: "")
        synchronized(trace) { trace.addLast(line); while (trace.size > 12) trace.removeFirst() }
    }

    companion object {
        val requests = AtomicLong()
        private const val LIMIT = 262_144
        private const val CACHE_MS = 600_000L
        /** AliDNS and DNSPod, by address; both serve certificates for their IPs. */
        private val dohServers = listOf("223.5.5.5", "1.12.12.12")
        private val udpServers = listOf("223.5.5.5", "119.29.29.29")
        private val resolved = java.util.concurrent.ConcurrentHashMap<String, Resolved>()
        private val trace = ArrayDeque<String>()
        /** The latest requests of this run, IPs kept to /16 like the rest of the redacted export. */
        fun trace(): List<String> = synchronized(trace) { trace.toList() }
        internal fun maskIps(text: String) = text.replace(Regex("""\b(\d{1,3}\.\d{1,3})\.\d{1,3}\.\d{1,3}\b""")) { "${it.groupValues[1]}.*.*" }

        internal fun parse(input: InputStream): HttpReply {
            val status = line(input)?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw ApiFailure("NETWORK_TLS_OR_RESPONSE_ERROR")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val l = line(input) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val body = if (status != 200) "" else when {
                headers["transfer-encoding"]?.contains("chunked", true) == true -> chunked(input)
                headers["content-length"] != null -> exactly(input, headers.getValue("content-length").toIntOrNull()?.takeIf { it in 0..LIMIT }
                    ?: throw ApiFailure("RESPONSE_TOO_LARGE"))
                else -> untilEnd(input)
            }.toString(Charsets.UTF_8)
            return HttpReply(status, body, headers["retry-after"])
        }
        private fun line(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1)
                if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1).trimEnd('\r')
                out.write(b); if (out.size() > 8192) throw ApiFailure("NETWORK_TLS_OR_RESPONSE_ERROR")
            }
        }
        private fun exactly(input: InputStream, n: Int): ByteArray = input.readNBytes(n).also { if (it.size != n) throw ApiFailure("NETWORK_TLS_OR_RESPONSE_ERROR") }
        private fun untilEnd(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > LIMIT) throw ApiFailure("RESPONSE_TOO_LARGE") }
            return out.toByteArray()
        }
        private fun chunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = line(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw ApiFailure("NETWORK_TLS_OR_RESPONSE_ERROR")
                if (size == 0) break
                if (out.size() + size > LIMIT) throw ApiFailure("RESPONSE_TOO_LARGE")
                out.write(exactly(input, size)); line(input)
            }
            return out.toByteArray()
        }
    }
}

/** A single A-record query over UDP from the bound address; enough to find where to connect. */
internal object Dns {
    fun query(host: String, server: String, local: InetAddress): List<InetAddress> = DatagramChannel.open().socket().use { s ->
        Egress.pin(s.channel, local)
        s.bind(InetSocketAddress(local, 0))
        s.soTimeout = 3_000
        val id = (System.nanoTime() and 0xffff).toInt()
        val q = encode(id, host)
        s.send(DatagramPacket(q, q.size, InetSocketAddress(server, 53)))
        val buf = ByteArray(1500); val p = DatagramPacket(buf, buf.size); s.receive(p)
        decode(buf.copyOf(p.length), id)
    }
    /** A-record addresses from a DoH JSON reply (Google-style /resolve); CNAME entries are skipped. */
    internal fun parseJson(body: String): List<InetAddress> {
        require(Regex(""""Status"\s*:\s*0\b""").containsMatchIn(body))
        return Regex(""""data"\s*:\s*"(\d{1,3}(?:\.\d{1,3}){3})"""").findAll(body).map { InetAddress.getByName(it.groupValues[1]) }.toList()
    }
    internal fun encode(id: Int, host: String): ByteArray {
        val b = ByteBuffer.allocate(512)
        b.putShort(id.toShort()).putShort(0x0100).putShort(1).putShort(0).putShort(0).putShort(0)
        host.trimEnd('.').split('.').forEach { label -> require(label.length in 1..63); b.put(label.length.toByte()).put(label.toByteArray(Charsets.US_ASCII)) }
        b.put(0).putShort(1).putShort(1)
        return b.array().copyOf(b.position())
    }
    internal fun decode(r: ByteArray, id: Int): List<InetAddress> {
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
}
