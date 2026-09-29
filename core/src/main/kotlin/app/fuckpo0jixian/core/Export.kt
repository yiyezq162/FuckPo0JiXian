package app.fuckpo0jixian.core

import kotlinx.serialization.json.*
import java.time.Instant

/**
 * How long the activity logs behind 调试信息 are kept: the same week as observations and events, with a line ceiling
 * so a runaway loop cannot grow the file without bound. Lines start with an ISO UTC time; lines without one are kept.
 */
object LogRetention {
    val windowMs = Policy().retentionMs
    const val MAX_LINES = 20_000
    /** Above this the file is trimmed at once. A line is at most ~262 bytes, so a trimmed file always fits below it. */
    const val MAX_BYTES = MAX_LINES * 280L
    /** Rewrite the file at most this often; between rewrites it may run a little past the window. */
    const val TRIM_EVERY_MS = 3_600_000L
    fun keep(lines: List<String>, now: Long): List<String> {
        val cutoff = now - windowMs
        return lines.filter { line ->
            line.isNotBlank() && (runCatching { Instant.parse(line.substringBefore(' ')).toEpochMilli() }.getOrNull() ?: now) >= cutoff
        }.takeLast(MAX_LINES)
    }
}

/**
 * Masking for debug output, applied to whole texts: IPv4 addresses keep their first two parts (/16), IPv6 addresses
 * their first two groups, and MAC addresses (Wi-Fi access points, routers) are dropped. Times such as 03:10:00 and
 * version numbers such as 0.8.5 are left alone.
 */
object Redact {
    // Android's ICU regex reads "[:" as the start of a POSIX class, so "-" comes first in the set.
    private val mac = Regex("""(?<![0-9A-Za-z:])[0-9A-Fa-f]{2}(?:[-:][0-9A-Fa-f]{2}){5}(?![0-9A-Za-z:])""")
    private val ipv4 = Regex("""(?<![0-9.])(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}(?![0-9])""")
    private val ipv6 = Regex("""(?<![0-9A-Za-z:])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?![0-9A-Za-z:])""")

    fun text(value: String): String = value
        .replace(mac) { "**:**:**:**:**:**" }
        .replace(ipv4) { "${it.groupValues[1]}.${it.groupValues[2]}.*.*" }
        .replace(ipv6) { m ->
            val groups = m.value.split(':')
            // A real IPv6 address has "::" or at least six groups; 03:10:00 has neither.
            if (!m.value.contains("::") && groups.size < 6) m.value
            else groups.filter { it.isNotEmpty() }.take(2).joinToString(":") + ":*"
        }

    /** A short stable stand-in for a Wi-Fi name or access point, the same within one installation ([salt]). */
    fun tag(value: String?, salt: String): String? = value?.takeIf { it.isNotBlank() }?.let {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("$salt\u0000$it".toByteArray())
        "#" + digest.take(3).joinToString("") { b -> "%02x".format(b) }
    }
}

private val pretty = Json { prettyPrint = true }
private fun time(value: Long) = if (value <= 0) JsonNull else JsonPrimitive(Instant.ofEpochMilli(value).toString())

/**
 * 共享分工: this device's slots for the user's other devices. Nothing is masked except that the Token never enters
 * [State]; it carries the /24 ranges, the device name and the Wi-Fi names and access points each slot is bound to.
 * [PeerImport] reads it (and the older redacted export) and only ever labels the slots this device manages.
 */
object ShareExport {
    const val FORMAT = "fuckpo0jixian-share"
    const val NOTE = "本机的槽位分工，供自己的其他设备导入。不含 Token；包含 IP 段（/24）、设备名、Wi-Fi 名称与接入点，请勿公开发布。"

