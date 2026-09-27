package app.fuckpo0jixian.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Several devices share one token; each manages its own slots, fixed slots may be co-managed. */
class MultiDeviceTest {
    private val home = Cidr("192.0.2.0/24")
    private val newHome = Cidr("198.18.1.0/24")
    private val office = Cidr("198.51.100.0/24")
    private val phone = Cidr("203.0.113.0/24")
    private var clock = 1_000_000L
    private val ap = "02:11:22:33:44:01"
    private fun wifi() = WifiObservation("n", "Home", ap, WifiSecurity.WPA2, clock)
    private fun layout(shared: Boolean) = SlotLayout(slots = listOf(
        ManagedSlot(0, "家", SlotPurpose.FIXED, Writer.LOCAL, "home", true, authorized = true, baseline = home, shared = shared),
        ManagedSlot(1, "公司", SlotPurpose.FIXED, Writer.OTHER_DEVICE, owner = "Windows"),
        ManagedSlot(2, "手机", SlotPurpose.MOBILE, Writer.OTHER_DEVICE, owner = "手机"),
        ManagedSlot(3, "外部"), ManagedSlot(4)
    ), identities = listOf(NetworkIdentity("home", "家", "Home", setOf(AuthorizedAp(ap, WifiSecurity.WPA2)))))
    private fun state(shared: Boolean) = State(mode = Mode.AUTO, paused = false, layout = layout(shared), accountContext = "a",
        snapshot = snap(home, listOf(Entry(home, 0), Entry(office, 1), Entry(phone, 2))))
    private fun snap(current: Cidr, entries: List<Entry>) = Snapshot(current, entries, 5)
    private class Fake(var snap: Snapshot) : SlotPlatform {
        override val capabilities = Capabilities()
        val writes = mutableListOf<Int>()
        override suspend fun query() = snap
        override suspend fun addIfUnchanged(expected: Snapshot): Snapshot = error("unused")
        override suspend fun writeSlot(slot: Int): Snapshot {
            writes += slot; snap = snap.copy(entries = snap.entries.filterNot { it.slot == slot } + Entry(snap.current, slot)); return snap
        }
    }
    private suspend fun check(st: StateStore, f: Fake, w: WifiObservation? = wifi(), kind: String = if (w == null) "cellular" else "wifi") =
        Engine(st, { clock }).check(f, NetworkSession("n", kind, true, f.snap.current, w) { true })
    private fun slot(st: MemoryStore, n: Int) = st.state.layout!!.slots.first { it.number == n }

    @Test fun sharedFixedSlotFollowsAnotherDeviceEvenWhenAway() = runTest {
        val st = MemoryStore(state(shared = true))
        // Away on cellular; the desktop at home already moved slot 0 to the new home exit.
        val f = Fake(snap(phone, listOf(Entry(newHome, 0), Entry(office, 1), Entry(phone, 2))))
        check(st, f, null)
        assertTrue(slot(st, 0).authorized); assertEquals(newHome, slot(st, 0).baseline)
        assertEquals("PEER_UPDATED", slot(st, 0).status); assertEquals(clock, slot(st, 0).changedAt)
        assertTrue(f.writes.isEmpty())
    }

    @Test fun unsharedFixedSlotStillTreatsAnotherWriterAsConflict() = runTest {
        val st = MemoryStore(state(shared = false))
        val f = Fake(snap(phone, listOf(Entry(newHome, 0), Entry(office, 1), Entry(phone, 2))))
        check(st, f, null)
        assertFalse(slot(st, 0).authorized); assertEquals("SLOT_CONFLICT", slot(st, 0).status)
    }

    @Test fun sharedSlotBacksOffAfterADifferentPeerWriteThenUpdates() = runTest {
        val st = MemoryStore(state(shared = true))
        val other = Cidr("198.18.7.0/24")
        val f = Fake(snap(newHome, listOf(Entry(other, 0), Entry(office, 1), Entry(phone, 2))))
        assertEquals("SHARED_RECENT", check(st, f)); assertTrue(f.writes.isEmpty())
        clock += Policy().sharedQuietMs
        assertEquals("SLOT_UPDATED", check(st, f)); assertEquals(listOf(0), f.writes)
        assertEquals(newHome, slot(st, 0).baseline)
    }

