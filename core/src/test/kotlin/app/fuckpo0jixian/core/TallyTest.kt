package app.fuckpo0jixian.core

import kotlin.test.*

/** Lifetime counters: what counts, what does not, and that they survive a round trip and bad files. */
class TallyTest {
    @Test fun countsConfirmedWritesAndFinishedChecksOnly() {
        var t = Tally()
        for (code in listOf("BUSY", "CANCELLED_NETWORK_OR_SETTINGS", "NETWORK_CHANGED")) t = t.afterCheck(code, 5)
        assertEquals(Tally(), t)
        t = t.afterCheck("LOCAL_UNCHANGED", 10).afterCheck("SLOT_CURRENT", 20).afterCheck("SLOT_UPDATED", 30)
            .afterCheck("RECOVERED_VERIFIED", 40).afterCheck("PENDING_REVIEW", 50)
        assertEquals(5, t.checks)
        assertEquals(2, t.ipUpdates)
        assertEquals(10, t.since)
        assertEquals(40, t.lastUpdate)
        assertEquals(1, t.afterNetworkChange(60).networkChanges)
        assertEquals(10, t.afterNetworkChange(60).since)
    }

    @Test fun roundTripsAndToleratesBadInput() {
        val t = Tally(3, 120, 40, 1_000, 2_000)
        assertEquals(t, Tally.decode(Tally.encode(t)))
        assertEquals(Tally(), Tally.decode(null))
        assertEquals(Tally(), Tally.decode("not json"))
        assertEquals(0, Tally.decode("""{"ipUpdates":-4}""").ipUpdates)
    }

    @Test fun storeWritesOnlyChanges() {
        var file: String? = null
        var writes = 0
        val store = TallyStore({ file }) { file = it; writes++ }
        store.update { it.afterCheck("BUSY", 1) }
        assertEquals(0, writes)
        store.update { it.afterCheck("SLOT_UPDATED", 1) }
        assertEquals(1, writes)
        assertEquals(1, TallyStore({ file }) {}.flow.value.ipUpdates)
    }

    @Test fun compactCounts() {
        assertEquals("9999", compactCount(9_999))
        assertEquals("1.2 万", compactCount(12_345))
        assertEquals("3 万", compactCount(30_000))
    }
}
