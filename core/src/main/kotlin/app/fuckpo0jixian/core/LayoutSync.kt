package app.fuckpo0jixian.core

import kotlinx.coroutines.CancellationException

/** One target per transaction; GET-before-write is not a server CAS. */
internal class LayoutSync(private val store: StateStore, private val now: () -> Long, private val policy: Policy) {
    suspend fun check(platform: SlotPlatform, network: NetworkSession, observeOnly: Boolean, manual: Boolean = false): String {
        var s = LayoutRules.migrate(store.load())
        val t = now()
        val permit = network.manualPermit
        val reviewing = manual && observeOnly && s.globalBlock in setOf("SLOT_VERIFY_FAILED", "SLOT_INVALID", "INVALID_RESPONSE", "NETWORK_OR_STORAGE_ERROR")
        if (s.globalBlock != null && !reviewing) return s.globalBlock!!
        if (s.paused && !observeOnly) return "PAUSED"
        if (s.authBlocked) return "AUTH_PAUSED"
        // A person pressing "check" is never made to wait for the loop guard or local backoff,
        // but a server-mandated Retry-After (429) always holds.
        if (!s.requestAllowed(t, manual)) return "RATE_LIMITED"
        val initialAccount = s.accountContext
        val initialVersion = s.layout?.version
        val initialMode = s.mode
        val initialPause = s.paused
        fun live() {
            val actual = store.load()
            if (!network.stillCurrent() || actual.accountContext != initialAccount ||
                actual.layout?.version != initialVersion || actual.mode != initialMode || actual.paused != initialPause)
                throw ApiFailure("NETWORK_CHANGED")
        }
        fun persist(next: State) { store.save(next); s = next }
        fun finish(code: String): String {
            val events = if (s.status == code) s.events else (s.events + Event(t, code)).takeLast(200)
            persist(s.copy(status = code, events = events, failures = 0, lastSuccess = t))
            return code
        }
        fun slotStatus(number: Int, code: String) {
            val l = s.layout!!
            s = s.copy(layout = l.copy(slots = l.slots.map { if (it.number == number) it.copy(status = code) else it }))
        }
        var postAttempted = false
        try {
            // Migration and limiter are durable before all network work.
            persist(s.copy(lastCheck = t, nextAllowed = maxOf(s.serverDeadline(), t + policy.minIntervalMs), networkKey = network.key))
            live()
            val before = platform.query(); live()
            val layout = s.layout ?: SlotLayout()
            LayoutRules.validate(before, layout)
            if (reviewing) {
                // Do not clear the block or journal before both fresh reads and all protection checks pass.
                val second = platform.query(); live(); LayoutRules.validate(second, layout)
                if (before != second) throw ApiFailure("SLOT_VERIFY_FAILED")
            }
            s = s.copy(snapshot = before, observations = (s.observations + Observation(t, before.current, network.kind))
                .filter { it.time >= t - policy.retentionMs }.takeLast(500))
            // Recovery never repeats a POST, even when the current exit changed since intent.
            layout.pending?.let { p ->
                if (p.account != s.accountContext) throw ApiFailure("ACCOUNT_MISMATCH")
                val unchangedOthers = before.capacity == p.before.capacity &&
                    before.entries.filterNot { it.slot == p.slot }.toSet() == p.before.entries.filterNot { it.slot == p.slot }.toSet()
                if (!unchangedOthers && !(reviewing && reviewablePeers(p.before, before, p.slot, layout)))
                    throw ApiFailure("SLOT_VERIFY_FAILED")
                val occupant = before.entries.find { it.slot == p.slot }?.cidr
                val versionMatches = p.version == layout.version
                val code = when {
                    !versionMatches -> "PENDING_CONFIG_CHANGED"
                    occupant == p.candidate -> "RECOVERED_VERIFIED"
                    occupant == p.original -> "RECOVERED_NOT_APPLIED"
                    else -> "SLOT_CONFLICT"
                }
                val slots = if (reviewing) followPeers(layout.slots, p.before, before, p.slot, t) else layout.slots
                s = s.copy(paused = s.paused || reviewing, globalBlock = if (reviewing) null else s.globalBlock,
                    layout = layout.copy(pending = null, slots = slots.map {
                    if (it.number != p.slot) it else it.copy(
                        baseline = if (versionMatches && occupant == p.candidate) occupant else it.baseline,
                        authorized = versionMatches && code != "SLOT_CONFLICT" && it.authorized, status = code)
                }))
                return finish(code)
            }
            if (reviewing) {
                // No journal: no write of ours is unaccounted for. As in pending recovery, only non-empty replacements in
                // slots labelled as another device's or co-managed are accepted; with a phone updating its own slot,
                // demanding an untouched whitelist would keep this device blocked for good. Anything else stays blocked.
                val previous = store.load().snapshot
                if (previous != null && !reviewablePeers(previous, before, null, layout)) throw ApiFailure("SLOT_VERIFY_FAILED")
                s = s.copy(paused = true, globalBlock = null,
                    layout = if (previous == null) layout else layout.copy(slots = followPeers(layout.slots, previous, before, null, t)))
                return finish("PROTECTION_REVIEWED")
            }
            // v1 carried a narrower mobile authorization. Reconcile, never infer identity or unknown-WiFi rights.
            // What this device saw at its previous check; a difference means someone else wrote the slot.
            val previous = store.load().snapshot
            val updated = layout.slots.map { slot ->
                val occupant = before.entries.find { it.slot == slot.number }?.cidr
                val changedElsewhere = previous != null && previous.entries.find { it.slot == slot.number }?.cidr != occupant
                when {
                    slot.status == "LEGACY_RECONCILE" -> slot.copy(
                        authorized = occupant == slot.baseline || (slot.legacyPending != null && occupant == slot.legacyPending),
                        baseline = if (slot.legacyPending != null && occupant == slot.legacyPending) occupant else slot.baseline,
                        legacyPending = null, status = if (occupant == slot.baseline || (slot.legacyPending != null && occupant == slot.legacyPending)) "MIGRATED_CELLULAR_ONLY" else "SLOT_CONFLICT")
                    // A co-managed slot follows the other devices' writes instead of treating them as a takeover.
                    // An emptied slot is not a peer write (Po0 has no delete call), so it still conflicts.
                    slot.authorized && occupant != slot.baseline && slot.shared && occupant != null ->
                        slot.copy(baseline = occupant, status = "PEER_UPDATED", changedAt = t)
                    slot.authorized && occupant != slot.baseline -> slot.copy(authorized = false, status = "SLOT_CONFLICT", changedAt = t)
                    slot.writer == Writer.OTHER_DEVICE && changedElsewhere -> slot.copy(status = "PEER_UPDATED", changedAt = t)
                    slot.writer != Writer.LOCAL && changedElsewhere -> slot.copy(status = "EXTERNAL_CHANGE", changedAt = t)
                    else -> slot
                }
            }
            s = s.copy(layout = layout.copy(slots = updated))
            if (observeOnly || (s.mode == Mode.OBSERVE && permit == null))
                return finish(if (before.contains(before.current)) "PRESENT_CURRENT_CHECK" else "OBSERVED_MISSING")
            if (network.observedCidr != before.current) return finish("EGRESS_UNVERIFIED")
            val decision = if (permit != null) {
                if (permit.account != s.accountContext || permit.version != s.layout!!.version || permit.networkKey != network.key ||
                    now() > permit.expires || permit.before != before ||
                    (permit.wifi != null && !permit.wifi.sameIdentity(network.wifi))) return finish("MANUAL_EXPIRED")
                TargetDecision(s.layout!!.slots.find { it.number == permit.slot }, "MANUAL")
            } else SlotSelection.select(s.layout!!, network, now())
            decision.notice?.takeIf { ApNotice.ignored(it) !in s.layout!!.notices }?.let {
                s = s.copy(layout = s.layout!!.copy(notices = (s.layout!!.notices + it).toList().takeLast(100).toSet()))
            }
            val target = decision.slot ?: return finish(decision.code)
            fun blocked(code: String): String { slotStatus(target.number, code); return finish(code) }
            if (target.writer != Writer.LOCAL || target.purpose == SlotPurpose.RESERVED ||
                (!target.automatic && permit == null)) return blocked("SLOT_NOT_LOCAL")
            if (target.temporaryHold) return blocked("TEMPORARY_HOLD")
            if (!target.authorized) return blocked(if (target.status == "LEGACY_UNINITIALIZED") target.status else "SLOT_CONFLICT")
            val occupant = before.entries.find { it.slot == target.number }?.cidr
            if (occupant != target.baseline) return blocked("SLOT_CONFLICT")
            if (occupant == before.current) return blocked("SLOT_CURRENT")
            // Another device just wrote something else here: it is probably on a different network claiming the same
            // slot. Back off rather than flip the value back and forth; a later check decides again.
            // An empty slot holds nobody's value (Po0 has no delete call; a person cleared it), so there is nothing to flip.
            // Nor is a value this device itself just saw as its own exit: that peer shares this network, which has since
            // moved on (a router redialling twice in a minute), and waiting would only keep a dead /24 in the slot.
            // A person pressing "update" has decided; every other check below still applies.
            val ownRecentExit = s.observations.any { it.cidr == occupant && it.networkKind == network.kind && t - it.time in 0 until policy.sharedQuietMs }
            if (!manual && target.shared && occupant != null && !ownRecentExit && t - target.changedAt in 0 until policy.sharedQuietMs) return blocked("SHARED_RECENT")
            if (before.contains(before.current)) return blocked("COVERED_OTHER_SLOT")
            if (occupant == null && before.remaining == 0) return blocked("CAPACITY_FULL")
            live()
            val fresh = platform.query(); live(); LayoutRules.validate(fresh, s.layout!!)
            if (fresh != before) return blocked("CONCURRENT_CHANGE")
            if (!network.revalidate()) return blocked("EGRESS_UNVERIFIED")
            live()
            if (permit != null && now() > permit.expires) return blocked("MANUAL_EXPIRED")
            if (permit == null && target.purpose == SlotPurpose.FIXED &&
                network.wifi?.usable(now(), network.key) != true) return blocked("WIFI_UNAVAILABLE")
            val intent = SlotPending(s.accountContext, s.layout!!.version, target.number,
                if (permit != null) "MANUAL" else target.purpose.name, occupant, before.current, decision.identityId, before, t)
            persist(s.copy(layout = s.layout!!.copy(pending = intent)))
            live()
            postAttempted = true
            val post = platform.writeSlot(target.number)
            fun verify(result: Snapshot) {
                LayoutRules.validate(result, s.layout!!)
                if (result.capacity != before.capacity || result.current != before.current ||
                    result.entries.find { it.slot == target.number }?.cidr != before.current ||
                    result.entries.filterNot { it.slot == target.number }.toSet() != before.entries.filterNot { it.slot == target.number }.toSet())
                    throw ApiFailure("SLOT_VERIFY_FAILED")
            }
            verify(post); live()
            val after = platform.query(); verify(after); live()
            // If this save fails the durable intent remains. No in-memory success is published.
            s = s.copy(snapshot = after, layout = s.layout!!.copy(pending = null, slots = s.layout!!.slots.map {
                if (it.number == target.number) it.copy(baseline = before.current, status = "SLOT_UPDATED") else it
            }))
            return finish("SLOT_UPDATED")
        } catch (e: Exception) {
            // Never overwrite the durable journal with speculative post-write state.
            val durable = store.load()
            if (durable.accountContext != initialAccount || durable.layout?.version != initialVersion) return "NETWORK_CHANGED"
            val failure = e as? ApiFailure
            val code = if (e is CancellationException) "CANCELLED_NETWORK_OR_SETTINGS" else failure?.code ?: "NETWORK_OR_STORAGE_ERROR"
            // A malformed or inconsistent read stops this check before any write, so it retries with backoff like any
            // other failed request. Only around our own write does it mean something we cannot account for.
            val global = code in setOf("SLOT_VERIFY_FAILED", "ACCOUNT_MISMATCH", "NETWORK_OR_STORAGE_ERROR") ||
                (postAttempted && code in setOf("SLOT_INVALID", "INVALID_RESPONSE"))
            if (!postAttempted && code in setOf("CANCELLED_NETWORK_OR_SETTINGS", "NETWORK_CHANGED")) {
                // The network or settings moved on: not a failure. The next network's check must not wait out a backoff;
                // the loop-guard reservation made at the start still applies.
                runCatching { store.save(durable.copy(status = code)) }
                if (e is CancellationException) throw e
                return code
            }
            val count = durable.failures + 1
            val wait = maxOf(policy.minIntervalMs, failure?.retryAfterMs ?: 0,
                (60_000L * (1L shl (count - 1).coerceIn(0, 6))).coerceAtMost(policy.maxBackoffMs))
            runCatching { store.save(durable.copy(status = if (postAttempted && !global) "PENDING_REVIEW" else code,
                failures = count, nextAllowed = if (Long.MAX_VALUE - t < wait) Long.MAX_VALUE else t + wait,
                serverNotBefore = maxOf(durable.serverDeadline(), if (failure?.http == 429 || code == "HTTP_429")
                    if (Long.MAX_VALUE - t < wait) Long.MAX_VALUE else t + wait else 0),
                authBlocked = failure?.http in listOf(401, 403), globalBlock = if (global) code else durable.globalBlock)) }
            if (e is CancellationException) throw e
            return if (postAttempted && !global) "PENDING_REVIEW" else code
        }
    }