    fun build(s: State, platform: String, appVersion: String, now: Long): String = pretty.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", FORMAT); put("version", 2); put("note", NOTE)
        put("exportedAt", time(now))
        putJsonObject("device") { put("name", s.deviceName); put("platform", platform); put("app", appVersion) }
        s.snapshot?.let { snap -> putJsonObject("whitelist") {
            put("capacity", snap.capacity); put("current", snap.current.value)
            putJsonArray("entries") { snap.entries.forEach { e -> add(buildJsonObject { put("slot", e.slot); put("cidr", e.cidr.value) }) } }
        } }
        put("domesticExit", s.domesticExit?.cidr?.value)
        val identities = s.layout?.identities.orEmpty()
        putJsonArray("slots") { s.layout?.slots.orEmpty().sortedBy { it.number }.forEach { slot -> add(buildJsonObject {
            put("number", slot.number); put("name", slot.name); put("purpose", slot.purpose.name); put("writer", slot.writer.name)
            put("owner", slot.owner); put("shared", slot.shared); put("automatic", slot.automatic)
            put("allowUnknownWifi", slot.allowUnknownWifi); put("authorized", slot.authorized); put("temporaryHold", slot.temporaryHold)
            put("status", slot.status); put("baseline", slot.baseline?.value); put("changedAt", time(slot.changedAt))
            identities.find { it.id == slot.identityId }?.let { n -> putJsonObject("network") {
                put("name", n.name); put("ssid", n.ssid)
                putJsonArray("aps") { n.aps.sortedBy { it.bssid }.forEach { a -> add(buildJsonObject { put("bssid", a.bssid); put("security", a.security.name) }) } }
            } }
        }) } }
        putJsonArray("networks") { NetworkHistory.summarize(s.observations, now).take(20).forEach { n -> add(buildJsonObject {
            put("cidr", n.cidr.value); put("days", n.days); put("visits", n.visits); put("common", n.common); put("lastSeen", time(n.lastSeen))
        }) } }
    })
}

/**
 * 调试信息: everything that helps explain what happened and when, for development. No Token and no account
 * identifier; every text value is passed through [Redact] (addresses to /16), and Wi-Fi names and access points
 * become per-installation tags. Device, slot and network names people typed are kept. The platform facts in
 * `device` (OS, ROM and Java versions) are left as they are, since version numbers look like addresses.
 * [device] adds platform facts (OS version, model, …); [logs] adds timelines such as the lifecycle log.
 */
object DebugExport {
    const val FORMAT = "fuckpo0jixian-debug"
    const val NOTE = "调试信息。不含 Token 和账户标识；IP 只保留前两段（/16），IPv6 只保留前两组，Wi-Fi 名称与接入点以本机哈希标记代替。" +
        "保留设备名、槽位名称等手填文字，其中写入的 IP 也会按 /16 处理。"

