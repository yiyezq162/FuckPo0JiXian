package app.fuckpo0jixian.desktop

import java.nio.file.Files
import kotlin.test.*

class VaultTest {
    /** DPAPI only exists on Windows; the CI Windows runner exercises it. */
    @Test fun dpapiRoundTripKeepsNoPlainText() {
        if (os != Os.WINDOWS) return
        val file = Files.createTempDirectory("fpjx").resolve("credential.bin")
        val vault = DpapiVault(file)
        vault.save("pgnfw_DPAPI_TEST_ONLY")
        assertFalse(String(Files.readAllBytes(file), Charsets.ISO_8859_1).contains("pgnfw_"))
        assertEquals("pgnfw_DPAPI_TEST_ONLY", DpapiVault(file).read())
        vault.clear(); assertFalse(vault.exists())
    }

    @Test fun fileStoreRefusesToOverwriteAnUnreadableState() {
        val dir = Files.createTempDirectory("fpjx")
        Files.writeString(dir.resolve("state.json"), "{broken")
        val store = FileStore(dir)
        assertEquals("STORAGE_RECOVERY_REQUIRED", store.load().globalBlock)
        assertFails { store.save(store.load().copy(paused = false)) }
        assertEquals("{broken", Files.readString(dir.resolve("state.json")))
    }
}
