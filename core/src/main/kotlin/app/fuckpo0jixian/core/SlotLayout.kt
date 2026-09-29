package app.fuckpo0jixian.core

import java.io.Serializable

enum class SlotPurpose { FIXED, MOBILE, RESERVED }
enum class Writer { LOCAL, OTHER_DEVICE, EXTERNAL }
/** GATEWAY: desktop networks are identified by the router's MAC (no location permission needed). */
enum class WifiSecurity { WPA2, WPA3, ENTERPRISE, ENTERPRISE_WPA3, OPEN, UNKNOWN, GATEWAY }
data class AuthorizedAp(val bssid: String, val security: WifiSecurity) : Serializable
data class NetworkIdentity(val id: String, val name: String, val ssid: String,
                           val aps: Set<AuthorizedAp>) : Serializable
/** An observation belongs to a request Network, never to a persistent networkHandle identity. */
data class WifiObservation(val networkKey: String, val ssid: String?, val bssid: String?,
                           val security: WifiSecurity, val time: Long, val available: Boolean = true) {
    fun usable(now: Long, key: String) = available && networkKey == key && now - time in 0..60_000 &&
        !ssid.isNullOrBlank() && ssid != "<unknown ssid>" &&
        bssid?.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) == true &&
        bssid !in setOf("02:00:00:00:00:00", "00:00:00:00:00:00") &&
        security !in setOf(WifiSecurity.OPEN, WifiSecurity.UNKNOWN)
    fun sameIdentity(other: WifiObservation?) = other != null &&
        copy(time = 0) == other.copy(time = 0)
}
/**
 * A Po0 slot as this device sees it. A FIXED slot may be bound to several networks ([identityIds]): an office with
 * two Wi-Fi names or a wired and a wireless router, all leaving through the same exit, updates one slot.
 */
data class ManagedSlot(val number: Int, val name: String = "", val purpose: SlotPurpose = SlotPurpose.RESERVED,
                       val writer: Writer = Writer.EXTERNAL, val identityIds: List<String> = emptyList(),
                       val automatic: Boolean = false, val allowUnknownWifi: Boolean = false,
                       val authorized: Boolean = false, val baseline: Cidr? = null,
                       val status: String = "UNMANAGED", val legacyPending: Cidr? = null,
                       val temporaryHold: Boolean = false,
                       /** Display label of the device that manages an OTHER_DEVICE slot; never an authorization. */
                       val owner: String = "",
                       /** LOCAL fixed slot that other devices on the same network may also update. */
                       val shared: Boolean = false,
                       /** When this device last saw someone else change the occupant. */
                       val changedAt: Long = 0) : Serializable {
    val bound: Boolean get() = identityIds.isNotEmpty()
}
data class SlotPending(val account: String, val version: Long, val slot: Int, val operation: String,
                       val original: Cidr?, val candidate: Cidr, val identityId: String?,
                       val before: Snapshot, val time: Long) : Serializable
data class SlotLayout(val version: Long = 1, val slots: List<ManagedSlot> = emptyList(),
                      val identities: List<NetworkIdentity> = emptyList(), val pending: SlotPending? = null,
                      val notices: Set<String> = emptySet()) : Serializable {
    /** The networks bound to [slot], in the order they were added. */
    fun networksOf(slot: ManagedSlot): List<NetworkIdentity> = slot.identityIds.mapNotNull { id -> identities.find { it.id == id } }
    /** The slot a network belongs to; null when it belongs to none. */
    fun slotOf(identityId: String): ManagedSlot? = slots.find { identityId in it.identityIds }
    /** Networks no slot refers to any more match nothing, so they are dropped. */
    fun withoutOrphans(): SlotLayout {
        val used = slots.flatMap { it.identityIds }.toSet()
        return copy(identities = identities.filter { it.id in used })
    }
}
/**
 * A same-name access point this device met but has not trusted: `AP_CONFIRM:<ssid>:<bssid>:<security>`, or
 * `AP_IGNORED:…` once dismissed. Kept as a notice so it can be confirmed later, away from that Wi-Fi.
 */
