package app.fuckpo0jixian.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** The per-device fallback interval: stored, exported, and actually honoured at its shortest setting. */
class FallbackIntervalTest {
    @Test fun oldStateDecodesToTheDefaultAndValuesStayInRange() {
        val old = StateCodec.encode(State()).replace(Regex(",\"fallbackMinutes\":\\d+"), "")
        assertFalse("fallbackMinutes" in old)
        assertEquals(10, StateCodec.decode(old).fallbackMinutes)
        assertEquals(2, StateCodec.decode(StateCodec.encode(State(fallbackMinutes = 2))).fallbackMinutes)
        assertEquals(59, StateCodec.decode(StateCodec.encode(State(fallbackMinutes = 99))).fallbackMinutes)
        assertEquals(2, StateCodec.decode(StateCodec.encode(State(fallbackMinutes = 0))).fallbackMinutes)
        assertEquals(120_000, State(fallbackMinutes = 2).fallbackMs)
        assertEquals(59 * 60_000L, State(fallbackMinutes = 500).fallbackMs)
    }

    @Test fun exportCarriesTheInterval() {
        val text = RedactedExport.build(State(fallbackMinutes = 4), "android", "0.8.0", 1_000)
        assertTrue("\"fallbackMinutes\": 4" in text)
        assertFalse("lifecycle" in text)
        val withLog = RedactedExport.build(State(), "android", "0.8.2", 1_000, lifecycle = listOf("2026-09-28T03:10:00Z EXIT LOW_MEMORY"))
        assertTrue("\"lifecycle\": [" in withLog && "EXIT LOW_MEMORY" in withLog)
        PeerImport.parse(withLog) // an export carrying the log stays importable on other devices
    }

    @Test fun everyTickObservesTheExitAgainUnlessAProbeJustRan() {
        val p = Policy()
        val s = State(probeStatus = "PROBE_OBSERVED", nextProbeAllowed = 0, domesticExit = DomesticExit("203.0.113.9", 0, "n"))
        // The previous tick's result is still "fresh" (under 2 minutes) but a tick must compare again.
        assertTrue(LocalCheck.fallbackProbe(s, "n", 119_000, p))
        assertTrue(LocalCheck.fallbackProbe(s, "n", 120_000, p))
        assertFalse(LocalCheck.fallbackProbe(s, "n", 5_000, p), "a check observed it seconds ago")
        assertTrue(LocalCheck.fallbackProbe(s, "other", 5_000, p), "different network")
        assertFalse(LocalCheck.fallbackProbe(s.copy(probeStatus = "PROBE_FAILED", nextProbeAllowed = 200_000), "n", 150_000, p), "backing off")
    }

    /** Po0 plus an exit that can move; counts every whitelist query. */
    private class Po0 : DemoPlatform(), SlotPlatform {
        var queries = 0
        override suspend fun query(): Snapshot { queries++; return super.query() }
        override suspend fun writeSlot(slot: Int) = replaceDemoSlot(slot)
    }

