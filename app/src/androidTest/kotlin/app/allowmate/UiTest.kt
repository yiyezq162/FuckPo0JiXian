package app.allowmate

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.allowmate.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val controller get() = (rule.activity.application as AllowMateApp).controller
    @Test fun runtimeModeSelectionKeepsLimitsSlotsAndUsesSeparateActualStatus() {
        val original = State(nextAllowed = 900_000, nextProbeAllowed = 800_000,
            slotPlan = SlotPlan(Cidr("192.0.2.0/24"), 0, 1, true))
        rule.runOnUiThread { controller.store.save(original) }
        rule.onNodeWithText("Po0").performClick()
        rule.onNodeWithTag("page-list").performScrollToNode(hasText("模块增强（Magisk／KernelSU）"))
        rule.onNodeWithText("模块增强（Magisk／KernelSU）").performClick()
        rule.waitUntil { controller.store.load().runtimeMode == RuntimeMode.MODULE }
        assertEquals(original, LocalStore(rule.activity).load().copy(runtimeMode = RuntimeMode.STANDARD))
        rule.onNodeWithText("已保存：模块增强").assertExists()
        rule.onNodeWithText("标准模式（免 root）").performClick()
        rule.waitUntil { controller.store.load().runtimeMode == RuntimeMode.STANDARD }
        assertEquals(original, LocalStore(rule.activity).load())
    }
    @Test fun po0ConfigurationPreservesAccountAndPausesWithoutServerWrite() {
        val home = Cidr("198.51.100.0/24")
        val mobile = Cidr("203.0.113.0/24")
        val plan = SlotPlan(home, 0, 1, homeReady = true, lastMobile = mobile)
        val snapshot = Snapshot(home, listOf(Entry(home, 0), Entry(mobile, 1)), 5)
        rule.runOnUiThread { controller.store.save(State(slotPlan = plan, snapshot = snapshot, mode = Mode.AUTO, paused = false)) }
        rule.onNodeWithText("Po0").performClick()
        rule.onNodeWithTag("page-list").performScrollToNode(hasText("修改槽位配置"))
        rule.onNodeWithText("修改槽位配置").performClick()
        rule.onNodeWithText("保存并暂停").performClick()
        rule.waitUntil { controller.store.load().status == "SLOT_CONFIGURED" }
        assertTrue(controller.store.load().paused)
        assertEquals(snapshot, controller.store.load().snapshot)
        assertEquals(plan, LocalStore(rule.activity).load().slotPlan)
    }
    @Test fun tokenDialogAcceptsOfficialLinkButCancelDoesNotChangeCredential() {
        rule.runOnUiThread { controller.store.save(State()) }
        rule.onNodeWithText("Po0").performClick()
        rule.onNodeWithText("添加 Token").performClick()
        rule.onNodeWithText("Token 或接口链接").performTextInput("https://124.221.69.228/api/firewall/pgnfw_LOCAL_TEST/add")
        rule.onNodeWithText("加密保存").assertIsEnabled()
        rule.onNodeWithText("取消").performClick()
        assertFalse(controller.vault.exists())
    }
    @Test fun dedicatedSlotsHaveSimpleReadOnlyManagementAndObserveSwitch() {
        val plan = SlotPlan(Cidr("198.51.100.0/24"), 0, 1, homeReady = true)
        rule.runOnUiThread { controller.store.save(State(slotPlan = plan, paused = true, mode = Mode.AUTO)) }
        rule.onNodeWithText("白名单").performClick()
        rule.onNodeWithText("Po0 白名单").assertExists()
        rule.onNodeWithText("保存预算").assertDoesNotExist()
        rule.onNodeWithText("Po0").performClick()
        rule.onNodeWithTag("page-list").performScrollToNode(hasContentDescription("自动同步"))
        rule.onNodeWithContentDescription("自动同步").assertIsOn()
        assertEquals(plan, LocalStore(rule.activity).load().slotPlan)
    }
    @Test fun initialStateDoesNotClaimSuccessAndFourPagesReachable() {
        rule.runOnUiThread { controller.store.save(State()) }
        rule.onNodeWithText("尚未检查").assertExists()
        rule.onNodeWithText("立即检查").assertIsNotEnabled()
        rule.onNodeWithText("首页").assertExists()
        rule.onNodeWithText("白名单").performClick()
        rule.onNodeWithText("Po0 白名单").assertExists()
        rule.onNodeWithText("Po0").performClick()
        rule.onNodeWithText("Po0 连接").assertExists()
        rule.onNodeWithText("诊断").performClick()
        rule.onNodeWithText("检查状态").assertExists()
    }
    @Test fun demoObserveCheckDoesNotWriteAndSurvivesStoreReload() {
        rule.runOnUiThread { controller.store.save(State(demo = true, paused = false)) }
        rule.onNodeWithText("立即检查").performClick()
        rule.waitUntil(10_000) { controller.store.load().status == "OBSERVED_MISSING" }
        rule.onAllNodesWithText("观察到当前 /24 未收录").onFirst().assertExists()
        val persisted = LocalStore(rule.activity).load()
        assertEquals("OBSERVED_MISSING", persisted.status)
        assertEquals(1, persisted.snapshot!!.entries.size)
        assertTrue(persisted.ownership.isEmpty())
        rule.onNodeWithText("立即检查").performClick()
        rule.waitUntil(5_000) { controller.feedback.value.contains("限频") }
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
        rule.onNodeWithText("诊断").performClick()
        rule.onNodeWithTag("page-list").performScrollToNode(hasText("203.0.113.0/24"))
        rule.onNodeWithText("203.0.113.0/24").assertIsDisplayed()
    }
}
