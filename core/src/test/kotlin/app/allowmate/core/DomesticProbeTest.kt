package app.allowmate.core

import kotlin.test.*
class DomesticProbeTest {
    @Test fun parseOnlyLabelledIpv4NotArbitraryPageAddress() {
        val result = DomesticProbe.parse(HttpReply(200, "当前 IP：203.0.113.42  来自于：测试"), 9, "network")
        assertEquals("203.0.113.42", result.ipv4)
        assertEquals(Cidr("203.0.113.0/24"), result.cidr)
        listOf("IP 203.0.113.42", "当前 IP：256.0.0.1", "当前 IP：2001:db8::1", "当前 IP：1.2.3.4 当前 IP：5.6.7.8").forEach {
            assertFailsWith<ApiFailure> { DomesticProbe.parse(HttpReply(200, it), 9, "network") }
        }
    }
    @Test fun probeDoesNotAlterPlatformSnapshotOrAuthorizeEgress() {
        val exit = DomesticExit("203.0.113.1", 10, "network")
        val state = State(domesticExit = exit, nextProbeAllowed = 900, probeStatus = "PROBE_OBSERVED")
        val restored = StateCodec.decode(StateCodec.encode(state))
        assertEquals(state, restored); assertNull(restored.snapshot); assertEquals(Mode.OBSERVE, restored.mode)
    }
    @Test fun serviceRateLimitPropagatesWithoutResponseLogging() {
        val e = assertFailsWith<ApiFailure> { DomesticProbe.parse(HttpReply(429, "private-body", "600"), 0, "network") }
        assertEquals(600_000L, e.retryAfterMs); assertFalse(e.message!!.contains("private-body"))
    }
    @Test fun ipv6IsNotSilentlyConvertedToIpv4() {
        val e = assertFailsWith<ApiFailure> { DomesticProbe.parse(HttpReply(200, "当前 IP：2001:db8::1 来自于：测试"), 1, "net") }
        assertEquals("PROBE_IPV6_ONLY", e.code)
    }
    @Test fun ipv4OnlyEndpointAcceptsOnlyCanonicalIpv4() {
        val result = DomesticProbe.parse(HttpReply(200, "203.0.113.8\n"), 10, "net", ProbeSource.IP3322)
        assertEquals(ProbeSource.IP3322, result.source)
        assertEquals(result, StateCodec.decode(StateCodec.encode(State(domesticExit = result))).domesticExit)
        assertFailsWith<ApiFailure> { DomesticProbe.parse(HttpReply(200, "<html>203.0.113.8</html>"), 10, "net", ProbeSource.IP3322) }
    }
}
