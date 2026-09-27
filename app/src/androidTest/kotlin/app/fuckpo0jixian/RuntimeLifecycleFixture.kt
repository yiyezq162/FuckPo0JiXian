package app.fuckpo0jixian

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import app.fuckpo0jixian.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Preparation only, not evidence of lifecycle success. Emulator/no-credential runner guard applies. */
class RuntimeLifecycleFixture {
    @Test fun prepareQuotaOnly() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = (instrumentation.targetContext.applicationContext as FuckPo0JiXianApp).controller
        check(!c.vault.exists())
        val state = State(paused = false, runtimeMode = RuntimeMode.MODULE, nextAllowed = Long.MAX_VALUE)
        instrumentation.runOnMainSync { c.store.save(state) }
        runBlocking { c.runtime.publish(state) }
    }
}