    @Test fun sharedSlotAlreadyCurrentAfterPeerWriteNeedsNoPost() = runTest {
        val st = MemoryStore(state(shared = true))
        val f = Fake(snap(newHome, listOf(Entry(newHome, 0), Entry(office, 1), Entry(phone, 2))))
        assertEquals("SLOT_CURRENT", check(st, f)); assertTrue(f.writes.isEmpty()); assertTrue(slot(st, 0).authorized)
    }

    @Test fun emptiedSharedSlotIsNotAPeerWrite() = runTest {
        val st = MemoryStore(state(shared = true))
        val f = Fake(snap(phone, listOf(Entry(office, 1), Entry(phone, 2))))
        check(st, f, null)
        assertFalse(slot(st, 0).authorized)
    }

    /** Slot cleared on the Po0 website, then authorized again: fill it now, no "another device just wrote" wait. */
    @Test fun reauthorizedEmptiedSharedSlotIsFilledRightAway() = runTest {
        val st = MemoryStore(state(shared = true))
        val f = Fake(snap(home, listOf(Entry(office, 1), Entry(phone, 2))))
        check(st, f)
        assertFalse(slot(st, 0).authorized); assertEquals(clock, slot(st, 0).changedAt)
        st.state = LayoutRules.saveSlot(st.state, slot(st, 0), true, clock).copy(nextAllowed = 0)
        assertEquals(0, slot(st, 0).changedAt)
        assertEquals("SLOT_UPDATED", check(st, f)); assertEquals(listOf(0), f.writes)
    }

    /** Even without re-confirming, an empty shared slot has no peer value to protect. */
    @Test fun emptySharedSlotIsNotHeldByTheQuietPeriod() = runTest {
        val l = layout(shared = true)
        val st = MemoryStore(state(shared = true).copy(layout = l.copy(slots = l.slots.map {
            if (it.number == 0) it.copy(baseline = null, changedAt = clock) else it })))
        val f = Fake(snap(home, listOf(Entry(office, 1), Entry(phone, 2))))
        assertEquals("SLOT_UPDATED", check(st, f)); assertEquals(listOf(0), f.writes)
    }

    @Test fun otherDeviceUpdatesAreLabelledAndExternalOnesWarn() = runTest {
        val st = MemoryStore(state(shared = false))
        val f = Fake(snap(home, listOf(Entry(home, 0), Entry(office, 1), Entry(Cidr("203.0.114.0/24"), 2), Entry(Cidr("203.0.115.0/24"), 3))))
        check(st, f)
        assertEquals("PEER_UPDATED", slot(st, 2).status); assertEquals(clock, slot(st, 2).changedAt)
        assertEquals("EXTERNAL_CHANGE", slot(st, 3).status)
        assertEquals(0, slot(st, 1).changedAt, "an unchanged slot keeps no change time")
    }

    @Test fun enablingSharedNeedsExplicitAuthorization() {
        val s = state(shared = false).copy(lastCheck = clock)
        val candidate = s.layout!!.slots.first().copy(shared = true)
        assertEquals("AUTHORIZATION_REQUIRED", assertFailsWith<IllegalArgumentException> { LayoutRules.saveSlot(s, candidate, false, clock) }.message)
        assertTrue(LayoutRules.saveSlot(s, candidate, true, clock).layout!!.slots.first { it.number == 0 }.shared)
        val mobile = s.layout!!.slots.first().copy(purpose = SlotPurpose.MOBILE, shared = true)
        assertFalse(LayoutRules.saveSlot(s, mobile, true, clock).layout!!.slots.first { it.number == 0 }.shared, "only fixed slots can be shared")
    }

