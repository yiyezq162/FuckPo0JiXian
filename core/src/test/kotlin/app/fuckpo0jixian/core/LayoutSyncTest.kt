package app.fuckpo0jixian.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LayoutSyncTest {
    private val home = Cidr("192.0.2.0/24")
    private val office = Cidr("198.51.100.0/24")
    private val mobile = Cidr("203.0.113.0/24")
    private val newHome = Cidr("198.18.1.0/24")
    private var clock = 1_000_000L
    private fun wifi(ssid: String = "Home", ap: String = "02:11:22:33:44:01", security: WifiSecurity = WifiSecurity.WPA2) =
        WifiObservation("n", ssid, ap, security, clock)
    private fun layout() = SlotLayout(slots = listOf(
        ManagedSlot(0, "Home", SlotPurpose.FIXED, Writer.LOCAL, "home", true, authorized = true, baseline = home),
        ManagedSlot(1, "Office", SlotPurpose.FIXED, Writer.LOCAL, "office", true, authorized = true, baseline = office),
        ManagedSlot(2, "Phone", SlotPurpose.MOBILE, Writer.LOCAL, automatic = true, authorized = true, baseline = mobile),
        ManagedSlot(3, "Other", SlotPurpose.MOBILE, Writer.OTHER_DEVICE), ManagedSlot(4, "Reserve")
    ), identities = listOf(NetworkIdentity("home", "Home", "Home", setOf(
        AuthorizedAp("02:11:22:33:44:01", WifiSecurity.WPA2), AuthorizedAp("02:11:22:33:44:09", WifiSecurity.WPA2))),
        NetworkIdentity("office", "Office", "Office", setOf(AuthorizedAp("02:11:22:33:44:02", WifiSecurity.WPA3)))))
    private fun state() = State(mode = Mode.AUTO, paused = false, layout = layout(), accountContext = "test-account")
    private class Fake(var snap: Snapshot) : SlotPlatform {
        override val capabilities = Capabilities()
        var writes = mutableListOf<Int>()
        var queries = 0
        var onQuery: ((Int) -> Unit)? = null
        var afterWrite: (() -> Unit)? = null
        var drop = false
        override suspend fun query(): Snapshot { queries++; onQuery?.invoke(queries); return snap }
        override suspend fun addIfUnchanged(expected: Snapshot): Snapshot = error("slotless prohibited")
        override suspend fun writeSlot(slot: Int): Snapshot {
            writes += slot
            snap = snap.copy(entries = snap.entries.filterNot { it.slot == slot } + Entry(snap.current, slot))
            afterWrite?.invoke()
            if (drop) throw ApiFailure("NETWORK_TLS_OR_RESPONSE_ERROR")
            return snap
        }
    }
    private fun fake(current: Cidr = newHome, full: Boolean = false) = Fake(Snapshot(current,
        listOf(Entry(home, 0), Entry(office, 1), Entry(mobile, 2)) + if (full) listOf(Entry(Cidr("203.0.114.0/24"), 3), Entry(Cidr("203.0.115.0/24"))) else emptyList(), 5))
    private fun session(f: Fake, w: WifiObservation? = wifi(), live: () -> Boolean = { true }) =
        NetworkSession("n", if (w == null) "cellular" else "wifi", true, f.snap.current, w, stillCurrent = live)
    private suspend fun check(st: StateStore, f: Fake, w: WifiObservation? = wifi()) = Engine(st, { clock }).check(f, session(f, w))
    @Test fun manualCheckSkipsLoopGuardButNotServerRetryAfter() = runTest {
        val st = MemoryStore(state()); val f = fake(current = home)
        assertEquals("SLOT_CURRENT", check(st, f))
        assertEquals("RATE_LIMITED", check(st, f), "automatic triggers keep the loop guard")
        assertEquals("SLOT_CURRENT", Engine(st, { clock }).check(f, session(f), manual = true))
        st.save(st.load().copy(status = "HTTP_429", nextAllowed = clock + 600_000))
        assertEquals("RATE_LIMITED", Engine(st, { clock }).check(f, session(f), manual = true))
        assertEquals(0, f.writes.size)
    }
    @Test fun twoFixedNetworksAndCellularOnlyChangeTheirOwnSlots() = runTest {
        val st = MemoryStore(state()); val f = fake(full = true)
        assertEquals("SLOT_UPDATED", check(st, f)); assertEquals(listOf(0), f.writes)
        assertEquals(office, f.snap.entries.find { it.slot == 1 }!!.cidr)
        clock += 120_000; f.snap = f.snap.copy(current = Cidr("198.18.2.0/24"))
        assertEquals("SLOT_UPDATED", check(st, f, wifi("Office", "02:11:22:33:44:02", WifiSecurity.WPA3)))
        clock += 120_000; f.snap = f.snap.copy(current = Cidr("198.18.3.0/24"))
        assertEquals("SLOT_UPDATED", check(st, f, null)); assertEquals(listOf(0, 1, 2), f.writes)
        assertEquals(5, f.snap.entries.size)
    }
    @Test fun mobileCommuteReplacesOneSlotAndLimiterIsGlobal() = runTest {
        val st = MemoryStore(state()); val f = fake()
        repeat(3) { i ->
            f.snap = f.snap.copy(current = Cidr("198.18.${i + 1}.0/24"))
            assertEquals("SLOT_UPDATED", check(st, f, null))
            assertEquals("RATE_LIMITED", check(st, f, null)); clock += 120_000
        }
        assertEquals(listOf(2, 2, 2), f.writes); assertEquals(3, f.snap.entries.size)
    }
    @Test fun sameNameAndSameExitDoNotGrantApRightsButMeshDoes() = runTest {
        val st = MemoryStore(state()); val f = fake(home)
        assertEquals("UNKNOWN_WIFI", check(st, f, wifi(ap = "02:11:22:33:44:88")))
        assertTrue(st.state.layout!!.notices.single().startsWith("AP_CONFIRM:")); assertTrue(f.writes.isEmpty())
        clock += 120_000
        check(st, f, wifi(ap = "02:11:22:33:44:88")); assertEquals(1, st.state.layout!!.notices.size)
        clock += 120_000; f.snap = f.snap.copy(current = newHome)
        assertEquals("SLOT_UPDATED", check(st, f, wifi(ap = "02:11:22:33:44:09")))
    }
    @Test fun ambiguousIdentityBlocksEvenWhenMobileUnknownWifiAllowed() = runTest {
        val l = layout(); val st = MemoryStore(state().copy(layout = l.copy(identities = l.identities + l.identities.first().copy(id = "other"))))
        val f = fake(); assertEquals("IDENTITY_AMBIGUOUS", check(st, f)); assertTrue(f.writes.isEmpty())
    }
    @Test fun revokedPlaceholderStaleAndDowngradedEvidenceCannotReuseBinding() = runTest {
        listOf(wifi().copy(available = false), wifi().copy(ssid = "<unknown ssid>"),
            wifi().copy(bssid = "02:00:00:00:00:00"), wifi().copy(time = clock - 60_001),
            wifi(security = WifiSecurity.OPEN), wifi(security = WifiSecurity.UNKNOWN)).forEach { w ->
            val f = fake(); assertEquals("WIFI_UNAVAILABLE", check(MemoryStore(state()), f, w)); assertTrue(f.writes.isEmpty())
        }
        val l = layout(); val st = MemoryStore(state().copy(layout = l.copy(identities = l.identities.map {
            if (it.id == "home") it.copy(aps = setOf(AuthorizedAp("02:11:22:33:44:01", WifiSecurity.WPA3))) else it })))
        assertEquals("WIFI_SECURITY_CHANGED", check(st, fake()))
    }
    @Test fun sameHandleRoamAndConfigurationOrAccountChangeCancelBeforePost() = runTest {
        repeat(4) { variation ->
            val st = MemoryStore(state()); val f = fake(); var current = true
            f.onQuery = { if (it == 2) when (variation) {
                0 -> current = false
                1 -> st.state = st.state.copy(layout = st.state.layout!!.copy(version = 2))
                2 -> st.state = st.state.copy(accountContext = "new-account")
                3 -> st.state = st.state.copy(paused = true)
            } }
            assertEquals("NETWORK_CHANGED", Engine(st, { clock }).check(f, session(f, live = { current })))
            assertTrue(f.writes.isEmpty())
        }
    }
    @Test fun singleSlotConflictDoesNotPauseIndependentSlotAndHistoryNeverReclaims() = runTest {
        val st = MemoryStore(state().copy(observations = listOf(Observation(clock, newHome, "wifi"))))
        val f = fake(); f.snap = f.snap.copy(entries = f.snap.entries.map { if (it.slot == 0) it.copy(cidr = newHome) else it })
        assertEquals("SLOT_CONFLICT", check(st, f)); assertFalse(st.state.paused)
        clock += 120_000; f.snap = f.snap.copy(current = Cidr("198.18.8.0/24"))
        assertEquals("SLOT_UPDATED", check(st, f, null)); assertEquals(listOf(2), f.writes)
        assertFalse(st.state.layout!!.slots.first { it.number == 0 }.authorized)
    }
    @Test fun reservedOtherExternalUnknownAndUnnumberedRemainProtected() = runTest {
        for (writer in Writer.entries) {
            val st = MemoryStore(state().copy(layout = layout().copy(slots = layout().slots.map {
                if (it.number == 0) it.copy(writer = writer, purpose = if (writer == Writer.LOCAL) SlotPurpose.RESERVED else it.purpose) else it })))
            val f = fake(full = true); check(st, f); assertTrue(f.writes.isEmpty())
        }
        val st = MemoryStore(state().copy(layout = layout().copy(slots = layout().slots.filterNot { it.number == 2 })))
        val f = fake(full = true); check(st, f, null); assertTrue(f.writes.isEmpty()); assertEquals(1, f.snap.entries.count { it.slot == null })
    }
    @Test fun emptySlotAtCapacityCannotAddButFullOwnedSlotReplaces() = runTest {
        val f = fake(full = true); assertEquals("SLOT_UPDATED", check(MemoryStore(state()), f))
        val st = MemoryStore(state().copy(layout = layout().copy(slots = layout().slots.filterNot { it.number == 4 }.map { if (it.number == 2) it.copy(number = 4, baseline = null) else it })))
        val f2 = fake(full = true); assertEquals("CAPACITY_FULL", check(st, f2, null)); assertTrue(f2.writes.isEmpty())
    }
    @Test fun coveredInAnotherSlotNeverPostsOrClaimsSuccess() = runTest {
        val st = MemoryStore(state()); val f = fake(mobile)
        repeat(3) { assertEquals("COVERED_OTHER_SLOT", check(st, f)); clock += 120_000 }
        assertTrue(f.writes.isEmpty()); assertEquals(home, st.state.layout!!.slots.first().baseline)
    }
    @Test fun lostReplyRestartReconcilesCandidateAndDoesNotReplayOldExit() = runTest {
        val st = MemoryStore(state()); val f = fake(); f.drop = true
        assertEquals("PENDING_REVIEW", check(st, f)); assertNotNull(st.state.layout!!.pending)
        val restart = MemoryStore(StateCodec.decode(StateCodec.encode(st.state)))
        clock += 120_000; f.drop = false; f.snap = f.snap.copy(current = Cidr("198.18.9.0/24"))
        assertEquals("RECOVERED_VERIFIED", check(restart, f)); assertEquals(1, f.writes.size)
        assertEquals(newHome, restart.state.layout!!.slots.first().baseline)
        clock += 120_000; assertEquals("SLOT_UPDATED", check(restart, f)); assertEquals(2, f.writes.size)
    }
    @Test fun pendingSaveFailurePreventsPostAndCommitFailureRetainsIntent() = runTest {
        for (failAt in listOf(2, 3)) {
            val memory = MemoryStore(state()); var saves = 0
            val store = object : StateStore {
                override fun load() = memory.load()
                override fun save(state: State) { saves++; if (saves >= failAt) error("disk full"); memory.save(state) }
            }
            val f = fake(); Engine(store, { clock }).check(f, session(f))
            assertEquals(if (failAt == 2) 0 else 1, f.writes.size)
            if (failAt == 3) assertNotNull(memory.state.layout!!.pending)
        }
    }
    @Test fun configChangedPendingDoesNotGrantNewBaseline() = runTest {
        val st = MemoryStore(state()); val f = fake(); f.drop = true; check(st, f)
        st.state = st.state.copy(layout = st.state.layout!!.copy(version = 3))
        clock += 120_000; f.drop = false
        assertEquals("PENDING_CONFIG_CHANGED", check(st, f)); assertEquals(1, f.writes.size)
        assertFalse(st.state.layout!!.slots.first().authorized)
    }
    @Test fun nonTargetLossGloballyBlocksAndConcurrentChangeNeverPosts() = runTest {
        val st = MemoryStore(state()); val f = fake(); f.afterWrite = { f.snap = f.snap.copy(entries = f.snap.entries.filterNot { it.slot == 1 }) }
        assertEquals("SLOT_VERIFY_FAILED", check(st, f)); assertEquals("SLOT_VERIFY_FAILED", st.state.globalBlock)
        clock += 120_000; assertEquals("SLOT_VERIFY_FAILED", check(st, f, null)); assertEquals(1, f.writes.size)
        val f2 = fake(); f2.onQuery = { if (it == 2) f2.snap = f2.snap.copy(current = mobile) }
        assertEquals("CONCURRENT_CHANGE", check(MemoryStore(state()), f2)); assertTrue(f2.writes.isEmpty())
    }
    @Test fun peerChangeAfterPostCanBeReviewedWithoutReplayButNeverIgnored() = runTest {
        val st = MemoryStore(state()); val f = fake(full = true)
        f.afterWrite = { f.snap = f.snap.copy(entries = f.snap.entries.map {
            if (it.slot == 3) it.copy(cidr = Cidr("198.18.30.0/24")) else it }) }
        assertEquals("SLOT_VERIFY_FAILED", check(st, f))
        val pending = st.state.layout!!.pending
        assertNotNull(pending)
        val restart = MemoryStore(StateCodec.decode(StateCodec.encode(st.state)))
        clock += 120_000
        assertEquals("SLOT_VERIFY_FAILED", check(restart, f))
        val count = f.queries
        assertEquals("RECOVERED_VERIFIED", Engine(restart, { clock }).check(f, session(f), manual = true, observeOnly = true))
        assertEquals(count + 2, f.queries)
        assertNull(restart.state.globalBlock); assertNull(restart.state.layout!!.pending)
        assertTrue(restart.state.paused)
        assertEquals("PEER_UPDATED", restart.state.layout!!.slots.first { it.number == 3 }.status)
        assertEquals(1, f.writes.size)
        assertEquals("PAUSED", check(restart, f))
    }
    @Test fun reviewRetainsJournalForLossUnknownChangesAccountAndInvalidStructure() = runTest {
        repeat(6) { fault ->
            val st = MemoryStore(state()); val f = fake(full = true)
            val old = f.snap
            val pending = SlotPending(if (fault == 3) "wrong-account" else st.state.accountContext,
                1, 0, "FIXED", home, newHome, "home", old, clock)
            st.save(st.state.copy(globalBlock = "SLOT_VERIFY_FAILED", layout = layout().copy(pending = pending)))
            f.snap = when (fault) {
                0 -> old.copy(entries = old.entries.filterNot { it.slot == 3 })
                1 -> old.copy(entries = old.entries.map { if (it.slot == null) it.copy(cidr = Cidr("198.18.50.0/24")) else it })
                2 -> old.copy(entries = old.entries.map { if (it.slot == 1) it.copy(cidr = Cidr("198.18.50.0/24")) else it })
                4 -> old.copy(entries = old.entries.map { if (it.slot == 1) it.copy(slot = 0) else it })
                else -> old
            }
            if (fault == 5) f.onQuery = { if (it == 2) f.snap = old.copy(current = mobile) }
            Engine(st, { clock }).check(f, session(f), manual = true, observeOnly = true)
            assertNotNull(st.state.globalBlock, "fault $fault")
            assertEquals(pending, st.state.layout!!.pending, "fault $fault")
            assertTrue(f.writes.isEmpty())
        }
    }
    @Test fun failedReviewCommitKeepsDurablePendingAndBlock() = runTest {
        val f = fake(); val st = MemoryStore(state())
        val pending = SlotPending(st.state.accountContext, 1, 0, "FIXED", home, newHome, "home", f.snap, clock)
        st.save(st.state.copy(globalBlock = "NETWORK_OR_STORAGE_ERROR", layout = layout().copy(pending = pending)))
        val store = object : StateStore {
            override fun load() = st.load()
            override fun save(state: State) {
                if (state.globalBlock == null) error("commit failed")
                st.save(state)
            }
        }
        Engine(store, { clock }).check(f, session(f), manual = true, observeOnly = true)
        assertEquals(pending, st.state.layout!!.pending); assertNotNull(st.state.globalBlock)
        assertTrue(f.writes.isEmpty())
    }
    @Test fun review429CannotLoseServerDeadlineOnStatusChangeOrRestart() = runTest {
        val f = fake(); val st = MemoryStore(state())
        val pending = SlotPending(st.state.accountContext, 1, 0, "FIXED", home, newHome, "home", f.snap, clock)
        st.save(st.state.copy(globalBlock = "SLOT_VERIFY_FAILED", layout = layout().copy(pending = pending)))
        f.onQuery = { throw ApiFailure("HTTP_429", 429, 7_200_000) }
        assertEquals("HTTP_429", Engine(st, { clock }).check(f, session(f), manual = true, observeOnly = true))
        val deadline = st.state.serverNotBefore
        val restarted = MemoryStore(StateCodec.decode(StateCodec.encode(st.state)).copy(status = "NOT_CHECKED", nextAllowed = 0))
        clock += 3_600_001
        assertEquals("RATE_LIMITED", Engine(restarted, { clock }).check(f, session(f), manual = true, observeOnly = true))
        assertEquals(1, f.queries); assertEquals(pending, restarted.state.layout!!.pending)
        f.onQuery = null; clock = deadline
        assertEquals("RECOVERED_NOT_APPLIED", Engine(restarted, { clock }).check(f, session(f), manual = true, observeOnly = true))
        assertEquals(deadline, restarted.state.serverNotBefore)
        assertTrue(restarted.state.paused); assertTrue(f.writes.isEmpty())
    }
    @Test fun v1NonDefaultSlotsUninitializedAndPendingMigrateWithoutWrites() = runTest {
        val old = State(mode = Mode.OBSERVE, paused = true, slotPlan = SlotPlan(home, 3, 4, false, mobile, newHome))
        val v1 = StateCodec.encode(old).replace("\"version\":2", "\"version\":1")
        val migrated = StateCodec.decode(v1)
        assertEquals(listOf(3, 4), migrated.layout!!.slots.map { it.number })
        assertEquals("LEGACY_UNINITIALIZED", migrated.layout!!.slots.first().status)
        assertEquals(newHome, migrated.layout!!.slots.last().legacyPending)
        assertTrue(migrated.layout!!.identities.isEmpty()); assertTrue(migrated.paused)
        assertEquals(migrated, StateCodec.decode(StateCodec.encode(migrated)))
        assertFails { StateCodec.decode(v1.replace("\"version\":1", "\"version\":999")) }
    }
    @Test fun manualConfirmationCannotSurviveSessionAccountVersionOrExitChange() = runTest {
        repeat(4) { variation ->
            val st = MemoryStore(state()); val f = fake()
            val permit = ManualPermit(if (variation == 0) "wrong" else st.state.accountContext,
                if (variation == 1) 99 else 1, 0, if (variation == 2) "other" else "n", wifi(),
                if (variation == 3) f.snap.copy(current = mobile) else f.snap, clock + 60_000)
            assertEquals("MANUAL_EXPIRED", Engine(st, { clock }).check(f, session(f).copy(manualPermit = permit)))
            assertTrue(f.writes.isEmpty())
        }
    }
    @Test fun sameNetworkLaterProbeFindsNewExitAndServerChangesWithoutSuccessCache() = runTest {
        val st = MemoryStore(state()); val f = fake(home)
        assertEquals("SLOT_CURRENT", check(st, f))
        clock += 120_000; f.snap = f.snap.copy(current = newHome)
        assertEquals("SLOT_UPDATED", check(st, f)); assertEquals(listOf(0), f.writes)
        clock += 120_000; f.snap = f.snap.copy(entries = f.snap.entries.map { if (it.slot == 0) it.copy(cidr = home) else it })
        assertEquals("SLOT_CONFLICT", check(st, f)); assertEquals(1, f.writes.size)
    }
    @Test fun authorizationMustBeExplicitAndBindingNeverWritesOrSilentlyAddsAp() {
        val s = state().copy(snapshot = fake().snap, lastCheck = clock)
        assertFails { LayoutRules.saveSlot(s, ManagedSlot(3, purpose = SlotPurpose.MOBILE, writer = Writer.LOCAL, automatic = true), false, clock) }
        val bound = LayoutRules.bind(s, 0, wifi(), clock, "Custom name", false)
        assertEquals("Custom name", bound.layout!!.identities.last().name)
        assertEquals(s.snapshot, bound.snapshot)
        assertFails { LayoutRules.bind(bound, 0, wifi("Changed SSID"), clock, "", true) }
        val cleared = State(); assertNull(cleared.layout); assertNotEquals(s.accountContext, cleared.accountContext)
    }
    @Test fun manualWithUnavailableIdentityWorksOnlyForAuthorizedBaselineAndFreshExit() = runTest {
        val st = MemoryStore(state().copy(mode = Mode.OBSERVE)); val f = fake()
        val permit = ManualPermit(st.state.accountContext, 1, 0, "n", null, f.snap, clock + 60_000)
        val session = session(f, null).copy(manualPermit = permit)
        assertEquals("SLOT_UPDATED", Engine(st, { clock }).check(f, session))
        assertEquals(listOf(0), f.writes)
        val st2 = MemoryStore(state()); val f2 = fake()
        assertEquals("EGRESS_UNVERIFIED", Engine(st2, { clock }).check(f2, session(f2).copy(revalidate = { false })))
        assertTrue(f2.writes.isEmpty())
    }
    @Test fun cellularAndExplicitUnknownWifiPoliciesDoNotLearnNetworkIdentity() = runTest {
        val l = layout().copy(slots = layout().slots.map { if (it.number == 2) it.copy(allowUnknownWifi = true) else it })
        val st = MemoryStore(state().copy(layout = l)); val f = fake()
        assertEquals("SLOT_UPDATED", check(st, f, wifi("Cafe", "02:aa:bb:cc:dd:ee")))
        assertEquals(listOf(2), f.writes); assertEquals(l.identities, st.state.layout!!.identities)
    }
    @Test fun crashBeforePostRecoversWithoutReplayAndStaleManualPermitCannotWrite() = runTest {
        val f = fake(); val s = state(); val intent = SlotPending(s.accountContext, 1, 0, "FIXED", home, newHome, "home", f.snap, clock)
        val st = MemoryStore(s.copy(layout = s.layout!!.copy(pending = intent)))
        assertEquals("RECOVERED_NOT_APPLIED", check(st, f)); assertTrue(f.writes.isEmpty()); assertNull(st.state.layout!!.pending)
        clock += 120_000
        val permit = ManualPermit(s.accountContext, 1, 0, "n", wifi(), f.snap, clock - 1)
        assertEquals("MANUAL_EXPIRED", Engine(st, { clock }).check(f, session(f).copy(manualPermit = permit)))
        assertTrue(f.writes.isEmpty())
    }
    @Test fun invalidSlotStructureAndMismatchedAccountPendingAreGlobal() = runTest {
        val st = MemoryStore(state()); val f = fake()
        f.snap = f.snap.copy(entries = listOf(Entry(home, 0), Entry(office, 0)))
        assertEquals("SLOT_INVALID", check(st, f)); assertEquals("SLOT_INVALID", st.state.globalBlock)
        val p = SlotPending("other-account", 1, 0, "FIXED", home, newHome, "home", fake().snap, clock)
        val st2 = MemoryStore(state().copy(layout = layout().copy(pending = p)))
        assertEquals("ACCOUNT_MISMATCH", check(st2, fake())); assertEquals("ACCOUNT_MISMATCH", st2.state.globalBlock)
    }
    @Test fun noteOnlyEditsNeedNoNewGrantAndReappearingHistoryDoesNotClearConflict() {
        val s = state().copy(snapshot = fake().snap, lastCheck = 1)
        val edited = LayoutRules.saveSlot(s, s.layout!!.slots.first().copy(name = "New note"), false, clock)
        assertEquals(home, edited.layout!!.slots.last().baseline)
        assertEquals(s.snapshot, edited.snapshot)
        assertFails { LayoutRules.saveSlot(s, s.layout!!.slots.first(), true, clock) }
    }
}
