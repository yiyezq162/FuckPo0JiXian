package app.fuckpo0jixian.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Engine-wide behavior on the slot path: single flight, limits, backoff and pause, whatever the slot layout. */
class EngineTest {
    private var clock = 1_000_000L
    private val old = Cidr("198.51.100.0/24")
    private val store = MemoryStore(State(paused = false, mode = Mode.AUTO, accountContext = "a", layout = SlotLayout(slots = listOf(
        ManagedSlot(0, "手机", SlotPurpose.MOBILE, Writer.LOCAL, automatic = true, authorized = true, baseline = old)))))
    private val engine = Engine(store, { clock })
    private val demo = SlotDemoPlatform().apply { entries = listOf(Entry(old, 0)) }
    private var live = true
    private val network get() = NetworkSession("net-1", "cellular", demo.current) { live }
    private suspend fun check(p: SlotPlatform = demo, manual: Boolean = true) = engine.check(p, network, manual)

    @Test fun observingNeverWrites() = runTest {
        store.state = store.state.copy(mode = Mode.OBSERVE)
        assertEquals("OBSERVED_MISSING", check()); assertEquals(0, demo.revision)
    }
    @Test fun automaticLoopGuardHoldsButManualChecksPass() = runTest {
        assertEquals("SLOT_UPDATED", check(manual = false))
        assertEquals("RATE_LIMITED", check(manual = false))
        assertEquals("SLOT_CURRENT", check(manual = true)); assertEquals(1, demo.revision)
    }
    @Test fun recentFailureIsNotHiddenAndSuccessClearsIt() = runTest {
        val broken = object : SlotPlatform by demo { override suspend fun query(): Snapshot = throw ApiFailure("HTTP_500", 500) }
        assertEquals("HTTP_500", check(broken)); assertEquals(1, store.state.failures)
        assertEquals("RATE_LIMITED", check(manual = false))
        clock = store.state.nextAllowed
        assertEquals("SLOT_UPDATED", check(manual = false)); assertEquals(0, store.state.failures)
    }
    @Test fun networkSwitchDuringReadCancelsWrite() = runTest {
        val p = object : SlotPlatform by demo { override suspend fun query(): Snapshot { live = false; return demo.query() } }
        assertEquals("NETWORK_CHANGED", check(p)); assertEquals(0, demo.revision)
    }
    @Test fun authFailurePausesAcrossEngineRestart() = runTest {
        val p = object : SlotPlatform by demo { override suspend fun query(): Snapshot = throw ApiFailure("HTTP_401", 401) }
        assertEquals("HTTP_401", check(p)); clock += 3_600_000
        val restored = MemoryStore(StateCodec.decode(StateCodec.encode(store.state)))
        assertEquals("AUTH_PAUSED", Engine(restored, { clock }).check(demo, network, true))
    }
    @Test fun retryAfterHonoredBeyondBackoffCapAndRestart() = runTest {
        val p = object : SlotPlatform by demo { override suspend fun query(): Snapshot = throw ApiFailure("HTTP_429", 429, 7_200_000) }
        check(p)
        assertEquals(clock + 7_200_000, store.state.serverNotBefore)
        val restored = MemoryStore(StateCodec.decode(StateCodec.encode(store.state)))
        clock += 3_600_000
        assertEquals("RATE_LIMITED", Engine(restored, { clock }).check(demo, network, true))
    }
    @Test fun repeatedFailuresExponentiallyBackOffToCap() = runTest {
        val p = object : SlotPlatform by demo { override suspend fun query(): Snapshot = throw ApiFailure("FAIL") }
        repeat(10) { check(p); assertTrue(store.state.nextAllowed - clock <= 3_600_000); clock = store.state.nextAllowed }
        assertEquals(10, store.state.failures)
    }
    @Test fun concurrentChecksSingleFlight() = runTest {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val p = object : SlotPlatform by demo { override suspend fun query(): Snapshot { started.complete(Unit); finish.await(); return demo.query() } }
        val task = async { check(p) }; started.await()
        assertEquals("BUSY", check()); finish.complete(Unit); assertEquals("SLOT_UPDATED", task.await())
    }
    @Test fun pausedDoesNotQuery() = runTest {
        store.state = store.state.copy(paused = true)
        assertEquals("PAUSED", check()); assertEquals(0, store.state.lastCheck)
    }
}
