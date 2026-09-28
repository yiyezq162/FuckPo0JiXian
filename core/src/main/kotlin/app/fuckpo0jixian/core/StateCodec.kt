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
        put("serverNotBefore", s.serverNotBefore); put("endpoint", s.endpoint)
        put("layout", s.layout?.let(LayoutCodec::encode) ?: JsonNull)
        put("runtimeMode", s.runtimeMode.name); put("deviceName", s.deviceName); put("fallbackMinutes", s.fallbackMinutes)
        // Retired fields, still written empty: earlier releases require them to read this file.
        put("fixed", 0); put("mobile", 0); putJsonArray("profiles") {}; putJsonArray("ownership") {}; put("activeProfileId", JsonNull)
        put("lastCheck", s.lastCheck); put("lastSuccess", s.lastSuccess); put("nextAllowed", s.nextAllowed)
        put("failures", s.failures); put("authBlocked", s.authBlocked); put("networkKey", s.networkKey); put("status", s.status)
        put("slotPlan", s.slotPlan?.let { p -> buildJsonObject {
            put("home", p.home.value); put("homeSlot", p.homeSlot); put("mobileSlot", p.mobileSlot)
            put("homeReady", p.homeReady); put("lastMobile", p.lastMobile?.value); put("pendingMobile", p.pendingMobile?.value)
        } } ?: JsonNull)
        put("nextProbeAllowed", s.nextProbeAllowed); put("probeStatus", s.probeStatus)
        put("domesticExit", s.domesticExit?.let { d -> buildJsonObject {
            put("ipv4", d.ipv4); put("time", d.time); put("networkKey", d.networkKey); put("source", d.source.name)
        } } ?: JsonNull)
        put("snapshot", s.snapshot?.let(::snapshot) ?: JsonNull)
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
        val state = State(mode = Mode.valueOf(o.s("mode")), paused = o.b("paused"), demo = o.b("demo"), snapshot = snap,
            lastCheck = o.l("lastCheck"), lastSuccess = o.l("lastSuccess"), nextAllowed = o.l("nextAllowed"),
            failures = o.l("failures").toInt(), authBlocked = o.b("authBlocked"), networkKey = o.optional("networkKey"), status = o.s("status"),
            observations = o.getValue("observations").jsonArray.map { it.jsonObject.let { v -> Observation(v.l("time"), Cidr(v.s("cidr")), v.s("kind")) } },
            events = o.getValue("events").jsonArray.map { it.jsonObject.let { v -> Event(v.l("time"), v.s("code")) } },
            domesticExit = o["domesticExit"]?.takeUnless { it is JsonNull }?.jsonObject?.let { DomesticExit(it.s("ipv4"), it.l("time"), it.s("networkKey"), ProbeSource.valueOf(it.optional("source") ?: "IPIP")) },
            nextProbeAllowed = o.optional("nextProbeAllowed")?.toLong() ?: 0, probeStatus = o.optional("probeStatus") ?: "NOT_CHECKED",
            slotPlan = o["slotPlan"]?.takeUnless { it is JsonNull }?.jsonObject?.let { p ->
                SlotPlan(Cidr(p.s("home")), p.l("homeSlot").toInt(), p.l("mobileSlot").toInt(), p.b("homeReady"),
                    p.optional("lastMobile")?.let(::Cidr), p.optional("pendingMobile")?.let(::Cidr))
            }, runtimeMode = o.optional("runtimeMode")?.let { runCatching { RuntimeMode.valueOf(it) }.getOrNull() } ?: RuntimeMode.STANDARD,
            layout = o["layout"]?.takeUnless { it is JsonNull }?.jsonObject?.let(LayoutCodec::decode),
            accountContext = o.optional("accountContext") ?: java.util.UUID.randomUUID().toString(), globalBlock = o.optional("globalBlock"),
            deviceName = o.optional("deviceName") ?: "",
            // Absent before 0.8: the default cadence.
            fallbackMinutes = FallbackInterval.clamp(o.optional("fallbackMinutes")?.toIntOrNull() ?: FallbackInterval.DEFAULT),
            // Old pending failures may have hidden HTTP_429; conservatively preserve their existing deadline.
            serverNotBefore = o.optional("serverNotBefore")?.toLong() ?: if (o.s("status") in setOf("HTTP_429", "PENDING_REVIEW")) o.l("nextAllowed") else 0,
            endpoint = o.optional("endpoint")?.takeIf(Po0Credential::validEndpoint) ?: Po0Credential.DEFAULT_ENDPOINT)
        return if (o.l("version") == 1L) LayoutRules.migrate(state) else state
    }
}