    @Test fun desktopGatewayIdentityMatchesLikeAWifiAp() = runTest {
        val gateway = "a4:11:22:33:44:55"
        val l = layout(false).copy(identities = listOf(NetworkIdentity("home", "家", "gw:192.168.5.1", setOf(AuthorizedAp(gateway, WifiSecurity.GATEWAY)))))
        val st = MemoryStore(state(false).copy(layout = l))
        val f = Fake(snap(newHome, listOf(Entry(home, 0), Entry(office, 1), Entry(phone, 2))))
        val lan = WifiObservation("n", "gw:192.168.5.1", gateway, WifiSecurity.GATEWAY, clock)
        assertEquals("SLOT_UPDATED", check(st, f, lan, "lan")); assertEquals(listOf(0), f.writes)
        clock += 120_000; f.snap = f.snap.copy(current = Cidr("198.18.9.0/24"))
        assertEquals("UNKNOWN_WIFI", check(st, f, lan.copy(bssid = "a4:11:22:33:44:66", time = clock), "lan"))
    }

    @Test fun exportIsRedactedAndRoundTripsIntoPeerLabels() {
        val s = state(shared = true).copy(deviceName = "Mac", domesticExit = DomesticExit("192.0.2.77", clock, "n"),
            events = listOf(Event(clock, "SLOT_UPDATED")), observations = listOf(Observation(clock, home, "wifi")))
        val text = RedactedExport.build(s, "macos", "0.7.0", clock)
        listOf("192.0.2.", "198.51.100.", "203.0.113.", "Home\"", ap, "\"a\"", "pgnfw_").forEach { assertFalse(it in text, "leaked $it") }
        assertTrue("192.0.*.0/24" in text)

        val phoneState = State(deviceName = "手机", snapshot = s.snapshot, layout = SlotLayout(slots = listOf(
            ManagedSlot(2, "这台手机", SlotPurpose.MOBILE, Writer.LOCAL, automatic = true, authorized = true, baseline = phone))))
        val result = PeerImport.apply(phoneState, PeerImport.parse(text))
        val slots = result.state.layout!!.slots.associateBy { it.number }
        assertEquals(Writer.OTHER_DEVICE, slots.getValue(0).writer); assertEquals("Mac", slots.getValue(0).owner)
        assertFalse(slots.getValue(0).authorized); assertEquals("家", slots.getValue(0).name)
        assertEquals("Windows", slots.getValue(1).owner)
        assertEquals(Writer.LOCAL, slots.getValue(2).writer, "own slots are never relabelled"); assertTrue(slots.getValue(2).authorized)
        assertEquals(Writer.EXTERNAL, slots.getValue(3).writer)
        assertEquals(listOf(0, 1, 3), result.changed)
        assertEquals("IMPORT_SAME_DEVICE", assertFailsWith<IllegalArgumentException> {
            PeerImport.apply(phoneState.copy(deviceName = "mac"), PeerImport.parse(text)) }.message)
        assertEquals("IMPORT_INVALID", assertFailsWith<IllegalArgumentException> { PeerImport.parse("{}") }.message)
    }

    @Test fun newFieldsSurviveTheStateCodecAndOldFilesDefault() {
        val s = state(shared = true).copy(deviceName = "Windows", layout = layout(true).let { l ->
            l.copy(slots = l.slots.map { if (it.number == 1) it.copy(changedAt = 42) else it }) })
        val back = StateCodec.decode(StateCodec.encode(s))
        assertEquals(s.layout, back.layout); assertEquals("Windows", back.deviceName)
        val old = StateCodec.encode(s).replace(Regex(",\"owner\":\"[^\"]*\",\"shared\":(true|false),\"changedAt\":\\d+"), "")
            .replace(",\"deviceName\":\"Windows\"", "")
        val legacy = StateCodec.decode(old)
        assertEquals("", legacy.deviceName); assertFalse(legacy.layout!!.slots.first().shared)
    }
}