    /**
     * Two minutes, two hours: the exit is compared on every tick, Po0 is asked only when the exit moved and for the
     * hourly refresh. This is the loop both apps run on each fallback tick.
     */
    @Test fun twoMinuteIntervalComparesEveryTickButRarelyAsksPo0() = runTest {
        var clock = 10_000_000L
        val current = Cidr("203.0.113.0/24")
        val po0 = Po0().apply { this.current = current; entries = listOf(Entry(current, 0)); capacity = 5 }
        val store = MemoryStore(State(paused = false, mode = Mode.AUTO, fallbackMinutes = 2, accountContext = "a",
            domesticExit = DomesticExit("203.0.113.9", clock, "n"), probeStatus = "PROBE_OBSERVED",
            layout = SlotLayout(slots = listOf(ManagedSlot(0, "家", SlotPurpose.MOBILE, Writer.LOCAL, automatic = true,
                authorized = true, baseline = current)))))
        val engine = Engine(store, { clock })
        val session = NetworkSession("n", "cellular", false) { true }
        suspend fun remote() = engine.check(po0, session.copy(observedCidr = store.load().domesticExit?.cidr))
        assertEquals("SLOT_CURRENT", remote())
        var probes = 0; var remoteChecks = 0
        var exitIp = "203.0.113.9"
        repeat(60) { tick ->
            clock += store.load().fallbackMs
            if (tick == 20) { exitIp = "198.51.100.7"; po0.current = Cidr("198.51.100.0/24") }
            if (LocalCheck.fallbackProbe(store.load(), "n", clock)) {
                probes++
                store.save(store.load().copy(domesticExit = DomesticExit(exitIp, clock, "n"), probeStatus = "PROBE_OBSERVED",
                    nextProbeAllowed = clock + Policy().probeIntervalMs))
            }
            if (!LocalCheck.canSkipRemote(store.load(), store.load().domesticExit, "n", clock)) {
                remoteChecks++
                val code = remote()
                // Never a cached answer: the slot engine always asks Po0 when the loop decides to.
                assertNotEquals("CACHED_OBSERVATION", code)
            }
        }
        assertEquals(60, probes, "every 2-minute tick compared the exit")
        // Tick 20 (exit moved) plus one hourly refresh after each update: 2 hours → about 3 requests, never 60.
        assertTrue(remoteChecks in 2..4, "Po0 asked $remoteChecks times")
        assertTrue(po0.queries < 10, "Po0 queried ${po0.queries} times")
        assertEquals(Cidr("198.51.100.0/24"), po0.entries.single { it.slot == 0 }.cidr)
    }

    @Test fun brokenProbeFallsBackToTheDefaultCadenceNotEveryTick() = runTest {
        var clock = 10_000_000L
        val current = Cidr("203.0.113.0/24")
        val po0 = Po0().apply { this.current = current; entries = listOf(Entry(current, 0)) }
        val store = MemoryStore(State(paused = false, mode = Mode.OBSERVE, fallbackMinutes = 2,
            layout = SlotLayout(slots = listOf(ManagedSlot(0)))))
        val engine = Engine(store, { clock })
        val session = NetworkSession("n", "wifi", false) { true }
        assertEquals("PRESENT_CURRENT_CHECK", engine.check(po0, session))
        var remoteChecks = 0
        repeat(30) { // one hour, probe always failing
            clock += store.load().fallbackMs
            if (LocalCheck.fallbackProbe(store.load(), "n", clock))
                store.save(store.load().copy(probeStatus = "PROBE_FAILED", nextProbeAllowed = clock + 60_000))
            if (!LocalCheck.canSkipRemote(store.load(), store.load().domesticExit, "n", clock)) { remoteChecks++; engine.check(po0, session) }
        }
        assertEquals(6, remoteChecks, "every 10 minutes, as before the setting existed")
    }

    @Test fun capacityCellsFollowSlotNumbers() {
        val a = Cidr("203.0.113.0/24"); val b = Cidr("198.51.100.0/24"); val c = Cidr("192.0.2.0/24"); val d = Cidr("192.0.3.0/24")
        val layout = SlotLayout(slots = listOf(ManagedSlot(0, purpose = SlotPurpose.FIXED), ManagedSlot(1, purpose = SlotPurpose.MOBILE),
            ManagedSlot(2, purpose = SlotPurpose.FIXED)))
        val snap = Snapshot(a, listOf(Entry(a, 0), Entry(b, 1), Entry(c, 2)), 5)
        // Slot 1 fixed, 2 mobile, 3 fixed stays blue-green-blue; before it was sorted into blue-blue-green.
        assertEquals(listOf(SlotPurpose.FIXED, SlotPurpose.MOBILE, SlotPurpose.FIXED, null, null), CapacityBar.cells(snap, layout))
        val gaps = Snapshot(a, listOf(Entry(a, 0), Entry(c, 3), Entry(d)), 5)
        assertEquals(listOf(SlotPurpose.FIXED, null, null, SlotPurpose.RESERVED, SlotPurpose.RESERVED), CapacityBar.cells(gaps, layout))
        assertEquals(listOf(SlotPurpose.RESERVED, null), CapacityBar.cells(Snapshot(a, listOf(Entry(a, 0)), 2), null))
    }
}