object ApNotice {
    const val CONFIRM = "AP_CONFIRM:"
    const val IGNORED = "AP_IGNORED:"
    data class Ap(val ssid: String, val bssid: String, val security: WifiSecurity)
    fun of(ssid: String, bssid: String, security: WifiSecurity) = "$CONFIRM$ssid:$bssid:$security"
    fun ignored(notice: String) = IGNORED + notice.removePrefix(CONFIRM)
    /** The SSID may itself contain ':', so the fixed-width BSSID and the security are taken from the right. */
    fun parse(notice: String): Ap? {
        val body = when {
            notice.startsWith(CONFIRM) -> notice.removePrefix(CONFIRM)
            notice.startsWith(IGNORED) -> notice.removePrefix(IGNORED)
            else -> return null
        }
        val security = WifiSecurity.entries.find { it.name == body.substringAfterLast(':') } ?: return null
        val rest = body.substringBeforeLast(':')
        if (rest.length < 19 || rest[rest.length - 18] != ':') return null
        val bssid = rest.takeLast(17)
        val ssid = rest.dropLast(18)
        if (ssid.isBlank() || !bssid.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) return null
        return Ap(ssid, bssid, security)
    }
    /** The one bound fixed slot a confirmed AP would join, or null when there is no single such slot. */
    fun slot(layout: SlotLayout, notice: String): ManagedSlot? {
        val ap = parse(notice) ?: return null
        val identity = layout.identities.singleOrNull { it.ssid == ap.ssid } ?: return null
        return layout.slots.singleOrNull { it.purpose == SlotPurpose.FIXED && it.writer == Writer.LOCAL && identity.id in it.identityIds }
    }
    /** Drops notices that no longer ask anything: the name is unbound, or the AP was added since. */
    fun prune(layout: SlotLayout) = layout.copy(notices = layout.notices.filterNot { n ->
        val ap = parse(n) ?: return@filterNot false
        layout.identities.none { it.ssid == ap.ssid } ||
            layout.identities.any { it.ssid == ap.ssid && it.aps.any { a -> a.bssid.equals(ap.bssid, true) } }
    }.toSet())
}

/** Ephemeral confirmation: deliberately absent from StateCodec. */
data class ManualPermit(val account: String, val version: Long, val slot: Int, val networkKey: String,
                        val wifi: WifiObservation?, val before: Snapshot, val expires: Long)

object LayoutRules {
    fun migrate(s: State): State {
        if (s.layout != null) return s
        val p = s.slotPlan ?: return s
        return s.copy(layout = SlotLayout(slots = listOf(
            ManagedSlot(p.homeSlot, "旧固定记录", SlotPurpose.FIXED, Writer.LOCAL,
                baseline = p.home, status = if (p.homeReady) "IDENTITY_REQUIRED" else "LEGACY_UNINITIALIZED"),
            ManagedSlot(p.mobileSlot, "本机移动", SlotPurpose.MOBILE, Writer.LOCAL,
                automatic = true, baseline = p.lastMobile, legacyPending = p.pendingMobile,
                status = "LEGACY_RECONCILE")
        ), notices = setOf("MIGRATED_WIFI_POLICY_OFF")))
    }
    fun validate(snap: Snapshot, layout: SlotLayout) {
        val numbers = snap.entries.mapNotNull { it.slot }
        if (numbers.distinct().size != numbers.size || numbers.any { it !in 0 until snap.capacity } ||
            layout.slots.map { it.number }.distinct().size != layout.slots.size ||
            layout.slots.any { it.number !in 0 until snap.capacity } ||
            layout.slots.count { it.purpose == SlotPurpose.MOBILE && it.writer == Writer.LOCAL && it.automatic } > 1 ||
            layout.identities.map { it.id }.distinct().size != layout.identities.size ||
            layout.slots.flatMap { it.identityIds }.let { it.distinct().size != it.size })
            throw ApiFailure("SLOT_INVALID")
    }
    /** Whether saving [slot] over [old] grants new automatic power, so the editor must ask for「授权本机管理」again. */
    fun needsAuthorization(old: ManagedSlot?, slot: ManagedSlot): Boolean {
        val granting = slot.writer == Writer.LOCAL && slot.purpose != SlotPurpose.RESERVED &&
            ((old?.automatic != true && slot.automatic) || (old?.allowUnknownWifi != true && slot.allowUnknownWifi) ||
                (slot.automatic && (old?.writer != slot.writer || old.purpose != slot.purpose)))
        // Accepting other writers relaxes conflict protection, so it needs the same explicit confirmation.
        val sharing = slot.shared && slot.writer == Writer.LOCAL && slot.purpose == SlotPurpose.FIXED && old?.shared != true
        return granting || sharing
    }
    fun saveSlot(s: State, slot: ManagedSlot, acknowledge: Boolean, now: Long): State {
        val snap = requireNotNull(s.snapshot) { "CONFIG_QUERY_FIRST" }
        if (acknowledge) require(now - s.lastCheck in 0..120_000) { "CONFIG_QUERY_FIRST" }
        val layout = s.layout ?: SlotLayout()
        require(layout.pending == null) { "PENDING_REVIEW" }
        val old = layout.slots.find { it.number == slot.number }
        val occupant = snap.entries.find { it.slot == slot.number }?.cidr
        require(!needsAuthorization(old, slot) || acknowledge) { "AUTHORIZATION_REQUIRED" }
        val changedAuthority = old?.purpose != slot.purpose || old.writer != slot.writer
        val shared = slot.shared && slot.writer == Writer.LOCAL && slot.purpose == SlotPurpose.FIXED
        val next = slot.copy(name = slot.name.trim().take(40), owner = slot.owner.trim().take(24), shared = shared,
            authorized = slot.writer == Writer.LOCAL && slot.purpose != SlotPurpose.RESERVED && (acknowledge || (old?.authorized == true && !changedAuthority)),
            baseline = if (acknowledge) occupant else old?.baseline,
            identityIds = if (changedAuthority) emptyList() else old.identityIds,
            status = if (acknowledge) "AUTHORIZED_LOCAL" else old?.status ?: "UNMANAGED",
            // Confirming takes the slot as it is now; an earlier change elsewhere no longer holds this device back.
            changedAt = if (acknowledge) 0 else slot.changedAt,
            legacyPending = if (acknowledge) null else old?.legacyPending)
        val updated = layout.copy(version = layout.version + 1, slots = layout.slots.filterNot { it.number == slot.number } + next).withoutOrphans()
        require(updated.slots.count { it.purpose == SlotPurpose.MOBILE && it.writer == Writer.LOCAL && it.automatic } <= 1) { "CONFIG_MOBILE_LIMIT" }
        validate(snap, updated)
        return s.copy(layout = updated, lastSuccess = 0)
    }
    /** How many networks one fixed slot may hold; an office rarely has more names than this. */
    const val MAX_NETWORKS = 8

