package app.allowmate.core

import kotlinx.coroutines.CancellationException

/** One target per transaction; GET-before-write is not a server CAS. */
internal class LayoutSync(private val store: StateStore, private val now: () -> Long, private val policy: Policy) {
    suspend fun check(platform: SlotPlatform, network: NetworkSession, observeOnly: Boolean, manual: Boolean = false): String {
        var s = LayoutRules.migrate(store.load())
        val t = now()
        val permit = network.manualPermit
        if (s.globalBlock != null) return s.globalBlock!!
        if (s.paused && !observeOnly) return "PAUSED"
        if (s.authBlocked) return "AUTH_PAUSED"
        // A person pressing "check" is never made to wait for the loop guard or local backoff,
        // but a server-mandated Retry-After (429) always holds.
        if (t < s.nextAllowed && !(manual && s.status != "HTTP_429")) return "RATE_LIMITED"
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
            persist(s.copy(lastCheck = t, nextAllowed = t + policy.minIntervalMs, networkKey = network.key))
            live()
            val before = platform.query(); live()
            val layout = s.layout ?: SlotLayout()
            LayoutRules.validate(before, layout)
            s = s.copy(snapshot = before, observations = (s.observations + Observation(t, before.current, network.kind))
                .filter { it.time >= t - policy.retentionMs }.takeLast(500))
            // Recovery never repeats a POST, even when the current exit changed since intent.
            layout.pending?.let { p ->
                if (p.account != s.accountContext) throw ApiFailure("ACCOUNT_MISMATCH")
                val unchangedOthers = before.capacity == p.before.capacity &&
                    before.entries.filterNot { it.slot == p.slot }.toSet() == p.before.entries.filterNot { it.slot == p.slot }.toSet()
                if (!unchangedOthers) throw ApiFailure("SLOT_VERIFY_FAILED")
                val occupant = before.entries.find { it.slot == p.slot }?.cidr
                val versionMatches = p.version == layout.version
                val code = when {
                    !versionMatches -> "PENDING_CONFIG_CHANGED"
                    occupant == p.candidate -> "RECOVERED_VERIFIED"
                    occupant == p.original -> "RECOVERED_NOT_APPLIED"
                    else -> "SLOT_CONFLICT"
                }
                s = s.copy(layout = layout.copy(pending = null, slots = layout.slots.map {
                    if (it.number != p.slot) it else it.copy(
                        baseline = if (versionMatches && occupant == p.candidate) occupant else it.baseline,
                        authorized = versionMatches && code != "SLOT_CONFLICT" && it.authorized, status = code)
                }))
                return finish(code)
            }
            // v1 carried a narrower mobile authorization. Reconcile, never infer identity or unknown-WiFi rights.
            val updated = layout.slots.map { slot ->
                val occupant = before.entries.find { it.slot == slot.number }?.cidr
                when {
                    slot.status == "LEGACY_RECONCILE" -> slot.copy(
                        authorized = occupant == slot.baseline || (slot.legacyPending != null && occupant == slot.legacyPending),
                        baseline = if (slot.legacyPending != null && occupant == slot.legacyPending) occupant else slot.baseline,
                        legacyPending = null, status = if (occupant == slot.baseline || (slot.legacyPending != null && occupant == slot.legacyPending)) "MIGRATED_CELLULAR_ONLY" else "SLOT_CONFLICT")
                    slot.authorized && occupant != slot.baseline -> slot.copy(authorized = false, status = "SLOT_CONFLICT")
                    slot.writer != Writer.LOCAL && s.snapshot != store.load().snapshot &&
                        store.load().snapshot?.entries?.find { it.slot == slot.number }?.cidr != occupant -> slot.copy(status = "EXTERNAL_CHANGE")
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
            decision.notice?.let { s = s.copy(layout = s.layout!!.copy(notices = (s.layout!!.notices + it).toList().takeLast(100).toSet())) }
            val target = decision.slot ?: return finish(decision.code)
            fun blocked(code: String): String { slotStatus(target.number, code); return finish(code) }
            if (target.writer != Writer.LOCAL || target.purpose == SlotPurpose.RESERVED ||
                (!target.automatic && permit == null)) return blocked("SLOT_NOT_LOCAL")
            if (target.temporaryHold) return blocked("TEMPORARY_HOLD")
            if (!target.authorized) return blocked(if (target.status == "LEGACY_UNINITIALIZED") target.status else "SLOT_CONFLICT")
            val occupant = before.entries.find { it.slot == target.number }?.cidr
            if (occupant != target.baseline) return blocked("SLOT_CONFLICT")
            if (occupant == before.current) return blocked("SLOT_CURRENT")
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
                if (permit != null) "MANUAL" else target.purpose.name, occupant, before.current, target.identityId, before, t)
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
            val global = code in setOf("SLOT_INVALID", "INVALID_RESPONSE", "SLOT_VERIFY_FAILED", "ACCOUNT_MISMATCH", "NETWORK_OR_STORAGE_ERROR")
            val count = durable.failures + 1
            val wait = maxOf(policy.minIntervalMs, failure?.retryAfterMs ?: 0,
                (60_000L * (1L shl (count - 1).coerceIn(0, 6))).coerceAtMost(policy.maxBackoffMs))
            runCatching { store.save(durable.copy(status = if (postAttempted && !global) "PENDING_REVIEW" else code,
                failures = count, nextAllowed = if (Long.MAX_VALUE - t < wait) Long.MAX_VALUE else t + wait,
                authBlocked = failure?.http in listOf(401, 403), globalBlock = if (global) code else durable.globalBlock)) }
            if (e is CancellationException) throw e
            return if (postAttempted && !global) "PENDING_REVIEW" else code
        }
    }
}
