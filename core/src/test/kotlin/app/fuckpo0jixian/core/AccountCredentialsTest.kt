package app.fuckpo0jixian.core

import kotlin.test.*

class AccountCredentialsTest {
    private fun state() = State(mode = Mode.AUTO, paused = false, accountContext = "old",
        layout = SlotLayout(slots = listOf(ManagedSlot(0, writer = Writer.LOCAL, authorized = true))),
        serverNotBefore = 90_000, nextAllowed = 90_000)

    @Test fun interruptionAtEveryCommitBoundaryIsFailClosed() {
        for (fault in 1..4) {
            val memory = MemoryStore(state())
            var saves = 0
            var token = "pgnfw_OLD"
            var writes = 0
            val store = object : StateStore {
                override fun load() = memory.load()
                override fun save(state: State) {
                    saves++
                    if ((fault == 1 && saves == 1) || (fault == 4 && saves == 2)) error("disk fault")
                    memory.save(StateCodec.decode(StateCodec.encode(state)))
                }
            }
            assertFails {
                val next = AccountCredentials.save(store, "pgnfw_NEW", { token }) {
                    writes++
                    if (fault == 2) error("credential write rejected")
                    token = it
                    if (fault == 3) error("credential written but result lost")
                }
                store.save(next)
            }
            if (fault == 1) {
                assertEquals("pgnfw_OLD", token); assertEquals(0, writes)
            } else {
                assertTrue(memory.state.paused); assertNull(memory.state.layout)
                assertNotEquals("old", memory.state.accountContext)
                assertEquals(90_000, memory.state.serverNotBefore)
            }
        }
    }
    @Test fun sameTokenKeepsConfigurationWithoutRewritingCredential() {
        val st = MemoryStore(state())
        val before = st.state
        st.save(AccountCredentials.save(st, " pgnfw_SAME ", { "pgnfw_SAME" }) { error("must not rewrite") })
        assertEquals(before.layout, st.state.layout)
        assertEquals(before.accountContext, st.state.accountContext)
        assertTrue(st.state.paused)
        assertEquals(before.serverNotBefore, st.state.serverNotBefore)
    }
    @Test fun unreadableIdentityRevokesBeforeAttemptingCredentialWrite() {
        val st = MemoryStore(state())
        assertFails {
            AccountCredentials.save(st, "pgnfw_NEW", { error("vault unavailable") }) {
                assertTrue(st.state.paused); assertNull(st.state.layout)
                error("vault still unavailable")
            }
        }
        assertNull(st.state.layout)
    }
}
