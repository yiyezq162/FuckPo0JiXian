package app.allowmate.core

import kotlin.test.*

class RuntimePolicyTest {
    @Test fun legacyDataDefaultsToStandardWithoutResettingLimits() {
        val old = State(nextAllowed = 900_000, nextProbeAllowed = 800_000, failures = 3)
        val text = StateCodec.encode(old).replace(",\"runtimeMode\":\"STANDARD\"", "")
        assertEquals(old, StateCodec.decode(text))
    }
    @Test fun modeRoundTripPreservesAccountAndBackoff() {
        val s = State(mode = Mode.AUTO, paused = false, nextAllowed = 910_000, nextProbeAllowed = 820_000,
            failures = 4, slotPlan = SlotPlan(Cidr("192.0.2.0/24"), 0, 1, true, Cidr("198.51.100.0/24")))
        val enhanced = StateCodec.decode(StateCodec.encode(s.copy(runtimeMode = RuntimeMode.MODULE)))
        assertEquals(s, enhanced.copy(runtimeMode = RuntimeMode.STANDARD))
        assertTrue(RuntimePolicy.enabled(enhanced))
    }
    @Test fun allStopGatesFailClosed() {
        val s = State(runtimeMode = RuntimeMode.MODULE, paused = false)
        assertTrue(RuntimePolicy.enabled(s))
        listOf(s.copy(paused = true), s.copy(demo = true), s.copy(authBlocked = true), s.copy(runtimeMode = RuntimeMode.STANDARD))
            .forEach { assertFalse(RuntimePolicy.enabled(it)) }
    }
    @Test fun handshakeIsNotCompletionAndMissingEvidenceIsNotInstallationProof() {
        assertTrue(RuntimePolicy.status(RuntimeMode.MODULE, false, "READY").contains("只读验收"))
        assertTrue(RuntimePolicy.status(RuntimeMode.MODULE, false, null).contains("安装及启用状态未知"))
        assertTrue(RuntimePolicy.status(RuntimeMode.MODULE, false, "VERSION").contains("版本不匹配"))
        assertTrue(RuntimePolicy.status(RuntimeMode.MODULE, false, "LOCKED").contains("首次解锁"))
        assertTrue(RuntimePolicy.status(RuntimeMode.MODULE, false, "DISABLED").contains("禁用"))
    }
}
