package app.fuckpo0jixian

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fuckpo0jixian.core.Mode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Opt-in account tests. Never run these as part of the ordinary emulator/UI suite. */
@RunWith(AndroidJUnit4::class)
class AuthorizedAccountTest {
    @Test fun inspectWifiIdentityRead() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("allowWifiIdentityRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val c = (context.applicationContext as FuckPo0JiXianApp).controller
        check(c.store.load().paused && !c.store.load().demo)
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val network = checkNotNull(manager.activeNetwork)
        check(manager.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true)
        val requests = NetworkTransport.requests.get()
        val observer = WifiIdentityObserver(context) { "device-test:${it.networkHandle}" }
        // No ActivityScenario: OEM lifecycle test harnesses can stall before reaching the observer.
        val foregroundWait = InstrumentationRegistry.getArguments().getString("waitForForegroundMs")?.toLongOrNull()?.coerceIn(0, 15_000) ?: 0
        val observation = runBlocking { delay(foregroundWait); withTimeout(8_000) { observer.observe(network) } }
        val usable = observation?.usable(System.currentTimeMillis(), "device-test:${network.networkHandle}") == true
        val expected = InstrumentationRegistry.getArguments().getString("expectUsable") == "true"
        assertEquals("WifiInfo available=${observation?.available}; security=${observation?.security}; permission=${observer.permitted()}; identity withheld", expected, usable)
        assertEquals(requests, NetworkTransport.requests.get())
        println("Actual WifiInfo on request Network: usable=$usable; expected=$expected; HTTP attempts=0; SSID/BSSID withheld; no binding persisted")
    }
    /** Explicitly selected physical-device check: no reset, credential access or whitelist POST. */
    @Test fun inspectUpgradeAndProxyGuard() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("allowUpgradeRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val c = (context.applicationContext as FuckPo0JiXianApp).controller
        val before = c.store.load()
        assertNull("State migration must be readable", before.globalBlock)
        if (before.slotPlan != null) {
            assertNotNull(before.layout)
            assertEquals(before.slotPlan!!.homeSlot, before.layout!!.slots.first { it.purpose == app.fuckpo0jixian.core.SlotPurpose.FIXED }.number)
            assertTrue(before.layout!!.identities.isEmpty())
        }
        val requests = NetworkTransport.requests.get()
        assertEquals(requests, NetworkTransport.requests.get())
        assertEquals(before, c.store.load())
        // Leave the authorized test phone safely paused; no remote operations or network settings changed.
        instrumentation.runOnMainSync { c.pause(true) }
        runBlocking { withTimeout(5_000) { c.store.flow.first { it.paused } } }
        println("Upgrade readable; prior configured slots preserved if present" +
            "; HTTP attempts=0; paused=true; credentials and network identities withheld")
    }
    @Test fun syncDedicatedSlots() {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("allowDedicatedSlotWrite") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = (instrumentation.targetContext.applicationContext as FuckPo0JiXianApp).controller
        val before = c.store.load()
        check(c.vault.exists() && !before.demo && !before.authBlocked && before.paused)
        check(System.currentTimeMillis() >= maxOf(before.nextAllowed, before.nextProbeAllowed)) { "Persistent quota preserved" }
        val slot = checkNotNull(args.getString("targetSlot")).toInt()
        val managed = checkNotNull(before.layout).slots.first { it.number == slot }
        check(managed.writer == app.fuckpo0jixian.core.Writer.LOCAL && managed.authorized && managed.automatic)
        instrumentation.runOnMainSync { c.store.save(before.copy(mode = Mode.AUTO, paused = false)) }
        try {
            val code = runBlocking { withTimeout(80_000) { c.runCheck(true) } }
            assertEquals(args.getString("expectedStatus"), code)
            val result = c.store.load()
            check(result.layout!!.pending == null)
            check(result.layout!!.slots.first { it.number == slot }.baseline == result.snapshot!!.current)
            check(result.snapshot!!.entries.any { it.cidr == result.snapshot!!.current && it.slot == slot })
            println("Explicit target slot verified; code=$code; token and network identities withheld")
        } finally {
            instrumentation.runOnMainSync { c.pause(true) }
            runBlocking { withTimeout(5_000) { c.store.flow.first { it.paused } } }
        }
    }
    @Test fun resumeDedicatedSlots() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("allowDedicatedSlotWrite") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = (instrumentation.targetContext.applicationContext as FuckPo0JiXianApp).controller
        val s = c.store.load()
        check(c.vault.exists() && !s.demo && !s.authBlocked && s.globalBlock == null && s.mode == Mode.AUTO)
        check(s.layout?.slots?.any { it.authorized && it.automatic && it.writer == app.fuckpo0jixian.core.Writer.LOCAL } == true)
        instrumentation.runOnMainSync { c.pause(false) }
        runBlocking { withTimeout(5_000) { c.store.flow.first { !it.paused } } }
        println("Authorized stable-slot synchronization enabled; APK safety gates remain active")
    }
    @Test fun scheduleAfterPersistentQuota() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("allowSchedulingRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = (instrumentation.targetContext.applicationContext as FuckPo0JiXianApp).controller
        val before = c.store.load()
        check(c.vault.exists() && !before.demo && !before.authBlocked)
        val due = maxOf(before.nextAllowed, System.currentTimeMillis() + 20_000)
        check(due - System.currentTimeMillis() < 120_000) { "Existing limiter preserved; retry test after its expiry" }
        instrumentation.runOnMainSync {
            c.store.save(before.copy(paused = true, mode = Mode.OBSERVE, lastSuccess = 0, nextAllowed = due))
            c.pause(false)
        }
        try {
            runBlocking { delay(3_000) }
            assertEquals("No early account request", before.lastCheck, c.store.load().lastCheck)
            runBlocking { withTimeout(150_000) {
                c.store.flow.first { it.lastSuccess > before.lastSuccess }
                c.busy.first { !it }
            } }
            assertTrue(c.store.load().lastCheck >= due)
            assertNotNull(c.store.load().snapshot)
        } finally {
            instrumentation.runOnMainSync { c.pause(true) }
            runBlocking { withTimeout(5_000) { c.store.flow.first { it.paused } } }
        }
    }
    @Test fun readDomesticOnly() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("allowRealNetworkProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = (instrumentation.targetContext.applicationContext as FuckPo0JiXianApp).controller
        val before = c.store.load()
        check(!before.demo && System.currentTimeMillis() >= before.nextProbeAllowed) { "Probe blocked by mode or persistent limiter" }
        instrumentation.runOnMainSync { c.store.save(before.copy(paused = false)); c.checkDomestic() }
        try {
            runBlocking { withTimeout(35_000) { c.busy.first { !it } } }
            val result = c.store.load()
            assertEquals("PROBE_OBSERVED", result.probeStatus)
            assertTrue(result.domesticExit!!.time > (before.domesticExit?.time ?: 0))
            assertEquals(before.snapshot, result.snapshot)
        } finally {
            instrumentation.runOnMainSync { c.pause(true) }
            runBlocking { withTimeout(5_000) { c.store.flow.first { it.paused } } }
        }
    }
    @Test fun importPrivateCredential() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("allowCredentialImport") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as FuckPo0JiXianApp
        val file = File(app.noBackupFilesDir, "credential.import")
        try {
            check(!app.controller.vault.exists()) { "Existing encrypted credential preserved" }
            check(file.isFile && file.length() in 8..1024) { "Private import file unavailable" }
            val secret = file.readText().trim()
            app.controller.vault.save(secret)
            check(app.controller.vault.read() == secret) { "Encrypted credential verification failed" }
            println("Credential imported and verified in Android Keystore-backed private storage; value withheld")
        } finally {
            if (file.exists()) check(file.delete()) { "Private plaintext cleanup failed" }
        }
    }
    @Test fun readPlatformOnly() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("allowAccountRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = (instrumentation.targetContext.applicationContext as FuckPo0JiXianApp).controller
        check(c.vault.exists()) { "No encrypted credential" }
        val before = c.store.load()
        check(!before.demo && !before.authBlocked && System.currentTimeMillis() >= before.nextAllowed) { "Read blocked by mode or persistent limiter" }
        instrumentation.runOnMainSync {
            c.store.save(before.copy(paused = false, mode = Mode.OBSERVE))
            c.check()
        }
        try {
            runBlocking { withTimeout(45_000) { c.busy.first { !it } } }
            val result = c.store.load()
            assertTrue("No completed account read", result.lastCheck > before.lastCheck)
            assertNotNull("Account query did not return a valid snapshot; safe code=${result.status}", result.snapshot)
            val snapshot = result.snapshot!!
            println("Account GET only: capacity=${snapshot.capacity}, entries=${snapshot.entries.size}, currentPresent=${snapshot.contains(snapshot.current)}, domesticMatches=${result.domesticExit?.cidr == snapshot.current}; business=NOT_CHECKED")
        } finally {
            instrumentation.runOnMainSync { c.pause(true) }
            runBlocking { withTimeout(5_000) { c.store.flow.first { it.paused } } }
        }
    }
}
