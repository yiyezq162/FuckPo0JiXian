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
import java.io.File

/** Overview screenshots for design review: synthetic demo data and counters, no credential, no requests. */
@RunWith(AndroidJUnit4::class)
class DesignShotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun captureOverview() {
        val c = (rule.activity.application as FuckPo0JiXianApp).controller
        val dir = File(rule.activity.getExternalFilesDir(null), "design").apply { mkdirs() }
        val night = (rule.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val suffix = if (night) "-dark" else ""
        fun capture(name: String) {
            rule.waitForIdle()
            Thread.sleep(1_400) // entrance and count-up animations settle
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            rule.waitUntil(5_000) { automation.rootInActiveWindow?.packageName?.toString() == rule.activity.packageName }
            File(dir, "$name$suffix.png").outputStream().use { checkNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val now = System.currentTimeMillis()
        rule.runOnUiThread {
            rule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            c.feedback.value = ""
            c.tally.update { Tally(ipUpdates = 37, checks = 4_812, networkChanges = 296, since = now - 21L * 86_400_000, lastUpdate = now - 5_400_000) }
            c.store.save(State(demo = true, paused = false, mode = Mode.AUTO, deviceName = "OPPO Find X8 Ultra",
                snapshot = Snapshot(Cidr("112.94.13.0/24"), listOf(Entry(Cidr("112.94.13.0/24"), 0), Entry(Cidr("14.31.200.0/24"), 1),
                    Entry(Cidr("14.19.66.0/24"), 2)), 5),
                layout = SlotLayout(slots = listOf(
                    ManagedSlot(0, "家里", SlotPurpose.FIXED, Writer.LOCAL, authorized = true, shared = true, status = "SLOT_CURRENT"),
                    ManagedSlot(1, "手机", SlotPurpose.MOBILE, Writer.LOCAL, automatic = true, authorized = true, status = "SLOT_CURRENT"),
                    ManagedSlot(2, "公司", SlotPurpose.FIXED, Writer.OTHER_DEVICE, owner = "Windows", status = "PEER_UPDATED"))),
                networkKey = "demo", status = "SLOT_CURRENT", lastCheck = now - 180_000,
                domesticExit = DomesticExit("112.94.13.87", now - 180_000, "demo", ProbeSource.STUN), probeStatus = "PROBE_OBSERVED",
                observations = (0 until 30).map { Observation(now - it * 5_000_000L,
                    when (it % 5) { 0 -> Cidr("14.31.200.0/24"); 3 -> Cidr("183.46.2.0/24"); else -> Cidr("112.94.13.0/24") }, "wifi") },
                events = listOf("SLOT_UPDATED", "EGRESS_UNVERIFIED", "SLOT_CURRENT", "PEER_UPDATED", "SLOT_UPDATED", "SLOT_CURRENT", "LOCAL_UNCHANGED")
                    .mapIndexed { i, code -> Event(now - (7 - i) * 2_400_000L, code) }))
        }
        try {
            rule.onNodeWithTag("tab-0").performClick()
            capture("overview")
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("查看槽位"))
            capture("overview-scrolled")
            rule.runOnUiThread { c.store.save(c.store.load().copy(paused = true)) }
            rule.onNodeWithTag("page-list").performScrollToIndex(0)
            capture("overview-paused")
            (1..3).forEach { rule.onNodeWithTag("tab-$it").performClick(); capture("page-$it") }
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("导出调试信息"))
            capture("page-3-end")
            rule.onNodeWithTag("tab-1").performClick()
            rule.onNodeWithTag("page-list").performScrollToNode(hasTestTag("slot-3"))
            capture("page-1-slots")
            rule.onNodeWithTag("slot-1").performClick()
            capture("slot-editor")
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithTag("tab-2").performClick()
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("标准模式"))
            capture("page-2-mode")
        } finally { rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
    }
}
