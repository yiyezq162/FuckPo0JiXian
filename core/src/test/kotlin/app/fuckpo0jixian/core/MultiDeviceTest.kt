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
        ManagedSlot(0, "家", SlotPurpose.FIXED, Writer.LOCAL, listOf("home"), true, authorized = true, baseline = home, shared = shared),
        ManagedSlot(1, "公司", SlotPurpose.FIXED, Writer.OTHER_DEVICE, owner = "Windows"),
        ManagedSlot(2, "手机", SlotPurpose.MOBILE, Writer.OTHER_DEVICE, owner = "手机"),
        ManagedSlot(3, "外部"), ManagedSlot(4)
    ), identities = listOf(NetworkIdentity("home", "家", "Home", setOf(AuthorizedAp(ap, WifiSecurity.WPA2)))))
    private fun state(shared: Boolean) = State(mode = Mode.AUTO, paused = false, layout = layout(shared), accountContext = "a",
        snapshot = snap(home, listOf(Entry(home, 0), Entry(office, 1), Entry(phone, 2))))
    private fun snap(current: Cidr, entries: List<Entry>) = Snapshot(current, entries, 5)
    private class Fake(var snap: Snapshot) : SlotPlatform {
        val writes = mutableListOf<Int>()
        override suspend fun query() = snap
        override suspend fun writeSlot(slot: Int): Snapshot {
            writes += slot; snap = snap.copy(entries = snap.entries.filterNot { it.slot == slot } + Entry(snap.current, slot)); return snap
        }
    }
    private suspend fun check(st: StateStore, f: Fake, w: WifiObservation? = wifi(), kind: String = if (w == null) "cellular" else "wifi") =
        Engine(st, { clock }).check(f, NetworkSession("n", kind, f.snap.current, w) { true })
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

    /** The router redialled twice within a minute: the phone wrote the middle exit, which this device saw itself. */
    @Test fun peerWriteOfThisNetworksPreviousExitIsReplacedRightAway() = runTest {
        val st = MemoryStore(state(shared = true))
        val middle = Cidr("198.18.7.0/24")
        val f = Fake(snap(middle, listOf(Entry(home, 0), Entry(office, 1), Entry(phone, 2))))
        // This device sees the middle exit but does not write it (say its Wi-Fi was not confirmed yet).
        check(st, f, null, kind = "wifi")
        assertTrue(f.writes.isEmpty())
        clock += 30_000; st.state = st.state.copy(nextAllowed = 0)
        f.snap = snap(newHome, listOf(Entry(middle, 0), Entry(office, 1), Entry(phone, 2)))
        assertEquals("SLOT_UPDATED", check(st, f)); assertEquals(listOf(0), f.writes)
        assertEquals(newHome, slot(st, 0).baseline)
    }

    @Test fun manualUpdateIsNotHeldByTheQuietPeriod() = runTest {
        val st = MemoryStore(state(shared = true))
        val f = Fake(snap(newHome, listOf(Entry(Cidr("198.18.7.0/24"), 0), Entry(office, 1), Entry(phone, 2))))
        assertEquals("SHARED_RECENT", check(st, f)); assertTrue(f.writes.isEmpty())
        clock += 30_000
        assertEquals("SLOT_UPDATED", Engine(st, { clock }).check(f, NetworkSession("n", "wifi", f.snap.current, wifi()) { true }, manual = true))
        assertEquals(listOf(0), f.writes)
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

    @Test fun addingUnknownWifiToAnAuthorizedMobileSlotAsksAgain() {
        val mobile = ManagedSlot(2, "手机", SlotPurpose.MOBILE, Writer.LOCAL, automatic = true, authorized = true, baseline = phone)
        val s = state(shared = false).let { it.copy(lastCheck = clock, layout = it.layout!!.copy(slots = it.layout!!.slots.map { p -> if (p.number == 2) mobile else p })) }
        val candidate = mobile.copy(allowUnknownWifi = true)
        // The editor hides the switch only when the save would not need it; otherwise saving could never succeed.
        assertTrue(LayoutRules.needsAuthorization(mobile, candidate))
        assertEquals("AUTHORIZATION_REQUIRED", assertFailsWith<IllegalArgumentException> { LayoutRules.saveSlot(s, candidate, false, clock) }.message)
        assertTrue(LayoutRules.saveSlot(s, candidate, true, clock).layout!!.slots.first { it.number == 2 }.allowUnknownWifi)
        assertFalse(LayoutRules.needsAuthorization(mobile, mobile.copy(name = "新名字", temporaryHold = true)))
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

    @Test fun shareExportKeepsTheDivisionAndRoundTripsIntoPeerLabels() {
        val s = state(shared = true).copy(deviceName = "Mac", domesticExit = DomesticExit("192.0.2.77", clock, "n"),
            events = listOf(Event(clock, "SLOT_UPDATED")), observations = listOf(Observation(clock, home, "wifi")))
        val text = ShareExport.build(s, "macos", "0.7.0", clock)
        // Only for the user's own devices: ranges, Wi-Fi name and access point stay; the host part and account do not.
        listOf("192.0.2.0/24", "198.51.100.0/24", "\"Home\"", ap, "\"Mac\"").forEach { assertTrue(it in text, "missing $it") }
        listOf("192.0.2.77", "\"a\"", "pgnfw_").forEach { assertFalse(it in text, "leaked $it") }

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
        assertEquals("IMPORT_INVALID", assertFailsWith<IllegalArgumentException> {
            PeerImport.parse(DebugExport.build(s, mapOf("platform" to "macos"), clock)) }.message, "debug info is not a division")
    }

    @Test fun olderRedactedExportsStillImport() {
        val legacy = """{"format":"fuckpo0jixian-export","version":1,"device":{"name":"旧 Mac"},
            "slots":[{"number":1,"name":"公司","purpose":"FIXED","writer":"LOCAL","owner":""}]}"""
        val peer = PeerImport.parse(legacy)
        assertEquals("旧 Mac", peer.device); assertEquals(Writer.LOCAL, peer.slots.single().writer)
    }

    @Test fun debugExportMasksAddressesAndWifiButKeepsNames() {
        val s = state(shared = true).copy(deviceName = "Mac", domesticExit = DomesticExit("192.0.2.77", clock, "n"),
            events = listOf(Event(clock, "SLOT_UPDATED")), observations = listOf(Observation(clock, home, "wifi")))
        val text = DebugExport.build(s, mapOf("platform" to "android", "app" to "0.8.6", "rom" to "PKU110_16.0.3.500(CN01)"), clock,
            logs = mapOf("lifecycle" to listOf("2026-09-28T03:10:00Z NET wifi addrs=192.168.31.20 v6=2408:8207:1a2b:3c4d::/64 gw=a4:11:22:33:44:55")))
        listOf("192.0.2.", "198.51.100.", "203.0.113.", "192.168.31", "\"Home\"", ap, "a4:11:22:33:44:55", "1a2b:3c4d", "pgnfw_")
            .forEach { assertFalse(it in text, "leaked $it") }
        listOf("192.0.*.*/24", "192.0.*.*", "192.168.*.*", "2408:8207:*", "03:10:00", "\"Mac\"", "\"家\"", "0.8.6", "SLOT_UPDATED",
            "PKU110_16.0.3.500(CN01)")
            .forEach { assertTrue(it in text, "missing $it") }
        val tag = Redact.tag("Home", s.accountContext)!!
        assertTrue(tag in text); assertEquals(tag, Redact.tag("Home", s.accountContext)); assertNotEquals(tag, Redact.tag("Home", "other"))
    }

    @Test fun stateDiffNamesWhatChanged() {
        val before = state(shared = true)
        val after = before.copy(status = "SLOT_UPDATED", snapshot = snap(newHome, listOf(Entry(newHome, 0), Entry(office, 1), Entry(phone, 2))),
            layout = before.layout!!.copy(slots = before.layout!!.slots.map { if (it.number == 0) it.copy(baseline = newHome) else it }),
            events = listOf(Event(clock, "SLOT_UPDATED")), runtimeMode = RuntimeMode.MODULE)
        val lines = StateDiff.describe(before, after)
        assertTrue("RUNTIME MODULE" in lines)
        assertTrue("STATUS NOT_CHECKED -> SLOT_UPDATED" in lines)
        assertTrue("PO0_SEES 198.18.1.0/24" in lines)
        assertTrue("WHITELIST slot=0 192.0.2.0/24 -> 198.18.1.0/24" in lines)
        assertTrue("SLOT 0 baseline=198.18.1.0/24" in lines)
        assertTrue("EVENT SLOT_UPDATED" in lines)
        assertTrue(StateDiff.describe(after, after).isEmpty())
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
