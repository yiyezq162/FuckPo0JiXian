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

/** Only synthetic data. Screenshot restriction is cleared in test process only. */
@RunWith(AndroidJUnit4::class)
class VisualTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun captureFourPagesWithSyntheticData() {
        val c = (rule.activity.application as FuckPo0JiXianApp).controller
        val dir = File(rule.activity.getExternalFilesDir(null), "qa").apply { mkdirs() }
        fun capture(name: String) {
            rule.waitForIdle()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            // Accessibility connects asynchronously; wait for the app's actual active window.
            rule.waitUntil(5_000) { automation.rootInActiveWindow?.packageName?.toString() == rule.activity.packageName }
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.runOnUiThread {
            rule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            c.feedback.value = ""
            c.vault.save("pgnfw_VISUAL_TEST_ONLY")
            c.credentialPresent.value = true
            // Synthetic dedicated-slot configuration; no credential, paused, no requests.
            c.store.save(State(demo = true, paused = true, mode = Mode.AUTO, snapshot = Snapshot(Cidr("203.0.113.0/24"),
                listOf(Entry(Cidr("198.51.100.0/24"), 0), Entry(Cidr("203.0.113.0/24"), 1), Entry(Cidr("192.0.2.0/24"))), 5),
                deviceName = "示例手机",
                layout = SlotLayout(slots = listOf(ManagedSlot(0, "家庭示例", SlotPurpose.FIXED, Writer.LOCAL, status = "IDENTITY_REQUIRED", shared = true),
                    ManagedSlot(1, "公司示例", SlotPurpose.FIXED, Writer.LOCAL),
                    ManagedSlot(2, "本机移动", SlotPurpose.MOBILE, Writer.LOCAL),
                    ManagedSlot(3, "笔记本外出", SlotPurpose.MOBILE, Writer.OTHER_DEVICE, owner = "Mac", status = "PEER_UPDATED",
                        changedAt = System.currentTimeMillis()), ManagedSlot(4, "保留用途"))),
                networkKey = "demo", status = "PRESENT_CURRENT_CHECK", lastCheck = System.currentTimeMillis() - 180_000,
                domesticExit = DomesticExit("203.0.113.87", System.currentTimeMillis() - 180_000, "demo", ProbeSource.IP3322),
                probeStatus = "PROBE_OBSERVED",
                observations = (0 until 12).map { Observation(System.currentTimeMillis() - it * 7_200_000L,
                    if (it % 3 == 0) Cidr("192.0.2.0/24") else Cidr("203.0.113.0/24"), "wifi") },
                events = listOf("SLOT_UPDATED", "PEER_UPDATED", "PRESENT_CURRENT_CHECK", "LOCAL_UNCHANGED", "PRESENT_CURRENT_CHECK", "SLOT_CURRENT")
                    .mapIndexed { i, code -> Event(System.currentTimeMillis() - (6 - i) * 1_800_000L, code) }))
        }
        try {
            (0..3).forEach { index ->
                rule.onNodeWithTag("tab-$index").performClick()
                capture("page-$index.png")
            }
            rule.onNodeWithTag("tab-2").performClick()
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("模块增强"))
            rule.onNodeWithText("模块增强").performClick()
            rule.waitUntil { c.store.load().runtimeMode == RuntimeMode.MODULE }
            capture("runtime-mode.png")
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("诊断与安装说明"))
            rule.onNodeWithText("诊断与安装说明").performClick()
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("最近连接"))
            capture("runtime-evidence.png")
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("最近结果"))
            capture("runtime-result-history.png")
            rule.onNodeWithText("诊断与安装说明").performClick()
            rule.onNodeWithTag("tab-1").performClick()
            rule.onNodeWithTag("page-list").performScrollToNode(hasTestTag("slot-1"))
            rule.onNodeWithTag("slot-1").performClick()
            capture("slot-dialog.png")
            rule.onNodeWithText("保存只修改本机配置，开启自动同步后才会写入 Po0。").performScrollTo()
            capture("slot-authorization.png")
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithTag("page-list").performScrollToNode(hasTestTag("slot-4"))
            rule.onNodeWithTag("slot-4").performClick()
            capture("slot-other-device.png")
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithTag("tab-2").performClick()
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("从剪贴板导入其他设备的分工"))
            capture("multi-device.png")
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("导出脱敏数据"))
            rule.onNodeWithText("导出脱敏数据").performClick()
            capture("export-dialog.png")
            rule.onAllNodesWithText("取消").onLast().performClick()
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("更换 Token"))
            rule.onNodeWithText("更换 Token").performClick()
            capture("token-dialog.png")
            rule.onNodeWithText("取消").performClick()
        } finally { rule.runOnUiThread {
            c.vault.clear(); c.credentialPresent.value = false
            rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } }
    }
}
