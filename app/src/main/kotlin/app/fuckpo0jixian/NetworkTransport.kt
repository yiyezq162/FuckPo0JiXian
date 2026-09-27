package app.fuckpo0jixian

import android.net.Network
import app.fuckpo0jixian.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NetworkTransport(private val network: Network) : Transport {
    override suspend fun execute(method: String, url: String): HttpReply = suspendCancellableCoroutine { continuation ->
        var connection: HttpsURLConnection? = null
        val lock = Any()
        continuation.invokeOnCancellation { executor.execute { synchronized(lock) { connection?.disconnect() } } }
        executor.execute {
            try {
                val c = network.openConnection(URL(url)) as HttpsURLConnection
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
        private val executor = Executors.newFixedThreadPool(2)
        val requests = java.util.concurrent.atomic.AtomicLong()
    }
}
