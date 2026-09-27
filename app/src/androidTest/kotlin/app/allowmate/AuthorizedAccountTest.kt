package app.allowmate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.allowmate.core.Mode
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
    @Test fun syncDedicatedSlots() {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("allowDedicatedSlotWrite") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = (instrumentation.targetContext.applicationContext as AllowMateApp).controller
        val before = c.store.load()
        check(c.vault.exists() && !before.demo && !before.authBlocked && before.paused)
        check(System.currentTimeMillis() >= maxOf(before.nextAllowed, before.nextProbeAllowed)) { "Persistent quota preserved" }
        val plan = before.slotPlan ?: app.allowmate.core.SlotPlan(
            app.allowmate.core.Cidr(checkNotNull(args.getString("homeCidr"))),
            checkNotNull(args.getString("homeSlot")).toInt(), checkNotNull(args.getString("mobileSlot")).toInt())
        instrumentation.runOnMainSync { c.store.save(before.copy(slotPlan = plan, mode = Mode.AUTO, paused = false)) }
        try {
            val code = runBlocking { withTimeout(80_000) { c.runCheck(true) } }
            assertEquals(args.getString("expectedStatus"), code)
            val result = c.store.load()
            check(result.slotPlan!!.homeReady)
            check(result.snapshot!!.entries.any { it.cidr == plan.home && it.slot == plan.homeSlot })
            println("Dedicated slot sync verified; code=$code; homeProtected=true; capacity=${result.snapshot!!.capacity}; entries=${result.snapshot!!.entries.size}; token withheld")
        } finally {
            instrumentation.runOnMainSync { c.pause(true) }
            runBlocking { withTimeout(5_000) { c.store.flow.first { it.paused } } }
        }
    }
    @Test fun resumeDedicatedSlots() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("allowDedicatedSlotWrite") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = (instrumentation.targetContext.applicationContext as AllowMateApp).controller
        val s = c.store.load()
        check(c.vault.exists() && !s.demo && !s.authBlocked && s.slotPlan?.homeReady == true && s.mode == Mode.AUTO)
        check(s.snapshot!!.entries.any { it.cidr == s.slotPlan!!.home && it.slot == s.slotPlan!!.homeSlot })
        instrumentation.runOnMainSync { c.pause(false) }
        runBlocking { withTimeout(5_000) { c.store.flow.first { !it.paused } } }
        println("Dedicated-slot automatic network sync enabled; home guard active")
    }
    @Test fun scheduleAfterPersistentQuota() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("allowSchedulingRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = (instrumentation.targetContext.applicationContext as AllowMateApp).controller
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
        val c = (instrumentation.targetContext.applicationContext as AllowMateApp).controller
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
        val app = instrumentation.targetContext.applicationContext as AllowMateApp
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
        val c = (instrumentation.targetContext.applicationContext as AllowMateApp).controller
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
