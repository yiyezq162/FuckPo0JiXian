package app.allowmate

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.allowmate.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only synthetic data. Screenshot restriction is cleared in test process only. */
@RunWith(AndroidJUnit4::class)
class VisualTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun captureFourPagesWithSyntheticData() {
        val c = (rule.activity.application as AllowMateApp).controller
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
            c.store.save(State(paused = true, mode = Mode.AUTO, snapshot = Snapshot(Cidr("203.0.113.0/24"),
                listOf(Entry(Cidr("198.51.100.0/24"), 0), Entry(Cidr("203.0.113.0/24"), 1)), 5),
                slotPlan = SlotPlan(Cidr("198.51.100.0/24"), 0, 1, true, Cidr("203.0.113.0/24")),
                networkKey = c.currentNetworkKey.value, status = "PRESENT_CURRENT_CHECK", lastCheck = 1_790_440_000_000, lastSuccess = 1_790_440_000_000))
        }
        try {
            listOf("首页", "白名单", "Po0", "诊断").forEachIndexed { index, page ->
                rule.onNodeWithText(page).performClick()
                capture("page-$index.png")
            }
            rule.onNodeWithText("Po0").performClick()
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("模块增强（Magisk／KernelSU）"))
            rule.onNodeWithText("模块增强（Magisk／KernelSU）").performClick()
            rule.waitUntil { c.store.load().runtimeMode == RuntimeMode.MODULE }
            capture("runtime-mode.png")
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("模块诊断与安装说明"))
            rule.onNodeWithText("模块诊断与安装说明").performClick()
            rule.onNodeWithText("最近连接报告（历史）").performScrollTo()
            capture("runtime-evidence.png")
            rule.onNodeWithText("最近执行结果（历史）").performScrollTo()
            capture("runtime-result-history.png")
            rule.onNodeWithText("模块诊断与安装说明").performScrollTo().performClick()
            rule.onNodeWithTag("page-list").performScrollToNode(hasText("修改槽位配置"))
            rule.onNodeWithText("修改槽位配置").performClick()
            capture("slot-dialog.png")
            rule.onNodeWithText("取消").performClick()
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
