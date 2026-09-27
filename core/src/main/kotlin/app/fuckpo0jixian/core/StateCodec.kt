package app.fuckpo0jixian.core

import kotlinx.serialization.json.*

/** Explicit versioned JSON; no Java object deserialization or credentials in state. */
object StateCodec {
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.l(k: String) = getValue(k).jsonPrimitive.long
    private fun JsonObject.b(k: String) = getValue(k).jsonPrimitive.boolean
    private fun JsonObject.optional(k: String) = get(k)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    internal fun snapshot(v: Snapshot) = buildJsonObject {
        put("current", v.current.value); put("capacity", v.capacity); put("revision", v.revision)
        putJsonArray("entries") { v.entries.forEach { e -> add(buildJsonObject { put("cidr", e.cidr.value); put("slot", e.slot) }) } }
    }
    fun encode(s: State): String = buildJsonObject {
        put("version", 2); put("mode", s.mode.name); put("paused", s.paused); put("demo", s.demo)
        put("accountContext", s.accountContext); put("globalBlock", s.globalBlock)
        put("layout", s.layout?.let(LayoutCodec::encode) ?: JsonNull)
        put("runtimeMode", s.runtimeMode.name); put("deviceName", s.deviceName); put("fallbackMinutes", s.fallbackMinutes)
        put("fixed", s.budget.fixed); put("mobile", s.budget.mobile)
        put("lastCheck", s.lastCheck); put("lastSuccess", s.lastSuccess); put("nextAllowed", s.nextAllowed)
        put("failures", s.failures); put("authBlocked", s.authBlocked); put("networkKey", s.networkKey); put("status", s.status)
        put("activeProfileId", s.activeProfileId)
        put("slotPlan", s.slotPlan?.let { p -> buildJsonObject {
            put("home", p.home.value); put("homeSlot", p.homeSlot); put("mobileSlot", p.mobileSlot)
            put("homeReady", p.homeReady); put("lastMobile", p.lastMobile?.value); put("pendingMobile", p.pendingMobile?.value)
        } } ?: JsonNull)
        put("nextProbeAllowed", s.nextProbeAllowed); put("probeStatus", s.probeStatus)
        put("domesticExit", s.domesticExit?.let { d -> buildJsonObject {
            put("ipv4", d.ipv4); put("time", d.time); put("networkKey", d.networkKey); put("source", d.source.name)
        } } ?: JsonNull)
        put("snapshot", s.snapshot?.let(::snapshot) ?: JsonNull)
        putJsonArray("profiles") { s.profiles.forEach { p -> add(buildJsonObject {
            put("id", p.id); put("name", p.name); put("kind", p.kind.name); put("cidr", p.cidr?.value); put("hint", p.networkHint)
        }) } }
        putJsonArray("ownership") { s.ownership.forEach { o -> add(buildJsonObject {
            put("cidr", o.cidr.value); put("authorized", o.authorized); put("protected", o.protected); put("used", o.lastUsed)
        }) } }
        putJsonArray("observations") { s.observations.forEach { o -> add(buildJsonObject {
            put("time", o.time); put("cidr", o.cidr.value); put("kind", o.networkKind)
        }) } }
        putJsonArray("events") { s.events.forEach { e -> add(buildJsonObject { put("time", e.time); put("code", e.code) }) } }
    }.toString()
    fun decode(text: String): State {
        require(text.length <= 1_048_576)
        val o = Json.parseToJsonElement(text).jsonObject
        require(o.l("version") in 1L..2L)
        val snap = o["snapshot"]?.takeUnless { it is JsonNull }?.jsonObject?.let { v ->
            Snapshot(Cidr(v.s("current")), v.getValue("entries").jsonArray.map {
                val e = it.jsonObject; Entry(Cidr(e.s("cidr")), e.optional("slot")?.toInt())
            }, v.l("capacity").toInt(), v.optional("revision"))
        }
        val state = State(Mode.valueOf(o.s("mode")), o.b("paused"), o.b("demo"), Budget(o.l("fixed").toInt(), o.l("mobile").toInt()),
            o.getValue("profiles").jsonArray.map { it.jsonObject.let { p -> Profile(p.s("id"), p.s("name"), Kind.valueOf(p.s("kind")), p.optional("cidr")?.let(::Cidr), p.optional("hint")) } },
            o.getValue("ownership").jsonArray.map { it.jsonObject.let { v -> Ownership(Cidr(v.s("cidr")), v.b("authorized"), v.b("protected"), v.l("used")) } },
            snap, o.l("lastCheck"), o.l("lastSuccess"), o.l("nextAllowed"), o.l("failures").toInt(), o.b("authBlocked"), o.optional("networkKey"), o.s("status"),
            o.getValue("observations").jsonArray.map { it.jsonObject.let { v -> Observation(v.l("time"), Cidr(v.s("cidr")), v.s("kind")) } },
            o.getValue("events").jsonArray.map { it.jsonObject.let { v -> Event(v.l("time"), v.s("code")) } }, o.optional("activeProfileId"),
            o["domesticExit"]?.takeUnless { it is JsonNull }?.jsonObject?.let { DomesticExit(it.s("ipv4"), it.l("time"), it.s("networkKey"), ProbeSource.valueOf(it.optional("source") ?: "IPIP")) },
            o.optional("nextProbeAllowed")?.toLong() ?: 0, o.optional("probeStatus") ?: "NOT_CHECKED",
            o["slotPlan"]?.takeUnless { it is JsonNull }?.jsonObject?.let { p ->
                SlotPlan(Cidr(p.s("home")), p.l("homeSlot").toInt(), p.l("mobileSlot").toInt(), p.b("homeReady"),
                    p.optional("lastMobile")?.let(::Cidr), p.optional("pendingMobile")?.let(::Cidr))
            }, o.optional("runtimeMode")?.let { runCatching { RuntimeMode.valueOf(it) }.getOrNull() } ?: RuntimeMode.STANDARD,
            o["layout"]?.takeUnless { it is JsonNull }?.jsonObject?.let(LayoutCodec::decode),
            o.optional("accountContext") ?: java.util.UUID.randomUUID().toString(), o.optional("globalBlock"),
            o.optional("deviceName") ?: "",
            // Absent before 0.8: the default cadence.
            FallbackInterval.clamp(o.optional("fallbackMinutes")?.toIntOrNull() ?: FallbackInterval.DEFAULT))
        return if (o.l("version") == 1L) LayoutRules.migrate(state) else state
    }
}
