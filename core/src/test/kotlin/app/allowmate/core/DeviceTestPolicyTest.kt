package app.allowmate.core
import kotlin.test.*
class DeviceTestPolicyTest {
    @Test fun realPhoneNeverRunsGeneralStateResetTests() {
        for (credential in listOf(true, false)) {
            assertFalse(DeviceTestPolicy.permitted(false, credential, null, emptySet()))
            assertFalse(DeviceTestPolicy.permitted(false, credential, "app.allowmate.UiTest", setOf("allowAccountRead")))
            assertFalse(DeviceTestPolicy.permitted(false, credential, "app.allowmate.VisualTest", emptySet()))
        }
    }
    @Test fun configuredEmulatorAlsoPreservesCredentials() {
        assertTrue(DeviceTestPolicy.permitted(true, false, null, emptySet()))
        assertFalse(DeviceTestPolicy.permitted(true, true, "app.allowmate.UiTest", emptySet()))
    }
    @Test fun realReadRequiresExactMethodAndMatchingOptIn() {
        val selected = "app.allowmate.AuthorizedAccountTest#readPlatformOnly"
        assertTrue(DeviceTestPolicy.permitted(false, true, selected, setOf("allowAccountRead")))
        assertFalse(DeviceTestPolicy.permitted(false, true, selected, emptySet()))
        assertFalse(DeviceTestPolicy.permitted(false, true, selected, setOf("allowCredentialImport")))
        assertFalse(DeviceTestPolicy.permitted(false, true, "app.allowmate.AuthorizedAccountTest", setOf("allowAccountRead")))
    }
}
