package app.fuckpo0jixian

import android.net.Network
import app.fuckpo0jixian.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Proxy
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NetworkTransport(private val network: Network) : Transport {
    /**
     * The socket timeouts do not cover everything (name lookup, a stalled TLS handshake), and a device log once showed a
     * single check waiting 16 minutes. Past [LIMIT_MS] the request counts as a network error, never as a cancellation.
     */
    override suspend fun execute(method: String, url: String): HttpReply =
        withTimeoutOrNull(LIMIT_MS) { request(method, url) } ?: throw ApiFailure("NETWORK_TLS_OR_RESPONSE_ERROR")

    private suspend fun request(method: String, url: String): HttpReply = suspendCancellableCoroutine { continuation ->
        var connection: HttpsURLConnection? = null
        val lock = Any()
        // Not on [executor]: its threads may be the ones stuck, which is exactly when this must still run.
        continuation.invokeOnCancellation { Thread { synchronized(lock) { connection?.disconnect() } }.start() }
        executor.execute {
            try {
                // Bound to the physical network and never proxied: a VPN / TUN or system proxy cannot carry it.
                val c = network.openConnection(URL(url), Proxy.NO_PROXY) as HttpsURLConnection
                synchronized(lock) {
                    if (!continuation.isActive) { c.disconnect(); return@execute }
                    connection = c
                }
                c.requestMethod = method
                requests.incrementAndGet()
                c.instanceFollowRedirects = false
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.setRequestProperty("Accept", "application/json")
                val status = c.responseCode
                val body = if (status == 200) c.inputStream.use {
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > 262144) throw ApiFailure("RESPONSE_TOO_LARGE")
                        out.write(buffer, 0, count)
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                } else ""
                if (continuation.isActive) continuation.resume(HttpReply(status, body, c.getHeaderField("Retry-After")))
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWithException(ApiFailure("NETWORK_TLS_OR_RESPONSE_ERROR"))
            } finally { synchronized(lock) { connection?.disconnect(); connection = null } }
        }
    }
    companion object {
        private val executor = Executors.newFixedThreadPool(4)
        private const val LIMIT_MS = 35_000L
        val requests = java.util.concurrent.atomic.AtomicLong()
    }
}
