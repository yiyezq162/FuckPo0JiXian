package app.allowmate

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.allowmate.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class SlotUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val c get() = (rule.activity.application as AllowMateApp).controller
    private fun demo() {
        val previous = c.store.load().accountContext
        rule.runOnUiThread { c.demo(true) }
        rule.waitUntil(5_000) { c.store.load().demo && c.store.load().accountContext != previous && c.store.load().layout?.identities?.size == 2 }
        rule.onNodeWithTag("tab-1").performClick()
    }
    private fun open(number: Int) {
        rule.onNodeWithTag("page-list").performScrollToNode(hasTestTag("slot-$number"))
        rule.onNodeWithTag("slot-$number").performClick()
    }
    @Test fun bindAndRevokeAreLocalAndUnknownWifiDefaultIsOff() {
        demo(); val before = c.store.load().snapshot; val requests = NetworkTransport.requests.get()
        open(1)
        rule.onNodeWithText("绑定当前 Wi-Fi").performScrollTo().performClick()
        rule.onNodeWithText("授权并绑定").performClick()
        rule.waitUntil { c.store.load().layout!!.version > 1 }
        assertEquals(before, c.store.load().snapshot); assertEquals(requests, NetworkTransport.requests.get())
        open(1); rule.onNodeWithText("撤销绑定").performScrollTo().performClick()
        rule.waitUntil { c.store.load().layout!!.slots.first { it.number == 0 }.identityId == null }
        open(3)
        rule.onNodeWithContentDescription("未知 Wi-Fi 也可更新").performScrollTo().assertIsOff()
        rule.onNodeWithText("取消").performClick()
    }
    @Test fun unassignedRecordCountsTowardFullCapacityAndOtherPhoneHasNoLocalRights() {
        demo()
        rule.onNodeWithText("已用 5 / 5").assertExists()
        rule.onNodeWithText("剩余 0").assertExists()
        rule.onNodeWithTag("page-list").performScrollToNode(hasText("未分配槽号"))
        rule.onNodeWithText("未分配槽号").assertExists()
        open(4)
        rule.onNode(hasText("其他设备") and isSelected()).assertExists()
        rule.onNodeWithText("绑定当前 Wi-Fi").assertDoesNotExist()
        rule.onNodeWithText("取消").performClick()
        assertFalse(c.store.load().layout!!.slots.first { it.number == 3 }.authorized)
    }
    @Test fun permissionMissingDemoBlocksBindingWithoutRemovingManualEntry() {
        demo()
        repeat(5) {
            val prior = c.store.load().status
            rule.runOnUiThread { c.nextDemoNetwork() }
            rule.waitUntil { c.store.load().status != prior }
        }
        open(1)
        rule.onNodeWithText("绑定当前 Wi-Fi").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("手动更新一次").performScrollTo().assertExists()
        rule.onNodeWithText("取消").performClick()
    }
    @Test fun manualPreviewIsSessionBoundAndCancelDoesNotWrite() {
        demo()
        rule.runOnUiThread { c.store.save(c.store.load().copy(paused = false, mode = Mode.OBSERVE, nextAllowed = 0)) }
        val before = c.store.load().snapshot!!.entries
        open(1)
        rule.onNodeWithText("手动更新一次").performScrollTo().performClick()
        rule.waitUntil(10_000) { c.manualPreview.value != null }
        rule.onNodeWithText("确认更新").assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.waitUntil { c.manualPreview.value == null }
        assertEquals(before, c.store.load().snapshot!!.entries)
    }
    @Test fun v1MigratesInPlaceAndCorruptionCannotBeOverwritten() {
        val path = File(rule.activity.noBackupFilesDir, "state-v1.json")
        val old = State(slotPlan = SlotPlan(Cidr("192.0.2.0/24"), 3, 4, false, pendingMobile = Cidr("198.51.100.0/24")))
        val raw = StateCodec.encode(old).replace("\"version\":2", "\"version\":1")
        rule.runOnUiThread { c.store.save(State()) }
        path.writeText(raw)
        val migrated = LocalStore(rule.activity).load()
        assertEquals(listOf(3, 4), migrated.layout!!.slots.map { it.number })
        assertEquals(migrated, LocalStore(rule.activity).load())
        assertTrue(File(rule.activity.noBackupFilesDir, "state-v1.recovery.json").exists())
        val valid = path.readText()
        try {
            path.writeText("{invalid")
            val broken = LocalStore(rule.activity)
            assertEquals("STORAGE_RECOVERY_REQUIRED", broken.load().status)
            assertTrue(runCatching { broken.save(State()) }.isFailure)
            assertEquals("{invalid", path.readText())
        } finally { path.writeText(valid); rule.runOnUiThread { c.store.save(migrated) } }
    }
    @Test fun demoSwitchAndCredentialClearDropAllSlotAuthority() {
        demo()
        val old = c.store.load().accountContext
        rule.runOnUiThread { c.demo(false) }
        rule.waitUntil { !c.store.load().demo }
        assertNull(c.store.load().layout); assertNotEquals(old, c.store.load().accountContext)
        rule.runOnUiThread { c.clearToken() }
        rule.waitUntil { c.store.load().status == "NO_TOKEN" }
        assertNull(c.store.load().layout); assertNull(c.manualPreview.value)
    }
    @Test fun authorizingFixedSlotStaysOpenOnWifiBindingStep() {
        demo(); open(5)
        rule.onNodeWithText("固定网络").performScrollTo().performClick()
        rule.onNodeWithText("本机").performScrollTo().performClick()
        rule.onNodeWithText("保存后可绑定").performScrollTo().assertExists()
        rule.onNodeWithContentDescription("授权本机管理").performScrollTo().performClick()
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil { c.store.load().layout!!.slots.first { it.number == 4 }.authorized }
        rule.onNodeWithText("完成").assertExists()
        rule.onNodeWithText("绑定当前 Wi-Fi").performScrollTo().assertIsEnabled()
        assertNull(c.store.load().layout!!.slots.first { it.number == 4 }.identityId)
        rule.onNodeWithText("完成").performClick()
        rule.onNodeWithTag("page-list").assertExists()
    }
    @Test fun secondAutomaticMobileSlotShowsActionableErrorAndKeepsEditor() {
        demo(); open(5)
        rule.onNodeWithTag("slot-name").performTextReplacement("保留我的输入")
        rule.onNodeWithText("设备移动").performScrollTo().performClick()
        rule.onNodeWithText("本机").performScrollTo().performClick()
        rule.onNodeWithContentDescription("自动更新此槽").performScrollTo().performClick()
        rule.onNodeWithContentDescription("授权本机管理").performScrollTo().performClick()
        rule.onNodeWithText("保存").performClick()
        rule.onNodeWithText(statusText("CONFIG_MOBILE_LIMIT")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("保留我的输入").assertExists()
        assertEquals(1, c.store.load().layout!!.slots.count { it.writer == Writer.LOCAL && it.purpose == SlotPurpose.MOBILE && it.automatic })
        rule.onNodeWithText("取消").performClick()
    }
}
