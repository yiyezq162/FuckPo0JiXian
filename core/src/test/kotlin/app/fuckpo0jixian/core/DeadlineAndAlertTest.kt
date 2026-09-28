package app.fuckpo0jixian.core

import kotlin.test.*

class DeadlineAndAlertTest {
    private val now = 1_000_000L

    @Test fun retryAfterIsCapped() {
        assertEquals(120_000, Wire.retryAfter("120", 0))
        assertEquals(Wire.MAX_RETRY_AFTER_MS, Wire.retryAfter("999999999", 0))
        assertEquals(Wire.MAX_RETRY_AFTER_MS, Wire.retryAfter("Fri, 01 Jan 2100 00:00:00 GMT", 0))
    }

    @Test fun loadingBoundsOldWaitsAndLiftsOnlyReadBlocksWithoutPending() {
        val s = State(serverNotBefore = Long.MAX_VALUE, nextAllowed = Long.MAX_VALUE, nextProbeAllowed = now + 5,
            globalBlock = "INVALID_RESPONSE", layout = SlotLayout()).loaded(now)
        assertEquals(now + Wire.MAX_RETRY_AFTER_MS, s.serverNotBefore)
        assertEquals(now + Wire.MAX_RETRY_AFTER_MS, s.nextAllowed)
        assertEquals(now + 5, s.nextProbeAllowed)
        assertNull(s.globalBlock)
        assertNull(State(globalBlock = "SLOT_INVALID").loaded(now).globalBlock)
        val cidr = Cidr("192.0.2.0/24")
        val pending = SlotPending("a", 1, 0, "FIXED", null, cidr, null, Snapshot(cidr, emptyList(), 5), now)
        assertEquals("INVALID_RESPONSE", State(globalBlock = "INVALID_RESPONSE", layout = SlotLayout(pending = pending)).loaded(now).globalBlock)
        assertEquals("SLOT_VERIFY_FAILED", State(globalBlock = "SLOT_VERIFY_FAILED").loaded(now).globalBlock)
        assertEquals("STORAGE_RECOVERY_REQUIRED", State(globalBlock = "STORAGE_RECOVERY_REQUIRED").loaded(now).globalBlock)
    }

    @Test fun enteringAGlobalBlockAlwaysAlertsAndReadErrorsOnlyWhenTheyLast() {
        val s = State(layout = SlotLayout())
        assertEquals(Attention("NETWORK_OR_STORAGE_ERROR", null),
            Alerts.after(s, "NETWORK_OR_STORAGE_ERROR", s.copy(globalBlock = "NETWORK_OR_STORAGE_ERROR")))
        assertNull(Alerts.after(s.copy(globalBlock = "ACCOUNT_MISMATCH"), "ACCOUNT_MISMATCH", s.copy(globalBlock = "ACCOUNT_MISMATCH")))
        assertNull(Alerts.after(s, "INVALID_RESPONSE", s.copy(failures = Alerts.LASTING_FAILURES - 1)))
        assertEquals(Attention("INVALID_RESPONSE", "NOTIFIED:INVALID_RESPONSE:1"),
            Alerts.after(s, "INVALID_RESPONSE", s.copy(failures = Alerts.LASTING_FAILURES)))
        assertEquals("NOTIFIED:SLOT_CONFLICT:1", Alerts.after(s, "SLOT_CONFLICT", s)?.marker)
        assertNull(Alerts.after(s, "SHARED_RECENT", s), "backing off from a peer write needs nobody's attention")
        assertNull(Alerts.after(s, "PRESENT_CURRENT_CHECK", s))
    }
}
