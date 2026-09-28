package app.fuckpo0jixian.core

import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Review export shared between devices and with helpers. Contains no token, account context, network keys,
 * Wi-Fi names or access points, and every address keeps only its first two octets.
 */
object RedactedExport {
    const val FORMAT = "fuckpo0jixian-export"
    const val NOTE = "已脱敏：不含 Token、账户标识、Wi-Fi 名称与接入点；IP 只保留前两段（/16）。"
    private val pretty = Json { prettyPrint = true }
    private fun time(value: Long) = if (value <= 0) JsonNull else JsonPrimitive(Instant.ofEpochMilli(value).toString())

    fun build(s: State, platform: String, appVersion: String, now: Long, trace: List<String> = emptyList(),
              lifecycle: List<String> = emptyList()): String = pretty.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", FORMAT); put("version", 1); put("note", NOTE)
        put("exportedAt", time(now))
        putJsonObject("device") { put("name", s.deviceName); put("platform", platform); put("app", appVersion) }
        putJsonObject("sync") {
            put("mode", s.mode.name); put("paused", s.paused); put("demo", s.demo); put("runtimeMode", s.runtimeMode.name)
            put("status", s.status); put("failures", s.failures); put("authBlocked", s.authBlocked); put("globalBlock", s.globalBlock)
            put("lastCheck", time(s.lastCheck)); put("lastSuccess", time(s.lastSuccess))
            put("pendingWrite", s.layout?.pending != null)
            put("fallbackMinutes", FallbackInterval.clamp(s.fallbackMinutes))
        }
        s.snapshot?.let { snap -> putJsonObject("whitelist") {
            put("capacity", snap.capacity); put("current", snap.current.masked)
            putJsonArray("entries") { snap.entries.forEach { e -> add(buildJsonObject { put("slot", e.slot); put("cidr", e.cidr.masked) }) } }
        } }
        put("domesticExit", s.domesticExit?.cidr?.masked)
        put("probeStatus", s.probeStatus)
        val identities = s.layout?.identities.orEmpty()
        putJsonArray("slots") { s.layout?.slots.orEmpty().sortedBy { it.number }.forEach { slot -> add(buildJsonObject {
            put("number", slot.number); put("name", slot.name); put("purpose", slot.purpose.name); put("writer", slot.writer.name)
            put("owner", slot.owner); put("shared", slot.shared); put("automatic", slot.automatic)
            put("allowUnknownWifi", slot.allowUnknownWifi); put("authorized", slot.authorized); put("temporaryHold", slot.temporaryHold)
            put("status", slot.status); put("baseline", slot.baseline?.masked); put("changedAt", time(slot.changedAt))
            val identity = identities.find { it.id == slot.identityId }
            put("network", when {
                identity == null -> "未绑定"
                identity.aps.all { it.security == WifiSecurity.GATEWAY } -> "已绑定路由器"
                else -> "已绑定 Wi-Fi · ${identity.aps.size} 个接入点"
            })
        }) } }
        putJsonArray("networks") { NetworkHistory.summarize(s.observations, now).take(10).forEach { n -> add(buildJsonObject {
            put("cidr", n.cidr.masked); put("days", n.days); put("visits", n.visits); put("common", n.common)
        }) } }
        putJsonArray("events") { s.events.takeLast(50).forEach { e -> add(buildJsonObject { put("time", time(e.time)); put("code", e.code) }) } }
        if (trace.isNotEmpty()) putJsonArray("trace") { trace.forEach { add(it) } }
        // Android: process starts and wakes, the system's exit reasons, Doze and checks (see LifeLog).
        if (lifecycle.isNotEmpty()) putJsonArray("lifecycle") { lifecycle.forEach { add(it) } }
    })
}

data class PeerSlot(val number: Int, val name: String, val purpose: SlotPurpose, val writer: Writer, val owner: String)
data class PeerLayout(val device: String, val slots: List<PeerSlot>)
data class ImportResult(val state: State, val changed: List<Int>)

/** Reads another device's export and labels the slots it manages. Never grants or changes local authority. */
object PeerImport {
    fun parse(text: String): PeerLayout = try {
        require(text.length <= 262_144)
        val o = Json.parseToJsonElement(text.trim()).jsonObject
        require(o.getValue("format").jsonPrimitive.content == RedactedExport.FORMAT)
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
