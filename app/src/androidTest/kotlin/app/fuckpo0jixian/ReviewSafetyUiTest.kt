package app.fuckpo0jixian

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fuckpo0jixian.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

/** SafeTestRunner: dedicated, credential-free emulator only; demo never reaches HTTP. */
@RunWith(AndroidJUnit4::class)
class ReviewSafetyUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val c get() = (rule.activity.application as FuckPo0JiXianApp).controller
    private fun demo() {
        val account = c.store.load().accountContext
        rule.runOnUiThread { c.demo(true) }
        rule.waitUntil { c.store.load().demo && c.store.load().accountContext != account }
        rule.onNodeWithTag("tab-0").performClick()
    }
    @Test fun readOnlyRecoveryButtonWorksWhilePausedWithoutHttpOrReplay() {
        demo()
        val requests = NetworkTransport.requests.get()
        rule.runOnUiThread {
            val s = c.store.load()
            val snap = s.snapshot!!
            val pending = SlotPending(s.accountContext, s.layout!!.version, 0, "FIXED",
                snap.entries.first { it.slot == 0 }.cidr, Cidr("198.18.1.0/24"), null, snap, 1)
            c.store.save(s.copy(globalBlock = "SLOT_VERIFY_FAILED", status = "SLOT_VERIFY_FAILED",
                layout = s.layout!!.copy(pending = pending), nextAllowed = 0))
            c.feedback.value = ""
            rule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val entries = c.store.load().snapshot!!.entries
        rule.onNodeWithTag("page-list").performScrollToNode(hasText("只读复核保护状态"))
        rule.onNodeWithText("只读复核保护状态").assertIsDisplayed().assertIsEnabled()
        val dir = File(rule.activity.getExternalFilesDir(null), "review-qa").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir, "recovery.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithText("只读复核保护状态").performClick()
        rule.waitUntil { c.store.load().globalBlock == null }
        assertEquals("RECOVERED_NOT_APPLIED", c.store.load().status)
        assertTrue(c.store.load().paused); assertNull(c.store.load().layout!!.pending)
        assertEquals(entries, c.store.load().snapshot!!.entries)
        assertEquals(requests, NetworkTransport.requests.get())
    }
    @Test fun persistedServerWaitSurvivesDisplayChangesAndControllerManualCheck() {
        demo()
        val requests = NetworkTransport.requests.get()
        val deadline = System.currentTimeMillis() + 7_200_000
        rule.runOnUiThread {
            c.store.save(c.store.load().copy(status = "PENDING_REVIEW", serverNotBefore = deadline, nextAllowed = deadline))
            c.store.save(LocalStore(rule.activity).load().copy(status = "TOKEN_SAVED"))
            c.checkConnection()
        }
        rule.waitUntil { c.feedback.value == statusText("RATE_LIMITED") }
        assertEquals(deadline, LocalStore(rule.activity).load().serverNotBefore)
        assertEquals(requests, NetworkTransport.requests.get())
    }
    @Test fun androidSameTokenKeepsAuthorityChangedTokenClearsIt() {
        demo()
        val requests = NetworkTransport.requests.get()
        rule.runOnUiThread { c.vault.save("pgnfw_SYNTHETIC_ONLY"); c.credentialPresent.value = true }
        val original = c.store.load()
        try {
            rule.runOnUiThread { c.saveToken("pgnfw_SYNTHETIC_ONLY") }
            rule.waitUntil { c.store.load().status == "TOKEN_SAVED" }
            assertEquals(original.layout, LocalStore(rule.activity).load().layout)
            assertEquals(original.accountContext, c.store.load().accountContext)
            rule.runOnUiThread { c.saveToken("pgnfw_DIFFERENT_SYNTHETIC") }
            rule.waitUntil { c.store.load().accountContext != original.accountContext && c.store.load().status == "TOKEN_SAVED" }
            assertTrue(LocalStore(rule.activity).load().paused)
            assertNull(LocalStore(rule.activity).load().layout)
            assertEquals(requests, NetworkTransport.requests.get())
        } finally {
            rule.runOnUiThread { c.vault.clear(); c.credentialPresent.value = false; c.store.save(State(demo = true)) }
        }
    }
}
