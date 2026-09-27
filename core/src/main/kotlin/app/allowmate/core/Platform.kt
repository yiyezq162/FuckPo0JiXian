package app.allowmate.core

import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class Capabilities(val atomicAddWithoutEviction: Boolean = false, val safeReplace: Boolean = false)
interface Platform {
    val capabilities: Capabilities
    suspend fun query(): Snapshot
    suspend fun addIfUnchanged(expected: Snapshot): Snapshot
}
/** Reviewed Po0 fixed-slot replacement, not a CAS/atomic conditional-add contract. */
interface SlotPlatform : Platform { suspend fun writeSlot(slot: Int): Snapshot }
class ApiFailure(val code: String, val http: Int = 0, val retryAfterMs: Long = 0) : Exception(code)
data class HttpReply(val status: Int, val body: String, val retryAfter: String? = null)
fun interface Transport { suspend fun execute(method: String, url: String): HttpReply }

object Wire {
    fun parse(body: String): Snapshot = try {
        val o = Json.parseToJsonElement(body).jsonObject
        if (o["enabled"]?.jsonPrimitive?.boolean != true) throw ApiFailure("PLATFORM_DISABLED")
        val rows = o.getValue("whitelist").jsonArray.map {
            val e = it.jsonObject
            Entry(Cidr(e.getValue("ip").jsonPrimitive.content), e["slot"]?.takeUnless { v -> v is JsonNull }?.jsonPrimitive?.int)
        }
        Snapshot(Cidr(o.getValue("currentIp").jsonPrimitive.content), rows, o.getValue("limit").jsonPrimitive.int)
    } catch (e: ApiFailure) { throw e } catch (_: Exception) { throw ApiFailure("INVALID_RESPONSE") }
    fun retryAfter(value: String?, now: Long): Long {
        if (value == null) return 0
        val seconds = value.toLongOrNull()
        if (seconds != null) return seconds.coerceIn(0, Long.MAX_VALUE / 1000) * 1000
        return try { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0) }
        catch (_: Exception) { 0 }
    }
}

/** Endpoint is fixed to reviewed source. No request logging, redirects or TLS bypass. */
class Po0Platform(private val token: () -> String?, private val transport: Transport,
                  private val now: () -> Long = System::currentTimeMillis) : SlotPlatform {
    override val capabilities = Capabilities()
    override suspend fun query(): Snapshot {
        return request("GET", "")
    }
    override suspend fun writeSlot(slot: Int): Snapshot {
        require(slot >= 0)
        return request("POST", "/add?slot=$slot")
    }
    private suspend fun request(method: String, suffix: String): Snapshot {
        val secret = token()?.takeIf { it.matches(Regex("pgnfw_[A-Za-z0-9_-]+")) } ?: throw ApiFailure("NO_TOKEN")
        val reply = transport.execute(method, "https://124.221.69.228/api/firewall/$secret$suffix")
        if (reply.status != 200) throw ApiFailure("HTTP_${reply.status}", reply.status, Wire.retryAfter(reply.retryAfter, now()))
        return Wire.parse(reply.body)
    }
    override suspend fun addIfUnchanged(expected: Snapshot): Snapshot = throw ApiFailure("UNSAFE_SERVER_ADD")
}

/** Test/demo only: server-side revision check and capacity refusal, not Po0 behavior. */
class DemoPlatform : Platform {
    override val capabilities = Capabilities(atomicAddWithoutEviction = true)
    var current = Cidr("203.0.113.0/24")
    var capacity = 5
    var entries = listOf(Entry(Cidr("198.51.100.0/24")))
    var revision = 0
    override suspend fun query() = Snapshot(current, entries, capacity, revision.toString())
    override suspend fun addIfUnchanged(expected: Snapshot): Snapshot {
        if (expected.revision != revision.toString() || expected.current != current) throw ApiFailure("CONCURRENT_CHANGE", 409)
        if (entries.none { it.cidr == current }) {
            if (entries.size >= capacity) throw ApiFailure("CAPACITY_FULL", 409)
            entries = entries + Entry(current); revision++
        }
        return query()
    }
}