    /**
     * Binds the network [observation] describes to fixed slot [number]. A network already bound to this slot under
     * the same name gains the access point (another router or band of it); any other name is added next to the
     * networks the slot already has, so one slot can follow several networks that share an exit. A name bound to
     * another slot is refused: one network updates one slot.
     */
    fun bind(s: State, number: Int, observation: WifiObservation, now: Long, name: String): State {
        require(observation.usable(now, observation.networkKey)) { "WIFI_UNAVAILABLE" }
        val layout = requireNotNull(s.layout)
        require(layout.pending == null) { "PENDING_REVIEW" }
        val slot = layout.slots.first { it.number == number }
        require(slot.purpose == SlotPurpose.FIXED && slot.writer == Writer.LOCAL && slot.authorized) { "AUTHORIZATION_REQUIRED" }
        val sameName = layout.identities.filter { it.ssid == observation.ssid }
        require(sameName.all { it.id in slot.identityIds }) { "NETWORK_BOUND_ELSEWHERE" }
        val ap = AuthorizedAp(observation.bssid!!, observation.security)
        val old = sameName.singleOrNull()
        val identity = old?.copy(aps = old.aps + ap)
            ?: NetworkIdentity(java.util.UUID.randomUUID().toString(), name.trim().ifBlank { slot.name.ifBlank { "固定网络" } }.take(40), observation.ssid!!, setOf(ap))
        val ids = if (old != null) slot.identityIds else slot.identityIds + identity.id
        require(ids.size <= MAX_NETWORKS) { "NETWORK_LIMIT" }
        return s.copy(layout = ApNotice.prune(layout.copy(version = layout.version + 1,
            identities = layout.identities.filterNot { it.id == identity.id } + identity,
            slots = layout.slots.map { if (it.number == number) it.copy(identityIds = ids, automatic = true, status = "AUTHORIZED_LOCAL") else it })))
    }
    /** Adds the access point a same-name notice recorded to its bound slot; the person confirms it, not the exit. */
    fun acceptAp(s: State, notice: String): State {
        val layout = requireNotNull(s.layout)
        require(layout.pending == null) { "PENDING_REVIEW" }
        require(notice in layout.notices && notice.startsWith(ApNotice.CONFIRM)) { "CONFIG_INVALID" }
        val ap = requireNotNull(ApNotice.parse(notice)) { "CONFIG_INVALID" }
        val slot = ApNotice.slot(layout, notice)
        require(slot != null && slot.authorized) { "AUTHORIZATION_REQUIRED" }
        val identity = layout.networksOf(slot).first { it.ssid == ap.ssid }
        val added = identity.copy(aps = identity.aps + AuthorizedAp(ap.bssid, ap.security))
        return s.copy(layout = ApNotice.prune(layout.copy(version = layout.version + 1,
            identities = layout.identities.map { if (it.id == identity.id) added else it })))
    }
    /** Keeps the AP untrusted and stops asking about it. */
    fun ignoreAp(s: State, notice: String): State {
        val layout = requireNotNull(s.layout)
        return s.copy(layout = layout.copy(notices = layout.notices.map { if (it == notice) ApNotice.ignored(it) else it }.toSet()))
    }
    /** Removes every network bound to slot [number]; the slot stops updating until it is bound again. */
    fun revoke(s: State, number: Int): State {
        val layout = requireNotNull(s.layout)
        return s.copy(layout = ApNotice.prune(layout.copy(version = layout.version + 1,
            slots = layout.slots.map { if (it.number == number) it.copy(identityIds = emptyList(), automatic = false, status = "IDENTITY_REQUIRED") else it })
            .withoutOrphans()))
    }
    /** Removes one network from slot [number]; the last one leaves the slot unbound, as [revoke] does. */
    fun unbind(s: State, number: Int, identityId: String): State {
        val layout = requireNotNull(s.layout)
        val slot = layout.slots.first { it.number == number }
        if (slot.identityIds == listOf(identityId)) return revoke(s, number)
        return s.copy(layout = ApNotice.prune(layout.copy(version = layout.version + 1,
            slots = layout.slots.map { if (it.number == number) it.copy(identityIds = it.identityIds - identityId) else it })
            .withoutOrphans()))
    }
}

