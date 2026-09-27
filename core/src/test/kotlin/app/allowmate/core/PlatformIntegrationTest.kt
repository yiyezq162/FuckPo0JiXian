package app.allowmate.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.*

class PlatformIntegrationTest {
    private val body = """{"enabled":true,"currentIp":"203.0.113.0/24","limit":9,"whitelist":[{"ip":"198.51.100.0/24","slot":2}]}"""
    @Test fun noTokenNoNetworkAndUnsafeAddNeverReachesTransport() = runTest {
        var calls = 0
        val p = Po0Platform({ null }, Transport { _, _ -> calls++; HttpReply(200, body) })
        assertEquals("NO_TOKEN", assertFailsWith<ApiFailure> { p.query() }.code)
        assertEquals("UNSAFE_SERVER_ADD", assertFailsWith<ApiFailure> { p.addIfUnchanged(Wire.parse(body)) }.code)
        assertEquals(0, calls)
    }
    @Test fun adapterUsesReviewedHttpsEndpointAndGetOnly() = runTest {
        val p = Po0Platform({ "pgnfw_TEST_ONLY" }, Transport { method, url ->
            assertEquals("GET", method); assertEquals("https://124.221.69.228/api/firewall/pgnfw_TEST_ONLY", url)
            HttpReply(200, body)
        })
        assertEquals(9, p.query().capacity); assertFalse(p.capabilities.atomicAddWithoutEviction)
    }
    @Test fun malformedResponseFailsClosed() {
        listOf("{}", body.replace("true", "false"), body.replace("/24", "/16"), body.replace("\"limit\":9", "\"limit\":0")).forEach {
            assertFailsWith<ApiFailure> { Wire.parse(it) }
        }
    }
    @Test fun retryAfterSecondsAndDate() {
        assertEquals(120_000, Wire.retryAfter("120", 0))
        assertEquals(60_000, Wire.retryAfter("Thu, 01 Jan 1970 00:01:00 GMT", 0))
        assertEquals(0, Wire.retryAfter("bad", 0))
    }
    @Test fun mockHttpServerQueryAndRateLimitIntegration() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var count = 0
        server.createContext("/query") { exchange ->
            count++
            val code = if (count == 1) 200 else 429
            if (code == 429) exchange.responseHeaders.add("Retry-After", "300")
            val bytes = if (code == 200) body.toByteArray() else "{}".toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            // Test transport deliberately routes to loopback. Production transport has no override.
            val p = Po0Platform({ "pgnfw_TEST_ONLY" }, Transport { method, _ ->
                val c = URL("http://127.0.0.1:${server.address.port}/query").openConnection() as HttpURLConnection
                try {
                    c.requestMethod = method
                    val code = c.responseCode
                    HttpReply(code, if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else "", c.getHeaderField("Retry-After"))
                } finally { c.disconnect() }
            })
            assertEquals(9, p.query().capacity)
            val e = assertFailsWith<ApiFailure> { p.query() }
            assertEquals(429, e.http); assertEquals(300_000, e.retryAfterMs)
        } finally { server.stop(0) }
    }
}
