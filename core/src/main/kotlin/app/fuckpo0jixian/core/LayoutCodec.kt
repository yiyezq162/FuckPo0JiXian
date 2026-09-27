package app.fuckpo0jixian.core

import kotlinx.serialization.json.*

internal object LayoutCodec {
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.n(k: String) = get(k)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.b(k: String) = getValue(k).jsonPrimitive.boolean
    private fun JsonObject.l(k: String) = getValue(k).jsonPrimitive.long
    fun encode(l: SlotLayout) = buildJsonObject {
        put("version", l.version)
        putJsonArray("slots") { l.slots.forEach { s -> add(buildJsonObject {
            put("number", s.number); put("name", s.name); put("purpose", s.purpose.name); put("writer", s.writer.name)
            put("identityId", s.identityId); put("automatic", s.automatic); put("allowUnknownWifi", s.allowUnknownWifi)
            put("authorized", s.authorized); put("baseline", s.baseline?.value); put("status", s.status)
            put("legacyPending", s.legacyPending?.value); put("temporaryHold", s.temporaryHold)
        }) } }
        putJsonArray("identities") { l.identities.forEach { n -> add(buildJsonObject {
            put("id", n.id); put("name", n.name); put("ssid", n.ssid)
            putJsonArray("aps") { n.aps.forEach { a -> add(buildJsonObject { put("bssid", a.bssid); put("security", a.security.name) }) } }
        }) } }
        putJsonArray("notices") { l.notices.forEach { add(it) } }
        put("pending", l.pending?.let { p -> buildJsonObject {
            put("account", p.account); put("version", p.version); put("slot", p.slot); put("operation", p.operation)
            put("original", p.original?.value); put("candidate", p.candidate.value); put("identityId", p.identityId)
            put("before", StateCodec.snapshot(p.before)); put("time", p.time)
        } } ?: JsonNull)
    }
    fun decode(o: JsonObject): SlotLayout = SlotLayout(o.l("version"), o.getValue("slots").jsonArray.map { e ->
        val s = e.jsonObject
        ManagedSlot(s.l("number").toInt(), s.s("name"), SlotPurpose.valueOf(s.s("purpose")), Writer.valueOf(s.s("writer")),
            s.n("identityId"), s.b("automatic"), s.b("allowUnknownWifi"), s.b("authorized"), s.n("baseline")?.let(::Cidr),
            s.s("status"), s.n("legacyPending")?.let(::Cidr), s.b("temporaryHold"))
    }, o.getValue("identities").jsonArray.map { e ->
        val n = e.jsonObject
        NetworkIdentity(n.s("id"), n.s("name"), n.s("ssid"), n.getValue("aps").jsonArray.map {
            AuthorizedAp(it.jsonObject.s("bssid"), WifiSecurity.valueOf(it.jsonObject.s("security")))
        }.toSet())
    }, o["pending"]?.takeUnless { it is JsonNull }?.jsonObject?.let { p ->
        val b = p.getValue("before").jsonObject
        val snap = Snapshot(Cidr(b.s("current")), b.getValue("entries").jsonArray.map {
            Entry(Cidr(it.jsonObject.s("cidr")), it.jsonObject.n("slot")?.toInt())
        }, b.l("capacity").toInt(), b.n("revision"))
        SlotPending(p.s("account"), p.l("version"), p.l("slot").toInt(), p.s("operation"), p.n("original")?.let(::Cidr),
            Cidr(p.s("candidate")), p.n("identityId"), snap, p.l("time"))
    }, o.getValue("notices").jsonArray.map { it.jsonPrimitive.content }.toSet())
}
