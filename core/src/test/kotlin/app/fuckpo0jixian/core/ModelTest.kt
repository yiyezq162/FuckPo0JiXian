package app.fuckpo0jixian.core

import kotlin.test.*

class ModelTest {
    private val cidr = Cidr("203.0.113.0/24")
    private val snap = Snapshot(cidr, listOf(Entry(cidr)), 7)
    @Test fun invalidNetworksRejected() {
        listOf("1.2.3.4/24", "1.2.0.0/16", "256.1.1.0/24", "01.2.3.0/24", "::/24").forEach { assertFailsWith<IllegalArgumentException> { Cidr(it) } }
    }
    @Test fun duplicatePlatformEntriesRejected() { assertFailsWith<IllegalArgumentException> { snap.copy(entries = listOf(Entry(cidr), Entry(cidr))) } }
    @Test fun debounceCoalescesAndDuplicatesDoNotStarve() {
        val d = Debouncer(15_000); d.changed("a", 0); d.changed("a", 10_000)
        assertNull(d.takeDue(14_999)); assertEquals("a", d.takeDue(15_000)); assertNull(d.takeDue(16_000))
        d.changed("b", 16_000); d.changed("c", 20_000)
        assertNull(d.takeDue(31_000)); assertEquals("c", d.takeDue(35_000))
        d.changed(null, 36_000); assertNull(d.takeDue(100_000))
    }
    @Test fun durableRoundTripPreservesAllSafetyState() {
        val state = State(mode = Mode.AUTO, paused = false, demo = true,
            snapshot = snap.copy(revision = "r1"), lastCheck = 10, lastSuccess = 8, nextAllowed = 1000, failures = 3,
            authBlocked = true, networkKey = "net", status = "AUTH_PAUSED", observations = listOf(Observation(2, cidr, "wifi")), events = listOf(Event(3, "FAIL")))
        assertEquals(state, StateCodec.decode(StateCodec.encode(state)))
        assertFails { StateCodec.decode("{}") }
    }
    @Test fun retiredFieldsAreStillWrittenForOlderReleasesAndIgnoredOnRead() {
        val text = StateCodec.encode(State())
        listOf("\"fixed\":0", "\"profiles\":[]", "\"ownership\":[]", "\"activeProfileId\":null").forEach { assertTrue(it in text, it) }
        val old = text.replace("\"profiles\":[]", "\"profiles\":[{\"id\":\"p\",\"name\":\"n\",\"kind\":\"FIXED\",\"cidr\":null,\"hint\":null}]")
        assertEquals(StateCodec.decode(text), StateCodec.decode(old))
    }
}
