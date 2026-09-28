package app.fuckpo0jixian.core

import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Po0's fixed-slot replacement, not a CAS/atomic conditional-add contract. */
interface SlotPlatform {
    suspend fun query(): Snapshot
    suspend fun writeSlot(slot: Int): Snapshot
}
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
    /**
     * Longest wait a Retry-After can impose. Nothing can shorten a server wait (not a manual check, nor saving or
     * clearing the token), so an absurd value would otherwise stop the app for good. A server that still wants
     * more simply answers 429 again.
     */
    const val MAX_RETRY_AFTER_MS = 6 * 3_600_000L
    fun retryAfter(value: String?, now: Long): Long {
        if (value == null) return 0
        val seconds = value.toLongOrNull()
        if (seconds != null) return seconds.coerceIn(0, MAX_RETRY_AFTER_MS / 1000) * 1000
        return try { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceIn(0, MAX_RETRY_AFTER_MS) }
        catch (_: Exception) { 0 }
    }
}

/** The endpoint comes from the official link people pasted. No request logging, redirects or TLS bypass. */
class Po0Platform(private val token: () -> String?, private val transport: Transport,
                  private val now: () -> Long = System::currentTimeMillis,
                  private val endpoint: String = Po0Credential.DEFAULT_ENDPOINT) : SlotPlatform {
    init { require(Po0Credential.validEndpoint(endpoint)) { "invalid_endpoint" } }
    override suspend fun query(): Snapshot {
        return request("GET", "")
    }
    override suspend fun writeSlot(slot: Int): Snapshot {
        require(slot >= 0)
        return request("POST", "/add?slot=$slot")
    }
    private suspend fun request(method: String, suffix: String): Snapshot {
        val secret = token()?.takeIf { it.matches(Regex("pgnfw_[A-Za-z0-9_-]+")) } ?: throw ApiFailure("NO_TOKEN")
        val reply = transport.execute(method, "$endpoint/api/firewall/$secret$suffix")
        if (reply.status != 200) throw ApiFailure("HTTP_${reply.status}", reply.status, Wire.retryAfter(reply.retryAfter, now()))
        return Wire.parse(reply.body)
    }
}

/** Demo only: an in-memory whitelist with Po0's slot semantics plus a revision counter. */
class SlotDemoPlatform : SlotPlatform {
    var current = Cidr("203.0.113.0/24")
    var capacity = 5
    var entries = listOf(Entry(Cidr("198.51.100.0/24")))
    var revision = 0
    override suspend fun query() = Snapshot(current, entries, capacity, revision.toString())
    override suspend fun writeSlot(slot: Int): Snapshot {
        if (entries.any { it.cidr == current && it.slot != slot }) throw ApiFailure("COVERED_OTHER_SLOT")
        if (entries.none { it.slot == slot } && entries.size >= capacity) throw ApiFailure("CAPACITY_FULL")
        entries = entries.filterNot { it.slot == slot } + Entry(current, slot)
        revision++
        return query()
    }
}
