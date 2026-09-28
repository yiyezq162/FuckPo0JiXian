package app.fuckpo0jixian.desktop

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Runs the shipped PowerShell script on Windows; msiexec must never start while the app is still running. */
class WindowsUpdateSafetyTest {
    @Test fun ownerStillRunningAbortsWithoutInstallingOrRelaunching() {
        org.junit.Assume.assumeTrue("Windows PowerShell harness", os == Os.WINDOWS)
        val root = Files.createTempDirectory("windows-update-test")
        val script = root.resolve("install.ps1")
        val field = Updater::class.java.getDeclaredField("WINDOWS_SCRIPT").apply { isAccessible = true }
        Files.writeString(script, field.get(null) as String)
        // The test JVM plays the app that has not quit; the MSI does not exist, so reaching msiexec would show in the log.
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
            "-Owner", ProcessHandle.current().pid().toString(), "-Msi", root.resolve("missing.msi").toString(),
            "-App", root.resolve("missing.exe").toString(), "-WaitMs", "500").redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        val log = process.inputStream.bufferedReader().readText()
        println("windows update script: $log")
        assertEquals(1, process.exitValue())
        assertTrue("installation aborted" in log)
        assertFalse("msiexec exit" in log)
    }
}
