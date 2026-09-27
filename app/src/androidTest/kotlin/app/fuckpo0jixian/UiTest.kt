package app.fuckpo0jixian

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fuckpo0jixian.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val controller get() = (rule.activity.application as FuckPo0JiXianApp).controller
    @Test fun runtimeModeSelectionKeepsLimitsSlotsAndUsesSeparateActualStatus() {
        val original = State(nextAllowed = 900_000, nextProbeAllowed = 800_000,
            slotPlan = SlotPlan(Cidr("192.0.2.0/24"), 0, 1, true))
        rule.runOnUiThread { controller.store.save(original) }
        rule.onNodeWithTag("tab-2").performClick()
        rule.onNodeWithTag("page-list").performScrollToNode(hasText("模块增强"))
        rule.onNodeWithText("模块增强").performClick()
        rule.waitUntil { controller.store.load().runtimeMode == RuntimeMode.MODULE }
        assertEquals(original, LocalStore(rule.activity).load().copy(runtimeMode = RuntimeMode.STANDARD))
        rule.onNode(hasText("模块增强") and isSelected()).assertExists()
        rule.onNodeWithText("标准模式").performClick()
        rule.waitUntil { controller.store.load().runtimeMode == RuntimeMode.STANDARD }
        assertEquals(original, LocalStore(rule.activity).load())
    }
    @Test fun fiveSlotConfigurationPreservesRemoteAndPersistsLocallyWithoutRequest() {
        val home = Cidr("198.51.100.0/24")
        val mobile = Cidr("203.0.113.0/24")
        val plan = SlotPlan(home, 0, 1, homeReady = true, lastMobile = mobile)
        val snapshot = Snapshot(home, listOf(Entry(home, 0), Entry(mobile, 1)), 5)
        rule.runOnUiThread { controller.store.save(LayoutRules.migrate(State(slotPlan = plan, snapshot = snapshot, lastCheck = System.currentTimeMillis()))) }
        val requests = NetworkTransport.requests.get()
        rule.onNodeWithTag("tab-1").performClick()
        for (slot in 1..5) {
            rule.onNodeWithTag("page-list").performScrollToNode(hasTestTag("slot-$slot"))
            rule.onNodeWithTag("slot-$slot").performClick()
            rule.onNodeWithTag("slot-name").performTextReplacement("测试槽 $slot")
            rule.onNodeWithText("保存").performClick()
            rule.waitUntil { controller.store.load().layout?.slots?.any { it.number == slot - 1 && it.name == "测试槽 $slot" } == true }
            // An authorized fixed slot without Wi-Fi stays open on the binding step.
            if (rule.onAllNodesWithText("完成").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithText("完成").performClick()
        }
        assertTrue(controller.store.load().paused)
        assertEquals(snapshot, controller.store.load().snapshot)
        assertEquals(5, LocalStore(rule.activity).load().layout!!.slots.size)
        assertEquals(requests, NetworkTransport.requests.get())
    }
    @Test fun tokenDialogAcceptsOfficialLinkButCancelDoesNotChangeCredential() {
        rule.runOnUiThread { controller.store.save(State()) }
        rule.onNodeWithTag("tab-2").performClick()
        rule.onNodeWithTag("page-list").performScrollToNode(hasText("添加 Token"))
        rule.onNodeWithText("添加 Token").performClick()
        rule.onNodeWithTag("token-input").performTextInput("https://124.221.69.228/api/firewall/pgnfw_LOCAL_TEST/add")
        rule.onNodeWithText("保存").assertIsEnabled()
        rule.onNodeWithText("取消").performClick()
        assertFalse(controller.vault.exists())
    }
    @Test fun dedicatedSlotsHaveSimpleReadOnlyManagementAndObserveSwitch() {
        val plan = SlotPlan(Cidr("198.51.100.0/24"), 0, 1, homeReady = true)
        rule.runOnUiThread { controller.store.save(State(slotPlan = plan, paused = true, mode = Mode.AUTO)) }
        rule.onNodeWithTag("tab-1").performClick()
        rule.onNodeWithText("尚未获取白名单").assertExists()
        rule.onNodeWithText("保存预算").assertDoesNotExist()
        rule.onNodeWithTag("tab-2").performClick()
        rule.onNodeWithTag("page-list").performScrollToNode(hasContentDescription("自动同步"))
        rule.onNodeWithContentDescription("自动同步").assertIsOn()
        assertEquals(plan, LocalStore(rule.activity).load().slotPlan)
    }
    @Test fun initialStateDoesNotClaimSuccessAndFourPagesReachable() {
        rule.runOnUiThread { controller.store.save(State()) }
        rule.onNodeWithText("尚未检查").assertExists()
        rule.onNodeWithText("立即检查").assertIsNotEnabled()
        rule.onNodeWithTag("tab-0").assertIsSelected()
        rule.onNodeWithTag("tab-1").performClick()
        rule.onNodeWithText("尚未获取白名单").assertExists()
        rule.onNodeWithTag("tab-2").performClick()
        rule.onNodeWithText("Po0 账户").assertExists()
        rule.onNodeWithTag("tab-3").performClick()
        rule.onNodeWithText("下次可请求").assertExists()
    }
    @Test fun demoObserveCheckDoesNotWriteAndSurvivesStoreReload() {
        val previous = controller.store.load().accountContext
        rule.runOnUiThread { controller.demo(true) }
        rule.waitUntil(5_000) { controller.store.load().demo && controller.store.load().accountContext != previous }
        rule.runOnUiThread { controller.store.save(controller.store.load().copy(paused = false, mode = Mode.OBSERVE, nextAllowed = 0)) }
        rule.onNodeWithText("立即检查").performClick()
        rule.waitUntil(10_000) { controller.store.load().status == "OBSERVED_MISSING" }
        rule.onAllNodesWithText(statusText("OBSERVED_MISSING")).onFirst().assertExists()
        val persisted = LocalStore(rule.activity).load()
        assertEquals("OBSERVED_MISSING", persisted.status)
        assertEquals(5, persisted.snapshot!!.entries.size)
        assertTrue(persisted.ownership.isEmpty())
        // A second manual press runs straight away: manual checks skip the automatic loop guard.
        val first = controller.store.load().lastCheck
        rule.onNodeWithText("立即检查").performClick()
        rule.waitUntil(10_000) { controller.store.load().lastCheck > first && !controller.busy.value }
        assertEquals("OBSERVED_MISSING", controller.store.load().status)
        assertEquals(5, controller.store.load().snapshot!!.entries.size)
    }
    @Test fun pauseAndResumeUi() {
        rule.runOnUiThread { controller.store.save(State(demo = true, paused = true)) }
        rule.onNodeWithText("立即检查").assertIsNotEnabled()
        rule.onNodeWithText("恢复检查").performClick()
        rule.waitUntil(5_000) { !controller.store.load().paused }
        rule.onNodeWithText("立即检查").assertIsEnabled()
        rule.onNodeWithText("暂停检查").performClick()
        rule.waitUntil(5_000) { controller.store.load().paused }
    }
    @Test fun keystoreRoundTripAndNoPlaintextInState() {
        val fake = "pgnfw_LOCAL_TEST_ONLY"
        val vault = TokenVault(rule.activity)
        try {
            vault.save(fake); assertEquals(fake, vault.read())
            assertFalse(java.io.File(rule.activity.noBackupFilesDir, "credential.enc").readText().contains(fake))
            assertFalse(StateCodec.encode(controller.store.load()).contains(fake))
        } finally { vault.clear() }
    }
    @Test fun expiredObservationsArePrunedOnProcessStoreLoad() {
        val expired = System.currentTimeMillis() - 8 * 86_400_000L
        rule.runOnUiThread { controller.store.save(State(demo = true,
            observations = listOf(Observation(expired, Cidr("203.0.113.0/24"), "test")),
            events = listOf(Event(expired, "OLD")))) }
        val restored = LocalStore(rule.activity)
        assertTrue(restored.load().observations.isEmpty())
        assertTrue(restored.load().events.isEmpty())
        assertTrue(LocalStore(rule.activity).load().observations.isEmpty())
    }
    @Test fun recentSubnetHistoryIsVisibleWithoutSixteenBitGrouping() {
        rule.runOnUiThread { controller.store.save(State(demo = true, observations = listOf(
            Observation(System.currentTimeMillis(), Cidr("203.0.113.0/24"), "test")))) }
        rule.onNodeWithTag("tab-3").performClick()
        rule.onNodeWithTag("page-list").performScrollToNode(hasText("203.0.113.0/24"))
        rule.onNodeWithText("203.0.113.0/24").assertIsDisplayed()
    }
}