    fun build(s: State, device: Map<String, String?>, now: Long, logs: Map<String, List<String>> = emptyMap(),
              details: Map<String, String?> = emptyMap()): String {
        val salt = s.accountContext
        val tree = buildJsonObject {
            put("format", FORMAT); put("version", 1); put("note", NOTE)
            put("exportedAt", time(now))
            putJsonObject("device") { put("name", s.deviceName); device.forEach { (k, v) -> put(k, v) } }
            putJsonObject("state") {
                put("mode", s.mode.name); put("paused", s.paused); put("demo", s.demo); put("runtimeMode", s.runtimeMode.name)
                put("status", s.status); put("failures", s.failures); put("authBlocked", s.authBlocked); put("globalBlock", s.globalBlock)
                put("lastCheck", time(s.lastCheck)); put("lastSuccess", time(s.lastSuccess)); put("nextAllowed", time(s.nextAllowed))
                put("serverNotBefore", time(s.serverNotBefore)); put("nextProbeAllowed", time(s.nextProbeAllowed))
                put("probeStatus", s.probeStatus); put("networkKey", s.networkKey)
                put("fallbackMinutes", FallbackInterval.clamp(s.fallbackMinutes)); put("endpoint", s.endpoint)
                put("legacyPlan", s.slotPlan != null)
            }
            if (details.isNotEmpty()) putJsonObject("details") { details.forEach { (k, v) -> put(k, v) } }
            s.snapshot?.let { snap -> putJsonObject("whitelist") {
                put("capacity", snap.capacity); put("current", snap.current.value); put("revision", snap.revision)
                putJsonArray("entries") { snap.entries.forEach { e -> add(buildJsonObject { put("slot", e.slot); put("cidr", e.cidr.value) }) } }
            } }
            s.domesticExit?.let { d -> putJsonObject("domesticExit") {
                put("ip", d.ipv4); put("source", d.source.name); put("time", time(d.time)); put("networkKey", d.networkKey)
            } }
            s.layout?.let { l -> putJsonObject("layout") {
                put("version", l.version)
                putJsonArray("slots") { l.slots.sortedBy { it.number }.forEach { slot -> add(buildJsonObject {
                    put("number", slot.number); put("name", slot.name); put("purpose", slot.purpose.name); put("writer", slot.writer.name)
                    put("owner", slot.owner); put("shared", slot.shared); put("automatic", slot.automatic)
                    put("allowUnknownWifi", slot.allowUnknownWifi); put("authorized", slot.authorized); put("temporaryHold", slot.temporaryHold)
                    put("status", slot.status); put("baseline", slot.baseline?.value); put("legacyPending", slot.legacyPending?.value)
                    put("identity", slot.identityId?.let { Redact.tag(it, salt) }); put("changedAt", time(slot.changedAt))
                }) } }
                putJsonArray("identities") { l.identities.forEach { n -> add(buildJsonObject {
                    put("id", Redact.tag(n.id, salt)); put("name", n.name); put("ssid", Redact.tag(n.ssid, salt))
                    putJsonArray("aps") { n.aps.forEach { a -> add(buildJsonObject { put("ap", Redact.tag(a.bssid, salt)); put("security", a.security.name) }) } }
                }) } }
                putJsonArray("notices") { l.notices.sorted().forEach { n ->
                    add(ApNotice.parse(n)?.let { ap -> "${n.substringBefore(':')}:${Redact.tag(ap.ssid, salt)}:${Redact.tag(ap.bssid, salt)}:${ap.security}" } ?: n)
                } }
                l.pending?.let { p -> putJsonObject("pending") {
                    put("slot", p.slot); put("operation", p.operation); put("version", p.version)
                    put("original", p.original?.value); put("candidate", p.candidate.value); put("time", time(p.time))
                } }
            } }
            putJsonArray("observations") { s.observations.takeLast(200).forEach { o -> add(buildJsonObject {
                put("time", time(o.time)); put("cidr", o.cidr.value); put("network", o.networkKind)
            }) } }
            putJsonArray("events") { s.events.forEach { e -> add(buildJsonObject { put("time", time(e.time)); put("code", e.code) }) } }
            logs.filterValues { it.isNotEmpty() }.forEach { (name, lines) -> putJsonArray(name) { lines.forEach { add(it) } } }
        }
        val masked = JsonObject(tree.mapValues { (key, value) ->
            if (key == "device") JsonObject(value.jsonObject.mapValues { (k, v) -> if (k == "name") redact(v) else v }) else redact(value)
        })
        return pretty.encodeToString(JsonObject.serializer(), masked)
    }

    private fun redact(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(e.map(::redact))
        is JsonPrimitive -> if (e.isString) JsonPrimitive(Redact.text(e.content)) else e
    }
}

/**
 * What changed between two saved states, one line per fact, for the activity logs behind 调试信息. Raw addresses:
 * the logs mask them when writing.
 */
