package app.fuckpo0jixian.core
import kotlin.test.*
class DeviceTestPolicyTest {
    @Test fun realPhoneNeverRunsGeneralStateResetTests() {
        for (credential in listOf(true, false)) {
            assertFalse(DeviceTestPolicy.permitted(false, credential, null, emptySet()))
            assertFalse(DeviceTestPolicy.permitted(false, credential, "app.fuckpo0jixian.UiTest", setOf("allowAccountRead")))
            assertFalse(DeviceTestPolicy.permitted(false, credential, "app.fuckpo0jixian.VisualTest", emptySet()))
        }
    }
    @Test fun configuredEmulatorAlsoPreservesCredentials() {
        assertTrue(DeviceTestPolicy.permitted(true, false, null, emptySet()))
        assertFalse(DeviceTestPolicy.permitted(true, true, "app.fuckpo0jixian.UiTest", emptySet()))
    }
    @Test fun realReadRequiresExactMethodAndMatchingOptIn() {
        val selected = "app.fuckpo0jixian.AuthorizedAccountTest#readPlatformOnly"
        assertTrue(DeviceTestPolicy.permitted(false, true, selected, setOf("allowAccountRead")))
        assertFalse(DeviceTestPolicy.permitted(false, true, selected, emptySet()))
        assertFalse(DeviceTestPolicy.permitted(false, true, selected, setOf("allowCredentialImport")))
        assertFalse(DeviceTestPolicy.permitted(false, true, "app.fuckpo0jixian.AuthorizedAccountTest", setOf("allowAccountRead")))
    }
    @Test fun upgradeProxyReadCannotAuthorizeResetOrSlotWrite() {
        assertTrue(DeviceTestPolicy.permitted(false, true, "app.fuckpo0jixian.AuthorizedAccountTest#inspectUpgradeAndProxyGuard", setOf("allowUpgradeRead")))
        assertFalse(DeviceTestPolicy.permitted(false, true, "app.fuckpo0jixian.UiTest", setOf("allowUpgradeRead")))
        assertFalse(DeviceTestPolicy.permitted(false, true, "app.fuckpo0jixian.AuthorizedAccountTest#syncDedicatedSlots", setOf("allowUpgradeRead")))
    }
}
