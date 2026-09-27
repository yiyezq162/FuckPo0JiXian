package app.fuckpo0jixian

import android.os.Build
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuntimeEvidenceTest {
    @Test fun bootChangeAndUnknownBootNeverReuseCompletionProof() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && !TokenVault(context).exists())
        val first = RuntimeBridge(context, 501)
        first.record("PRESENT_CURRENT_CHECK · HTTP尝试 2", 1000, true)
        first.validate("test-epoch")
        assertTrue(first.validated("test-epoch"))
        assertTrue(RuntimeBridge(context, 501).result.value.startsWith("本次开机最近记录"))
        val nextBoot = RuntimeBridge(context, 502)
        assertFalse(nextBoot.validated("test-epoch"))
        assertTrue(nextBoot.result.value.startsWith("历史记录（非本次开机"))
        val unknown = RuntimeBridge(context, -1)
        unknown.validate("test-epoch")
        assertFalse(unknown.validated("test-epoch"))
        assertTrue(unknown.result.value.startsWith("历史记录（非本次开机"))
    }

    @Test fun legacyReceiptIsRetainedOnlyAsUnattributedHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && !TokenVault(context).exists())
        val file = AtomicFile(File(context.noBackupFilesDir, "runtime-result-v1.json"))
        val stream = file.startWrite()
        stream.write("synthetic previous success".toByteArray())
        file.finishWrite(stream)
        assertEquals("历史记录（开机归属未知，不作当前依据）：synthetic previous success", RuntimeBridge(context, 503).result.value)
    }
}
