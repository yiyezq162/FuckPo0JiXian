package app.allowmate.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EngineTest {
    private var clock = 1_000_000L
    private val store = MemoryStore(State(paused = false, demo = true, mode = Mode.AUTO, budget = Budget(0, 3)))
    private val engine = Engine(store, { clock })
    private val demo = DemoPlatform()
    private var live = true
    private val network get() = NetworkSession("net-1", "test", true) { live }
    private suspend fun check(p: Platform = demo, n: NetworkSession = network, manual: Boolean = true) = engine.check(p, n, manual)

    @Test fun emptyCapacityAddPreservesExternalAndVerifies() = runTest {
        assertEquals("ADDED_VERIFIED", check())
        assertEquals(2, store.state.snapshot!!.entries.size)
        assertTrue(Allocation.protected(store.state, Cidr("198.51.100.0/24")))
        assertEquals(1, store.state.ownership.size)
    }
    @Test fun presentNeverWrites() = runTest {
        demo.entries = demo.entries + Entry(demo.current)
        assertEquals("PRESENT_CURRENT_CHECK", check())
        assertEquals(0, demo.revision)
    }
    @Test fun observingNeverWrites() = runTest {
        store.state = store.state.copy(mode = Mode.OBSERVE)
        assertEquals("OBSERVED_MISSING", check()); assertEquals(0, demo.revision)
    }
    @Test fun unverifiedEgressBlocksWrite() = runTest {
        assertEquals("EGRESS_UNVERIFIED", check(n = network.copy(verifiedEgress = false)))
        assertEquals(0, demo.revision)
    }
    @Test fun unknownCapabilityStopsEvenWithVacancy() = runTest {
        val p = object : Platform by demo { override val capabilities = Capabilities() }
        assertEquals("UNSAFE_SERVER_ADD", check(p)); assertEquals(0, demo.revision)
    }
    @Test fun fullPreservesAll() = runTest {
        demo.capacity = 1; store.state = store.state.copy(budget = Budget())
        assertEquals("CAPACITY_FULL", check()); assertEquals(1, demo.entries.size)
    }
    @Test fun protectedExternalConsumesBudget() = runTest {
        demo.capacity = 3
        assertEquals("BUDGET_CONFLICT", check()); assertEquals(0, demo.revision)
    }
    @Test fun mobileBudgetDoesNotAllowUnboundedAccumulation() = runTest {
        store.state = store.state.copy(budget = Budget(0, 1))
        assertEquals("ADDED_VERIFIED", check())
        clock += 120_000; demo.current = Cidr("192.0.2.0/24")
        assertEquals("MOBILE_BUDGET_FULL", check()); assertEquals(2, demo.entries.size)
    }
    @Test fun manualClickCannotBypassMinInterval() = runTest {
        check(); assertEquals("RATE_LIMITED", check()); assertEquals(1, demo.revision)
    }
    @Test fun networkChangeInvalidatesCacheNotGlobalRateLimit() = runTest {
        check()
        val other = network.copy(key = "new")
        assertEquals("RATE_LIMITED", check(n = other, manual = false))
        clock += 120_000
        assertEquals("PRESENT_CURRENT_CHECK", check(n = other, manual = false))
    }
    @Test fun automaticSuccessCacheAndManualFreshQuery() = runTest {
        check(); clock += 120_000
        assertEquals("CACHED_OBSERVATION", check(manual = false))
        assertEquals("PRESENT_CURRENT_CHECK", check())
    }
    @Test fun recentFailureCannotBeHiddenByOlderSuccessCache() = runTest {
        check(); clock += 120_000
        val broken = object : Platform by demo { override suspend fun query(): Snapshot = throw ApiFailure("HTTP_500", 500) }
        assertEquals("HTTP_500", check(broken))
        clock = store.state.nextAllowed
        assertEquals("PRESENT_CURRENT_CHECK", check(manual = false))
        assertEquals(0, store.state.failures)
    }
    @Test fun networkSwitchDuringReadCancelsWrite() = runTest {
        val p = object : Platform by demo { override suspend fun query(): Snapshot { live = false; return demo.query() } }
        assertEquals("NETWORK_CHANGED", check(p)); assertEquals(0, demo.revision)
    }
    @Test fun rereadDetectsOtherUpdater() = runTest {
        var reads = 0
        val p = object : Platform by demo { override suspend fun query(): Snapshot {
            if (++reads == 2) { demo.entries = demo.entries + Entry(Cidr("192.0.2.0/24")); demo.revision++ }
            return demo.query()
        } }
        assertEquals("CONCURRENT_CHANGE", check(p)); assertFalse(demo.entries.any { it.cidr == demo.current })
    }
    @Test fun compareAndSwapRejectsRaceAfterReread() = runTest {
        val p = object : Platform by demo { override suspend fun addIfUnchanged(expected: Snapshot): Snapshot {
            demo.revision++; return demo.addIfUnchanged(expected)
        } }
        assertEquals("CONCURRENT_CHANGE", check(p)); assertEquals(1, demo.entries.size)
    }
    @Test fun failedPostconditionNeverClaimsOwnership() = runTest {
        val p = object : Platform by demo { override suspend fun addIfUnchanged(expected: Snapshot) = demo.query() }
        assertEquals("VERIFY_FAILED", check(p)); assertTrue(store.state.ownership.isEmpty())
    }
    @Test fun externalDeletionInPostconditionFails() = runTest {
        val p = object : Platform by demo { override suspend fun addIfUnchanged(expected: Snapshot): Snapshot {
            demo.entries = listOf(Entry(demo.current)); return demo.query()
        } }
        assertEquals("VERIFY_FAILED", check(p)); assertTrue(store.state.ownership.isEmpty())
    }
    @Test fun finalQueryRequiredEvenWhenPostLooksSuccessful() = runTest {
        var reads = 0
        val p = object : Platform by demo { override suspend fun query(): Snapshot {
            if (++reads == 3) demo.entries = emptyList()
            return demo.query()
        } }
        assertEquals("VERIFY_FAILED", check(p)); assertEquals(3, reads)
    }
    @Test fun authFailurePausesAcrossEngineRestart() = runTest {
        val p = object : Platform by demo { override suspend fun query(): Snapshot = throw ApiFailure("HTTP_401", 401) }
        assertEquals("HTTP_401", check(p)); clock += 3_600_000
        val restored = MemoryStore(StateCodec.decode(StateCodec.encode(store.state)))
        assertEquals("AUTH_PAUSED", Engine(restored, { clock }).check(demo, network, true))
    }
    @Test fun retryAfterHonoredBeyondBackoffCapAndRestart() = runTest {
        val p = object : Platform by demo { override suspend fun query(): Snapshot = throw ApiFailure("HTTP_429", 429, 7_200_000) }
        check(p)
        assertEquals(clock + 7_200_000, store.state.nextAllowed)
        val restored = MemoryStore(StateCodec.decode(StateCodec.encode(store.state)))
        clock += 3_600_000
        assertEquals("RATE_LIMITED", Engine(restored, { clock }).check(demo, network, true))
    }
    @Test fun repeatedFailuresExponentiallyBackOffToCap() = runTest {
        val p = object : Platform by demo { override suspend fun query(): Snapshot = throw ApiFailure("FAIL") }
        repeat(10) { check(p); assertTrue(store.state.nextAllowed - clock <= 3_600_000); clock = store.state.nextAllowed }
        assertEquals(10, store.state.failures)
    }
    @Test fun concurrentChecksSingleFlight() = runTest {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val p = object : Platform by demo { override suspend fun query(): Snapshot { started.complete(Unit); finish.await(); return demo.query() } }
        val task = async { check(p) }; started.await()
        assertEquals("BUSY", check()); finish.complete(Unit); assertEquals("ADDED_VERIFIED", task.await())
    }
    @Test fun cancellationPersistsReservationAndReleasesLock() = runTest {
        val started = CompletableDeferred<Unit>()
        val p = object : Platform by demo { override suspend fun query(): Snapshot { started.complete(Unit); awaitCancellation() } }
        val task = launch { check(p) }; started.await(); task.cancelAndJoin()
        assertEquals("CANCELLED_NETWORK_OR_SETTINGS", store.state.status)
        assertEquals("RATE_LIMITED", check()); clock += 120_000; assertEquals("ADDED_VERIFIED", check())
    }
    @Test fun pausedDoesNotQuery() = runTest {
        store.state = store.state.copy(paused = true)
        assertEquals("PAUSED", check()); assertEquals(0, store.state.lastCheck)
    }
    @Test fun fixedDriftNeverOverwrites() = runTest {
        store.state = store.state.copy(profiles = listOf(Profile("p", "固定", Kind.FIXED, Cidr("198.51.100.0/24"), "net-1")))
        assertEquals("FIXED_DRIFT", check()); assertEquals(0, demo.revision)
    }
    @Test fun retentionPrunesOldRecords() = runTest {
        store.state = store.state.copy(events = listOf(Event(clock - 8 * 86_400_000, "OLD")), observations = listOf(Observation(clock - 8 * 86_400_000, demo.current, "test")))
        check(); assertEquals(1, store.state.events.size); assertEquals(1, store.state.observations.size)
    }
    @Test fun selectedFixedProfileDetectsDriftAcrossNetworkInstancesEvenInObserveMode() = runTest {
        store.state = store.state.copy(mode = Mode.OBSERVE, activeProfileId = "fixed",
            profiles = listOf(Profile("fixed", "固定", Kind.FIXED, Cidr("198.51.100.0/24"), "old-instance")))
        assertEquals("FIXED_DRIFT", check()); assertEquals(0, demo.revision)
    }
    @Test fun emptySelectedFixedCanUseFixedBudgetWithoutMobileBudget() = runTest {
        store.state = store.state.copy(budget = Budget(1, 0), activeProfileId = "fixed",
            profiles = listOf(Profile("fixed", "固定", Kind.FIXED)))
        assertEquals("ADDED_VERIFIED", check())
        assertEquals(demo.current, store.state.profiles.single().cidr)
        assertTrue(store.state.ownership.single().protected)
    }
    @Test fun selectedMobileKeepsOldEntryAsCacheAndUpdatesOnlyItsReference() = runTest {
        store.state = store.state.copy(activeProfileId = "mobile",
            profiles = listOf(Profile("mobile", "流动", Kind.MOBILE, Cidr("198.51.100.0/24"))))
        assertEquals("ADDED_VERIFIED", check())
        assertEquals(2, demo.entries.size)
        assertEquals(demo.current, store.state.profiles.single().cidr)
    }
}
