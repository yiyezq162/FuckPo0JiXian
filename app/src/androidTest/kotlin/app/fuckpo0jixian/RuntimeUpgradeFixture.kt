package app.fuckpo0jixian

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fuckpo0jixian.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Opt-in emulator harness for an external install -r between instrumentation runs. */
@RunWith(AndroidJUnit4::class)
class RuntimeUpgradeFixture {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun prepare() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("prepareUpgradeFixture") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val c = (rule.activity.application as FuckPo0JiXianApp).controller
        check(!c.vault.exists())
        val state = State(runtimeMode = RuntimeMode.MODULE, paused = false, nextAllowed = Long.MAX_VALUE)
        rule.runOnUiThread { c.store.save(state) }
        c.runtime.publish(state)
        val reply = requireNotNull(c.runtime.exchange("STATUS"))
        assertTrue(reply.startsWith("READY:"))
        val epoch = reply.removePrefix("READY:")
        // Synthetic proof exists only in this credential-free emulator fixture.
        c.runtime.validate(epoch)
        assertTrue(c.runtime.validated(epoch))
        c.runtime.record("UPGRADE_FIXTURE_READY", 0, false)
    }
    @Test fun verifyAndRestore() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("verifyUpgradeFixture") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val c = (rule.activity.application as FuckPo0JiXianApp).controller
        check(!c.vault.exists())
        try {
            val epoch = requireNotNull(c.runtime.exchange("STATUS")).removePrefix("READY:")
            assertFalse(c.runtime.validated(epoch))
            assertEquals(Long.MAX_VALUE, c.store.load().nextAllowed)
            assertEquals(0L, c.store.load().lastSuccess)
            assertTrue(c.runtime.result.value.contains("RATE_LIMITED"))
            assertTrue(c.runtime.result.value.contains("HTTP尝试 0"))
        } finally {
            rule.runOnUiThread { c.store.save(State()) }
            c.runtime.publish(State())
        }
    }
}
