package app.fuckpo0jixian

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Asks the system itself whether the no-root wake paths are registered and released. No Po0 requests. */
@RunWith(AndroidJUnit4::class)
class WakeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    // Pending alarms print "tag=" on its own line; removal-history snapshots print "type=... tag=..." instead.
    private fun fallbackAlarms() = shell("dumpsys alarm").lines().count { it.trim().startsWith("tag=*walarm*:${Wake.ACTION_FALLBACK}") }
    // Live requests only: the "Network Requests:" section, not the registration history logs further down.
    private fun networkListens() = shell("dumpsys connectivity").lines()
        .dropWhile { it != "Network Requests:" }.drop(1).takeWhile { it.isBlank() || it.startsWith(" ") }
        .count { it.contains("LISTEN") && it.contains("RequestorPkg: ${context.packageName}") && it.contains("INTERNET") }

    @Test fun fallbackAlarmAndNetworkWakeRegisterOnceAndClear() {
        try {
            Wake.enable(context, 600_000)
            Wake.enable(context, 600_000) // re-arming must replace, not stack
            assertEquals(1, fallbackAlarms())
            assertEquals(1, networkListens())
            Thread.sleep(15_000) // must persist, not just exist right after registering
            assertEquals(1, networkListens())
            val alarm = shell("dumpsys alarm").lines().dropWhile { !it.trim().startsWith("tag=*walarm*:${Wake.ACTION_FALLBACK}") }.take(2).joinToString()
            assertTrue(alarm, alarm.contains("ELAPSED_WAKEUP"))
        } finally { Wake.disable(context) }
        assertEquals(0, fallbackAlarms())
        assertEquals(0, networkListens())
    }

    @Test fun fallbackTickWithoutCredentialDisarmsInsteadOfChecking() {
        val controller = (context.applicationContext as FuckPo0JiXianApp).controller
        org.junit.Assume.assumeFalse("needs an emulator without a saved token", controller.vault.exists())
        Wake.enable(context, 600_000)
        instrumentation.runOnMainSync { controller.fallbackTick() }
        assertEquals(0, fallbackAlarms())
        assertEquals(0, networkListens())
    }
}
