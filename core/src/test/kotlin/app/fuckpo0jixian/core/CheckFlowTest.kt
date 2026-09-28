package app.fuckpo0jixian.core

import kotlin.test.*

class CheckFlowTest {
    private val now = 10_000_000L
    private val home = Cidr("192.0.2.0/24")
    private val exit = Cidr("198.51.100.0/24")
    private val snap = Snapshot(exit, listOf(Entry(home, 0)), 5)
    private val fixed = ManagedSlot(0, "家", SlotPurpose.FIXED, Writer.LOCAL, authorized = true, baseline = home)
    private val state = State(mode = Mode.AUTO, paused = false, snapshot = snap, layout = SlotLayout(slots = listOf(fixed, ManagedSlot(1))))

    @Test fun confirmationWaitsOnlyForTheServer() {
        val permit = CheckFlow.permit("OBSERVED_MISSING", state, 0, "n", null, exit, true, now)!!
        assertEquals(0, CheckFlow.confirmWait(state.copy(nextAllowed = now + 3_600_000, failures = 6), permit, now),
            "local backoff never holds a manual update")
        assertEquals(60_000, CheckFlow.confirmWait(state.copy(serverNotBefore = now + 60_000), permit, now))
        assertNull(CheckFlow.confirmWait(state.copy(serverNotBefore = now + CheckFlow.PERMIT_MS + 1), permit, now),
            "a wait longer than the confirmation would only let it expire")
    }

    @Test fun manualConfirmationOnlyForAnAuthorizedLocalFixedSlotOnTheSameExit() {
        val permit = CheckFlow.permit("OBSERVED_MISSING", state, 0, "n", null, exit, true, now)!!
        assertEquals(now + CheckFlow.PERMIT_MS, permit.expires); assertEquals(snap, permit.before)
        assertNull(CheckFlow.permit("OBSERVED_MISSING", state, 1, "n", null, exit, true, now), "not this device's slot")
        assertNull(CheckFlow.permit("OBSERVED_MISSING", state, 0, "n", null, home, true, now), "exit disagrees with Po0")
        assertNull(CheckFlow.permit("OBSERVED_MISSING", state, 0, "n", null, exit, false, now), "network moved on")
        assertNull(CheckFlow.permit("OBSERVED_MISSING", state.copy(paused = true), 0, "n", null, exit, true, now))
        assertNull(CheckFlow.permit("HTTP_500", state, 0, "n", null, exit, true, now))
    }

    @Test fun onlyChecksThatMayWriteAskForTheExit() {
        assertTrue(CheckFlow.mayWrite(state, false, false, false, null, now))
        assertFalse(CheckFlow.mayWrite(state, true, true, false, null, now), "connection check")
        assertTrue(CheckFlow.mayWrite(state, true, true, true, null, now), "manual preview")
        assertFalse(CheckFlow.mayWrite(state.copy(mode = Mode.OBSERVE), false, false, false, null, now))
        assertFalse(CheckFlow.mayWrite(state.copy(globalBlock = "SLOT_VERIFY_FAILED"), false, true, false, null, now))
        assertFalse(CheckFlow.mayWrite(state.copy(serverNotBefore = now + 1), false, true, false, null, now))
    }

    @Test fun onlyAFreshObservationOnThisNetworkIsEvidence() {
        val observed = state.copy(domesticExit = DomesticExit("198.51.100.7", now - 1_000, "n"), probeStatus = "PROBE_OBSERVED")
        assertEquals(exit, CheckFlow.freshExit(observed, "n", now))
        assertNull(CheckFlow.freshExit(observed, "other", now))
        assertNull(CheckFlow.freshExit(observed, "n", now + Policy().freshnessMs))
        assertNull(CheckFlow.freshExit(observed.copy(probeStatus = "PROBE_FAILED"), "n", now))
    }
}
