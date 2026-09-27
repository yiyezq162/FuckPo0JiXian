package app.allowmate

import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import app.allowmate.core.DeviceTestPolicy
import java.io.File

class SafeTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish")
        val credential = File(targetContext.noBackupFilesDir, "credential.enc").exists()
        val flags = setOf("allowCredentialImport", "allowAccountRead", "allowRealNetworkProbe", "allowSchedulingRead", "allowDedicatedSlotWrite", "allowUpgradeRead", "allowWifiIdentityRead")
            .filter { arguments.getString(it) == "true" }.toSet()
        check(DeviceTestPolicy.permitted(emulator, credential, arguments.getString("class"), flags)) {
            "Refusing state-reset tests on real/configured devices; select one explicitly authorized safe method"
        }
        super.onCreate(arguments)
    }
}
