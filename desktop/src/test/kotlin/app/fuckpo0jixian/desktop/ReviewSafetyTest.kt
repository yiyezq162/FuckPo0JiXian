package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class ReviewSafetyTest {
    private fun authorized() = State(mode = Mode.AUTO, paused = false, accountContext = "old",
        layout = SlotLayout(slots = listOf(ManagedSlot(0, purpose = SlotPurpose.MOBILE,
            writer = Writer.LOCAL, automatic = true, authorized = true))))

    @Test fun credentialWriteInterruptionNeverLeavesOldAuthority() = runBlocking {
        val dir = Files.createTempDirectory("token-fault")
        val store = FileStore(dir).apply { save(authorized()) }
        var token = "pgnfw_OLD_FAKE"
        var atWrite: State? = null
        val vault = object : TokenVault {
            override fun exists() = true
            override fun read() = token
            override fun clear() {}
            override fun save(value: String) {
                token = value
                atWrite = FileStore(dir).load() // process death after credential write
                error("simulated interrupted credential write")
            }
        }
        val c = DesktopController(store, vault)
        try {
            c.saveToken("pgnfw_NEW_FAKE")
            withTimeout(5_000) { while (c.feedback.value.isEmpty()) delay(10) }
            assertEquals("pgnfw_NEW_FAKE", token)
            assertTrue(atWrite!!.paused)
            assertNull(atWrite!!.layout)
            assertNull(FileStore(dir).load().layout)
        } finally { c.stop() }
    }

    @Test fun post429HoldsAcrossManualConnectionCheckAndRestart() = runBlocking {
        var clock = 1_800_000_000_000L
        val dir = Files.createTempDirectory("post429")
        val vault = FileVault(dir.resolve("fake-token")).apply { save("pgnfw_RATE_TEST") }
        var calls = 0
        var posts = 0
        val transport = object : Transport {
            override suspend fun execute(method: String, url: String): HttpReply {
                calls++
                if (method == "POST") { posts++; return HttpReply(429, "", "7200") }
                return HttpReply(200, """{"enabled":true,"limit":5,"currentIp":"203.0.113.0/24","whitelist":[]}""")
            }
        }
        fun controller() = DesktopController(FileStore(dir), vault,
            { DesktopLink("en0", "192.0.2.5", "192.0.2.1", "a4:11:22:33:44:55") },
            stun = { "203.0.113.9" }, now = { clock }) { _, _ -> transport }
        val c = controller()
        c.store.save(authorized().copy(layout = authorized().layout!!.copy(slots = authorized().layout!!.slots.map { it.copy(allowUnknownWifi = true) })))
        assertEquals("PENDING_REVIEW", c.runCheck(true))
        val count = calls
        c.stop()
        val restarted = controller()
        try {
            assertEquals("RATE_LIMITED", restarted.runCheck(true, observeOnly = true))
            // A settings operation changes the display status. The server deadline must remain authoritative.
            restarted.saveToken("pgnfw_RATE_TEST")
            withTimeout(5_000) { while (restarted.store.load().status != "TOKEN_SAVED") delay(10) }
            restarted.store.save(restarted.store.load().copy(paused = false))
            clock += 3_600_001
            assertEquals("RATE_LIMITED", restarted.runCheck(true))
            assertEquals(count, calls)
            clock += 3_600_000
            assertEquals("RECOVERED_NOT_APPLIED", restarted.runCheck(true))
            assertEquals(1, posts, "recovery is GET only")
        } finally { restarted.stop() }
    }
    @Test fun stateSaveFailuresBeforeAndAfterCredentialMutationAreSafeOnDisk() = runBlocking {
        for (afterCredential in listOf(false, true)) {
            val dir = Files.createTempDirectory("token-disk-fault")
            val store = FileStore(dir).apply { save(authorized()) }
            var writes = 0
            val vault = object : TokenVault {
                override fun exists() = true
                override fun read() = "pgnfw_OLD"
                override fun clear() {}
                override fun save(token: String) {
                    writes++
                    Files.createDirectory(dir.resolve("state.json.tmp")) // final state commit fails
                }
            }
            if (!afterCredential) Files.createDirectory(dir.resolve("state.json.tmp"))
            val c = DesktopController(store, vault)
            try {
                c.saveToken("pgnfw_NEW")
                withTimeout(5_000) { while (c.feedback.value.isEmpty()) delay(10) }
                assertEquals(if (afterCredential) 1 else 0, writes)
                val restarted = FileStore(dir).load()
                if (afterCredential) { assertTrue(restarted.paused); assertNull(restarted.layout) }
                else assertEquals("old", restarted.accountContext)
            } finally { c.stop() }
        }
    }
}
