package app.fuckpo0jixian.core

/** Prevent an accidental connectedAndroidTest run from resetting a configured phone. */
object DeviceTestPolicy {
    private val optIns = mapOf(
        "inspectUpgradeAndProxyGuard" to "allowUpgradeRead",
        "inspectWifiIdentityRead" to "allowWifiIdentityRead",
        "importPrivateCredential" to "allowCredentialImport",
        "readPlatformOnly" to "allowAccountRead",
        "readDomesticOnly" to "allowRealNetworkProbe",
        "scheduleAfterPersistentQuota" to "allowSchedulingRead",
        "syncDedicatedSlots" to "allowDedicatedSlotWrite",
        "resumeDedicatedSlots" to "allowDedicatedSlotWrite"
    )
    fun permitted(emulator: Boolean, credentialPresent: Boolean, selection: String?, enabledFlags: Set<String>): Boolean {
        if (emulator && !credentialPresent) return true
        val parts = selection?.split('#') ?: return false
        if (parts.size != 2 || parts[0] != "app.fuckpo0jixian.AuthorizedAccountTest") return false
        val flag = optIns[parts[1]] ?: return false
        return flag in enabledFlags
    }
}