    /** Only explicitly designated peer/co-managed slots may change during explicit read-only review.
     * Unknown records (including unnumbered), missing records and capacity changes remain protected. */
    private fun reviewablePeers(old: Snapshot, current: Snapshot, target: Int?, layout: SlotLayout): Boolean {
        if (old.capacity != current.capacity) return false
        val a = old.entries.filterNot { target != null && it.slot == target }.toSet()
        val b = current.entries.filterNot { target != null && it.slot == target }.toSet()
        return ((a - b) + (b - a)).all { entry ->
            val number = entry.slot ?: return@all false
            val slot = layout.slots.find { it.number == number } ?: return@all false
            (slot.writer == Writer.OTHER_DEVICE || (slot.writer == Writer.LOCAL && slot.shared && slot.purpose == SlotPurpose.FIXED)) &&
                a.any { it.slot == number } && b.any { it.slot == number }
        }
    }

    /** Slots whose occupant changed while protected read as peer updates; co-managed ones take the new value as baseline. */
    private fun followPeers(slots: List<ManagedSlot>, old: Snapshot, current: Snapshot, target: Int?, t: Long) = slots.map {
        val occupantNow = current.entries.find { entry -> entry.slot == it.number }?.cidr
        val occupantThen = old.entries.find { entry -> entry.slot == it.number }?.cidr
        if (it.number == target || occupantNow == occupantThen) it else it.copy(status = "PEER_UPDATED", changedAt = t,
            baseline = if (it.writer == Writer.LOCAL && it.shared && it.authorized) occupantNow else it.baseline)
    }
}
