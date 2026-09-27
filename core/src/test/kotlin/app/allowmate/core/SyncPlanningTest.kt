package app.allowmate.core

import kotlin.test.*
class SyncPlanningTest {
    @Test fun switchingWithinQuotaSchedulesAtQuotaInsteadOfThirtyMinuteFallback() {
        val s = State(paused = false, nextAllowed = 120_000)
        assertEquals(100_000, SyncPlanning.delayMs(s, 20_000, 15_000))
        assertTrue(SyncPlanning.needsFollowUp("RATE_LIMITED", s))
    }
    @Test fun stableDeadlineAndServerRetryAfterBothHold() {
        val s = State(paused = false, nextAllowed = 7_200_000)
        assertEquals(7_190_000, SyncPlanning.delayMs(s, 10_000, 15_000))
        assertEquals(15_000, SyncPlanning.delayMs(s.copy(nextAllowed = 0), 10_000, 15_000))
    }
    @Test fun pausedAuthBlockedAndOfflineDoNotSchedule() {
        assertNull(SyncPlanning.delayMs(State(), 0, 0))
        assertNull(SyncPlanning.delayMs(State(paused = false, authBlocked = true), 0, 0))
        assertNull(SyncPlanning.delayMs(State(paused = false), 0, Long.MAX_VALUE))
        assertFalse(SyncPlanning.needsFollowUp("HTTP_401", State(authBlocked = true)))
        assertFalse(SyncPlanning.needsFollowUp("CACHED_OBSERVATION", State(paused = false, failures = 1, status = "HTTP_500")))
    }
    @Test fun duplicateCallbacksDoNotExtendStabilityDeadline() {
        val d = Debouncer(); assertTrue(d.changed("n", 0)); assertFalse(d.changed("n", 14_000))
        assertEquals(1_000, d.remaining(14_000)); assertEquals(0, d.remaining(15_000))
        d.changed("other", 15_000); assertEquals(15_000, d.remaining(15_000))
    }
    @Test fun repeatedPollingAndRapidFlappingDoNotManufactureCommonNetwork() {
        val a = Cidr("203.0.113.0/24"); val b = Cidr("192.0.2.0/24")
        val rows = (1L..20L).map { Observation(it * 1000, if (it % 2 == 0L) a else b, "test") }
        assertTrue(NetworkHistory.summarize(rows, 30_000).none { it.common })
        assertTrue(NetworkHistory.summarize(rows, 30_000).all { it.visits == 1 })
    }
    @Test fun repeatedIndependentReturnsAndMultiDayUseAreSuggestionsOnly() {
        val a = Cidr("203.0.113.0/24"); val b = Cidr("192.0.2.0/24")
        val rows = listOf(Observation(0, a, "wifi"), Observation(1_000, b, "mobile"), Observation(1_800_000, a, "wifi"),
            Observation(1_801_000, b, "mobile"), Observation(3_600_000, a, "wifi"))
        assertTrue(NetworkHistory.summarize(rows, 3_600_000).first { it.cidr == a }.common)
        val days = listOf(Observation(0, b, "wifi"), Observation(86_400_000, b, "wifi"))
        assertTrue(NetworkHistory.summarize(days, 86_400_000).single().common)
        assertFalse(NetworkHistory.summarize(listOf(Observation(86_399_000, a, "x"), Observation(86_401_000, a, "x")), 86_401_000).single().common)
        assertTrue(NetworkHistory.summarize(rows, 9 * 86_400_000L).isEmpty())
    }
}