object StateDiff {
    fun describe(old: State, new: State): List<String> = buildList {
        if (old.mode != new.mode) add("MODE ${new.mode}")
        if (old.paused != new.paused) add(if (new.paused) "PAUSED" else "RESUMED")
        if (old.demo != new.demo) add("DEMO ${new.demo}")
        if (old.runtimeMode != new.runtimeMode) add("RUNTIME ${new.runtimeMode}")
        if (old.fallbackMinutes != new.fallbackMinutes) add("FALLBACK ${new.fallbackMinutes}min")
        if (old.deviceName != new.deviceName) add("DEVICE_NAME ${new.deviceName}")
        if (old.endpoint != new.endpoint) add("ENDPOINT ${new.endpoint.removePrefix("https://")}")
        if (old.status != new.status) add("STATUS ${old.status} -> ${new.status}")
        if (old.authBlocked != new.authBlocked) add("AUTH ${if (new.authBlocked) "blocked" else "ok"}")
        if (old.globalBlock != new.globalBlock) add("BLOCK ${new.globalBlock ?: "cleared"}")
        if (old.failures != new.failures) add("FAILURES ${new.failures}" +
            if (new.nextAllowed > 0) " next=${Instant.ofEpochMilli(new.nextAllowed)}" else "")
        if (old.serverNotBefore != new.serverNotBefore && new.serverNotBefore > 0) add("SERVER_WAIT until=${Instant.ofEpochMilli(new.serverNotBefore)}")
        if (old.networkKey != new.networkKey) add("NETWORK_KEY ${new.networkKey}")
        if (old.snapshot?.current != new.snapshot?.current) add("PO0_SEES ${new.snapshot?.current?.value}")
        if (old.snapshot?.capacity != new.snapshot?.capacity && new.snapshot != null) add("CAPACITY ${new.snapshot.capacity}")
        val before = old.snapshot?.entries.orEmpty().associate { (it.slot ?: -1) to it.cidr }
        val after = new.snapshot?.entries.orEmpty().associate { (it.slot ?: -1) to it.cidr }
        if (old.snapshot != null && new.snapshot != null) (before.keys + after.keys).filter { it >= 0 }.sorted().forEach { slot ->
            if (before[slot] != after[slot]) add("WHITELIST slot=$slot ${before[slot]?.value ?: "-"} -> ${after[slot]?.value ?: "-"}")
        }
        val unslottedBefore = old.snapshot?.entries.orEmpty().filter { it.slot == null }.map { it.cidr }.toSet()
        val unslottedAfter = new.snapshot?.entries.orEmpty().filter { it.slot == null }.map { it.cidr }.toSet()
        if (old.snapshot != null && new.snapshot != null && unslottedBefore != unslottedAfter)
            add("WHITELIST unslotted ${unslottedAfter.size} entries")
        if (old.domesticExit != new.domesticExit) new.domesticExit?.let { add("EXIT ${it.ipv4} via ${it.source}") }
        if (old.probeStatus != new.probeStatus) add("PROBE ${new.probeStatus}")
        val oldSlots = old.layout?.slots.orEmpty().associateBy { it.number }
        new.layout?.slots.orEmpty().forEach { slot ->
            val was = oldSlots[slot.number]
            if (was == slot) return@forEach
            val fields = if (was == null) listOf("added") else buildList {
                if (was.name != slot.name) add("name=${slot.name}")
                if (was.purpose != slot.purpose) add("purpose=${slot.purpose}")
                if (was.writer != slot.writer) add("writer=${slot.writer}")
                if (was.owner != slot.owner) add("owner=${slot.owner}")
                if (was.authorized != slot.authorized) add("authorized=${slot.authorized}")
                if (was.automatic != slot.automatic) add("automatic=${slot.automatic}")
                if (was.shared != slot.shared) add("shared=${slot.shared}")
                if (was.allowUnknownWifi != slot.allowUnknownWifi) add("unknownWifi=${slot.allowUnknownWifi}")
                if (was.temporaryHold != slot.temporaryHold) add("hold=${slot.temporaryHold}")
                if (was.identityId != slot.identityId) add(if (slot.identityId == null) "wifi=unbound" else "wifi=bound")
                if (was.baseline != slot.baseline) add("baseline=${slot.baseline?.value}")
                if (was.status != slot.status) add("status=${slot.status}")
            }
            if (fields.isNotEmpty()) add("SLOT ${slot.number} ${fields.joinToString(" ")}")
        }
        (oldSlots.keys - new.layout?.slots.orEmpty().map { it.number }.toSet()).sorted().forEach { add("SLOT $it removed") }
        if (old.layout?.pending != new.layout?.pending) add(new.layout?.pending?.let {
            "PENDING slot=${it.slot} ${it.operation} ${it.original?.value ?: "-"} -> ${it.candidate.value}"
        } ?: "PENDING cleared")
        val seen = old.events.toSet()
        new.events.filterNot { it in seen }.takeLast(10).forEach { add("EVENT ${it.code}") }
    }
}

data class PeerSlot(val number: Int, val name: String, val purpose: SlotPurpose, val writer: Writer, val owner: String)
data class PeerLayout(val device: String, val slots: List<PeerSlot>)
data class ImportResult(val state: State, val changed: List<Int>)

/** Reads another device's export and labels the slots it manages. Never grants or changes local authority. */
object PeerImport {
    /** The combined redacted export of 0.8.5 and earlier; still importable. */
    const val LEGACY_FORMAT = "fuckpo0jixian-export"
    fun parse(text: String): PeerLayout = try {
        require(text.length <= 262_144)
        val o = Json.parseToJsonElement(text.trim()).jsonObject
        require(o.getValue("format").jsonPrimitive.content in setOf(ShareExport.FORMAT, LEGACY_FORMAT))
        val device = o.getValue("device").jsonObject.getValue("name").jsonPrimitive.content.trim().take(24)
        PeerLayout(device, o.getValue("slots").jsonArray.map {
            val e = it.jsonObject
            PeerSlot(e.getValue("number").jsonPrimitive.int, e.getValue("name").jsonPrimitive.content.trim().take(40),
                SlotPurpose.valueOf(e.getValue("purpose").jsonPrimitive.content), Writer.valueOf(e.getValue("writer").jsonPrimitive.content),
                e["owner"]?.jsonPrimitive?.contentOrNull?.trim()?.take(24) ?: "")
        })
    } catch (_: Exception) { throw IllegalArgumentException("IMPORT_INVALID") }

