package app.fuckpo0jixian.core

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
        val d = Debouncer(15_000); assertTrue(d.changed("n", 0)); assertFalse(d.changed("n", 14_000))
        assertEquals(1_000, d.remaining(14_000)); assertEquals(0, d.remaining(15_000))
        d.changed("other", 15_000); assertEquals(15_000, d.remaining(15_000))
    }
    @Test fun fallbackSkipsPo0OnlyWhenLocalExitMatchesRecentResultOnSameNetwork() {
        val now = 10_000_000L
        val current = Cidr("203.0.113.0/24")
        val base = State(paused = false, mode = Mode.AUTO, status = "SLOT_CURRENT", lastSuccess = now - 60_000, networkKey = "n",
            snapshot = Snapshot(current, listOf(Entry(current, 0)), 5))
        val exit = DomesticExit("203.0.113.9", now - 1_000, "n")
        assertTrue(LocalCheck.canSkipRemote(base, exit, "n", now))
        assertFalse(LocalCheck.canSkipRemote(base, DomesticExit("198.51.100.9", now - 1_000, "n"), "n", now), "exit changed")
        assertFalse(LocalCheck.canSkipRemote(base, DomesticExit("203.0.113.9", now - 1_000, "m"), "m", now), "other network")
        assertFalse(LocalCheck.canSkipRemote(base, DomesticExit("203.0.113.9", now - 120_000, "n"), "n", now), "stale probe")
        assertFalse(LocalCheck.canSkipRemote(base, null, "n", now), "no probe")
        assertFalse(LocalCheck.canSkipRemote(base.copy(lastSuccess = now - 3_600_000), exit, "n", now), "hourly Po0 refresh")
        assertFalse(LocalCheck.canSkipRemote(base.copy(lastSuccess = 0), exit, "n", now), "configuration edited")
        assertFalse(LocalCheck.canSkipRemote(base.copy(status = "OBSERVED_MISSING"), exit, "n", now), "unresolved result")
        assertFalse(LocalCheck.canSkipRemote(base.copy(status = "WIFI_UNAVAILABLE"), exit, "n", now), "unresolved result")
        assertFalse(LocalCheck.canSkipRemote(base.copy(failures = 1), exit, "n", now), "backing off")
        assertFalse(LocalCheck.canSkipRemote(base.copy(paused = true), exit, "n", now), "paused")
    }
    @Test fun defaultsFollowOfficialScriptCadence() {
        val p = Policy()
        assertEquals(3_000, p.debounceMs); assertEquals(10L, p.fallbackMinutes)
        assertTrue(p.minIntervalMs <= 10_000); assertTrue(p.freshnessMs >= p.probeIntervalMs)
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
    @Test fun probeThrottleIsPerNetworkButFailuresStillBackOff() {
        val s = State(nextProbeAllowed = 10_000, probeStatus = "PROBE_OBSERVED", domesticExit = DomesticExit("192.0.2.1", 0, "old"))
        assertFalse(LocalCheck.probeAllowed(s, "old", 5_000))
        assertTrue(LocalCheck.probeAllowed(s, "new", 5_000))
        assertFalse(LocalCheck.probeAllowed(s.copy(probeStatus = "PROBE_FAILED"), "new", 5_000))
        assertTrue(LocalCheck.probeAllowed(s, "old", 10_000))
    }
}
