package app.fuckpo0jixian

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Asks the system whether "常驻运行" really runs as a foreground service, and only while wanted. No Po0 requests. */
@RunWith(AndroidJUnit4::class)
class KeepAliveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun service() = shell("dumpsys activity services ${context.packageName}/.KeepAliveService")
    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(200) }
        return condition()
    }

    @Test fun runsInForegroundOnlyWhileEnabledAndScheduled() {
        val before = KeepAlive.enabled(context)
        try {
            KeepAlive.set(context, true)
            KeepAlive.sync(context, scheduled = true)
            assertTrue(service(), waitFor { service().contains("isForeground=true") })
            if (android.os.Build.VERSION.SDK_INT >= 34) assertTrue(service(), service().contains("types=0x40000000"))
            KeepAlive.sync(context, scheduled = false) // paused, no token …
            assertTrue(service(), waitFor { !service().contains("KeepAliveService") })
            KeepAlive.set(context, false)
            KeepAlive.sync(context, scheduled = true)
            Thread.sleep(1_000)
            assertFalse(service(), service().contains("KeepAliveService"))
        } finally {
            KeepAlive.set(context, before); KeepAlive.sync(context, scheduled = false)
        }
    }
}