    fun apply(s: State, peer: PeerLayout): ImportResult {
        val snap = requireNotNull(s.snapshot) { "CONFIG_QUERY_FIRST" }
        val layout = s.layout ?: SlotLayout()
        require(layout.pending == null) { "PENDING_REVIEW" }
        require(peer.device.isNotBlank()) { "IMPORT_DEVICE_NAME" }
        require(!peer.device.equals(s.deviceName.trim(), ignoreCase = true)) { "IMPORT_SAME_DEVICE" }
        val mine = s.deviceName.trim()
        val changed = mutableListOf<Int>()
        val slots = layout.slots.associateBy { it.number }.toMutableMap()
        peer.slots.filter { it.number in 0 until snap.capacity }.forEach { p ->
            val local = slots[p.number]
            if (local?.writer == Writer.LOCAL) return@forEach // Local authority is only ever set by hand.
            val owner = when (p.writer) {
                Writer.LOCAL -> peer.device
                Writer.OTHER_DEVICE -> p.owner.takeUnless { mine.isNotEmpty() && it.equals(mine, ignoreCase = true) }
                Writer.EXTERNAL -> null
            }
            val base = local ?: ManagedSlot(p.number)
            val next = if (owner != null && p.purpose != SlotPurpose.RESERVED)
                base.copy(name = p.name.ifBlank { base.name }, purpose = p.purpose, writer = Writer.OTHER_DEVICE, owner = owner,
                    automatic = false, allowUnknownWifi = false, authorized = false, shared = false, identityId = null)
            else base.copy(name = base.name.ifBlank { p.name })
            if (next != base) { slots[p.number] = next; changed += p.number }
        }
        if (changed.isEmpty()) return ImportResult(s, changed)
        val updated = layout.copy(version = layout.version + 1, slots = slots.values.sortedBy { it.number })
        LayoutRules.validate(snap, updated)
        return ImportResult(s.copy(layout = updated, lastSuccess = 0), changed.sorted())
    }
}
