package app.allowmate

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.allowmate.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Emulator-only root harness; never included in the authorized real-account test class. */
@RunWith(AndroidJUnit4::class)
class RuntimeModuleTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val c get() = (rule.activity.application as AllowMateApp).controller
    @Test fun resumingEnhancedModeTriggersWithoutNetworkChange() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(!c.vault.exists())
        val paused = State(paused = true, runtimeMode = RuntimeMode.MODULE, nextAllowed = Long.MAX_VALUE)
        rule.runOnUiThread { c.store.save(paused) }
        runBlocking { c.runtime.publish(paused) }
        assertEquals("STOPPED", runBlocking { c.runtime.exchange("STATUS") })
        // Drain earlier event-storm callbacks while paused; no network or manual
        // service trigger is permitted in this recovery test.
        Thread.sleep(16_000)
        val before = c.runtime.result.value
        val requests = NetworkTransport.requests.get()
        try {
            val resumed = paused.copy(paused = false)
            rule.runOnUiThread { c.store.save(resumed) }
            runBlocking { c.runtime.publish(resumed) }
            rule.waitUntil(30_000) { c.runtime.result.value != before && c.runtime.result.value.contains("RATE_LIMITED") }
            assertEquals(requests, NetworkTransport.requests.get())
            assertEquals(Long.MAX_VALUE, c.store.load().nextAllowed)
            assertTrue(c.runtime.result.value.contains("未证明请求完成"))
        } finally {
            rule.runOnUiThread { c.store.save(State()) }
            runBlocking { c.runtime.publish(State()) }
        }
    }
    @Test fun deepIdleDeclinesEnhancedRequestAndStopsForegroundService() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(!c.vault.exists())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
        }
        check(shell("dumpsys deviceidle get deep").trim() == "ACTIVE")
        val state = State(paused = false, runtimeMode = RuntimeMode.MODULE, nextAllowed = Long.MAX_VALUE)
        rule.runOnUiThread { c.store.save(state) }
        runBlocking { c.runtime.publish(state) }
        val wifi = android.provider.Settings.Global.getInt(rule.activity.contentResolver, "wifi_on", 0)
        val count = NetworkTransport.requests.get()
        val deepEnabled = shell("dumpsys deviceidle enabled deep").trim() == "1"
        try {
            if (!deepEnabled) shell("dumpsys deviceidle enable deep")
            shell("dumpsys deviceidle force-idle deep")
            check(shell("dumpsys deviceidle get deep").trim() == "IDLE")
            shell("svc wifi disable")
            Thread.sleep(2000)
            shell("svc wifi enable")
            rule.waitUntil(60_000) { c.runtime.result.value.contains("DEGRADED_DOZE") }
            assertEquals(count, NetworkTransport.requests.get())
            assertEquals(Long.MAX_VALUE, c.store.load().nextAllowed)
            rule.waitUntil(5000) { !shell("dumpsys activity services app.allowmate").contains("app.allowmate/.RuntimeSyncService") }
        } finally {
            shell("dumpsys deviceidle unforce")
            if (!deepEnabled) shell("dumpsys deviceidle disable deep")
            shell(if (wifi == 0) "svc wifi disable" else "svc wifi enable")
            rule.runOnUiThread { c.store.save(State()) }
            runBlocking { c.runtime.publish(State()) }
        }
    }
    @Test fun unclaimedWakeIsReportedAsFailureWithoutHttp() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(!c.vault.exists())
        val state = State(paused = false, nextAllowed = Long.MAX_VALUE)
        rule.runOnUiThread { c.store.save(state) }
        runBlocking { c.runtime.publish(state) }
        // Fault injection: helper sees a stale enabled gate; the actual APK refuses
        // this request. am can still return success, so PID/exit-code is insufficient.
        runBlocking { c.runtime.publish(state.copy(runtimeMode = RuntimeMode.MODULE)) }
        val count = NetworkTransport.requests.get()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        val wifi = android.provider.Settings.Global.getInt(rule.activity.contentResolver, "wifi_on", 0)
        try {
            shell("svc wifi disable")
            Thread.sleep(2000)
            shell("svc wifi enable")
            runBlocking {
                kotlinx.coroutines.withTimeout(65_000) {
                    while (c.runtime.exchange("STATUS") != "START_FAILED") kotlinx.coroutines.delay(1000)
                }
            }
            assertEquals(count, NetworkTransport.requests.get())
            assertEquals(Long.MAX_VALUE, c.store.load().nextAllowed)
        } finally {
            shell(if (wifi == 0) "svc wifi disable" else "svc wifi enable")
            rule.runOnUiThread { c.store.save(State()) }
            runBlocking { c.runtime.publish(State()) }
        }
    }
    @Test fun networkEventEntersApkAndQuotaIsNotHttpCompletion() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val original = State(paused = false, runtimeMode = RuntimeMode.MODULE, nextAllowed = Long.MAX_VALUE)
        rule.runOnUiThread { c.store.save(original) }
        runBlocking { c.runtime.publish(original) }
        val before = c.runtime.result.value
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        val wifi = android.provider.Settings.Global.getInt(rule.activity.contentResolver, "wifi_on", 0)
        try {
            shell("svc wifi disable")
            Thread.sleep(2000)
            shell("svc wifi enable")
            rule.waitUntil(60_000) { c.runtime.result.value != before && c.runtime.result.value.contains("RATE_LIMITED") }
            assertTrue(c.runtime.result.value.contains("HTTP尝试 0"))
            assertTrue(c.runtime.result.value.contains("未证明请求完成"))
            assertEquals(Long.MAX_VALUE, c.store.load().nextAllowed)
            val epoch = runBlocking { c.runtime.exchange("STATUS") }!!.removePrefix("READY:")
            assertFalse(runBlocking { c.runtime.validated(epoch) })
        } finally {
            shell(if (wifi == 0) "svc wifi disable" else "svc wifi enable")
            rule.runOnUiThread { c.store.save(State()) }
            runBlocking { c.runtime.publish(State()) }
        }
    }
    @Test fun rootHandshakeAndForgedClaimAndStopGates() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val original = State(paused = false, runtimeMode = RuntimeMode.MODULE, nextAllowed = Long.MAX_VALUE)
        rule.runOnUiThread { c.store.save(original) }
        runBlocking { c.runtime.publish(original) }
        val reply = runBlocking { c.runtime.exchange("STATUS") }
        assertTrue("helper reply=$reply", reply?.startsWith("READY:") == true)
        val info = org.json.JSONObject(runBlocking { c.runtime.exchange("INFO") }!!)
        assertEquals(android.provider.Settings.Global.getInt(rule.activity.contentResolver, android.provider.Settings.Global.BOOT_COUNT), info.getInt("boot"))
        assertTrue(info.getLong("startedMs") >= 0)
        assertEquals("REJECTED", runBlocking { c.runtime.exchange("DIAGNOSTICS") })
        assertNull(runBlocking { c.runtime.claim("00000000-0000-0000-0000-000000000000") })
        assertEquals("REJECTED", runBlocking { c.runtime.exchange("EXEC", "id") })
        runBlocking { c.runtime.publish(original.copy(paused = true)) }
        assertEquals("STOPPED", runBlocking { c.runtime.exchange("STATUS") })
        runBlocking { c.runtime.publish(original.copy(runtimeMode = RuntimeMode.STANDARD)) }
        assertEquals("STOPPED", runBlocking { c.runtime.exchange("STATUS") })
        rule.runOnUiThread { c.store.save(State()) }
        runBlocking { c.runtime.publish(State()) }
    }
}