/** [identityId]: the bound network that matched, recorded with a fixed write. */
data class TargetDecision(val slot: ManagedSlot? = null, val code: String, val notice: String? = null, val identityId: String? = null)
object SlotSelection {
    fun select(layout: SlotLayout, session: NetworkSession, now: Long): TargetDecision {
        val wifi = session.wifi
        if (session.kind == NetworkKind.WIFI || session.kind == NetworkKind.LAN) {
            if (wifi?.usable(now, session.key) != true) return TargetDecision(code = "WIFI_UNAVAILABLE")
            val exact = layout.identities.filter { it.ssid == wifi.ssid && AuthorizedAp(wifi.bssid!!, wifi.security) in it.aps }
            if (exact.size > 1) return TargetDecision(code = "IDENTITY_AMBIGUOUS")
            if (exact.size == 1) {
                val targets = layout.slots.filter { it.purpose == SlotPurpose.FIXED && exact.single().id in it.identityIds }
                if (targets.size != 1) return TargetDecision(code = "IDENTITY_AMBIGUOUS")
                return TargetDecision(targets.single(), "FIXED_MATCH", identityId = exact.single().id)
            }
            val sameName = layout.identities.any { it.ssid == wifi.ssid }
            // A changed security policy on an already known AP is not an unknown Wi-Fi authorization.
            if (layout.identities.any { it.ssid == wifi.ssid && it.aps.any { ap -> ap.bssid == wifi.bssid } })
                return TargetDecision(code = "WIFI_SECURITY_CHANGED")
            val mobile = layout.slots.singleOrNull { it.purpose == SlotPurpose.MOBILE && it.writer == Writer.LOCAL && it.automatic && it.allowUnknownWifi }
            return TargetDecision(mobile, if (mobile == null) "UNKNOWN_WIFI" else "MOBILE_MATCH",
                if (sameName) ApNotice.of(wifi.ssid!!, wifi.bssid!!, wifi.security) else null)
        }
        if (session.kind != NetworkKind.CELLULAR) return TargetDecision(code = "NO_TARGET")
        return TargetDecision(layout.slots.singleOrNull { it.purpose == SlotPurpose.MOBILE && it.writer == Writer.LOCAL && it.automatic }, "MOBILE_MATCH")
    }
}

/**
 * The capacity bar, one cell per quota: cells follow slot numbers so slot 2 always sits second, whatever the
 * purposes. A cell holds the purpose of the slot occupying it, or null when empty. Entries without a slot number
 * use quota too; they take the last empty cells, drawn as RESERVED (gray).
 */
object CapacityBar {
    fun cells(snap: Snapshot, layout: SlotLayout?): List<SlotPurpose?> {
        val cells = MutableList(snap.capacity) { n ->
            if (snap.entries.any { it.slot == n }) layout?.slots?.find { it.number == n }?.purpose ?: SlotPurpose.RESERVED else null
        }
        var unassigned = snap.entries.count { it.slot == null || it.slot !in 0 until snap.capacity }
        for (i in cells.indices.reversed()) {
            if (unassigned == 0) break
            if (cells[i] == null) { cells[i] = SlotPurpose.RESERVED; unassigned-- }
        }
        return cells
    }
}
