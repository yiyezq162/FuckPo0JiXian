package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/** Small per-device preferences that are not part of the sync state. */
class DesktopPrefs(dir: Path) {
    private val file = dir.resolve("settings.properties")
    private val props = Properties().apply { runCatching { Files.newInputStream(file).use { load(it) } } }
    @Synchronized private fun set(key: String, value: String) {
        props.setProperty(key, value)
        runCatching { writeAtomically(file, java.io.ByteArrayOutputStream().also { props.store(it, null) }.toByteArray()) }
    }
    var notify: Boolean
        get() = props.getProperty("notify") != "false"
        set(v) = set("notify", v.toString())
    var lastUpdateCheck: Long
        get() = props.getProperty("lastUpdateCheck")?.toLongOrNull() ?: 0
        set(v) = set("lastUpdateCheck", v.toString())
}

/**
 * Finds the installer for this computer in the newest release, downloads and verifies it, then hands over to a
 * small script that waits for this app to quit, installs, and starts the new version:
 * - macOS: mounts the DMG and replaces the .app bundle in place (needs write access to it, as for a drag install);
 * - Windows: runs the per-user MSI, which upgrades the existing install without an administrator prompt.
 * Outside an installed app (development runs) the downloaded file is just opened.
 */
class Updater(private val version: String, private val dir: Path, private val prefs: DesktopPrefs,
              private val client: UpdateClient = UpdateClient("FuckPo0JiXian-Desktop/$version")) {
    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private var job: Job? = null

    val arch: String = System.getProperty("os.arch").lowercase().let { if (it == "aarch64" || it == "arm64") "arm64" else "x64" }
    fun assetName(v: String) = when (os) {
        Os.WINDOWS -> Updates.desktopAsset(v, "windows", arch, "msi")
        else -> Updates.desktopAsset(v, "macos", arch, "dmg")
    }

    fun check(scope: CoroutineScope, manual: Boolean) {
        if (job?.isActive == true) return
        if (!manual && System.currentTimeMillis() - prefs.lastUpdateCheck < Updates.AUTO_INTERVAL_MS) return
        job = scope.launch(Dispatchers.IO) {
            val before = state.value
            if (manual) state.value = UpdateState.Checking
            try {
                val update = client.check(version, ::assetName)
                prefs.lastUpdateCheck = System.currentTimeMillis()
                state.value = if (update != null) UpdateState.Available(update) else UpdateState.Latest(System.currentTimeMillis())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A silent daily check that fails leaves no message behind.
                state.value = if (manual) UpdateState.Failed(updateText((e as? ApiFailure)?.code ?: "UPDATE_OFFLINE")) else before
            }
        }
    }

    /** Downloads, verifies, then calls [quit] once the installer script is waiting for this process to exit. */
    fun install(scope: CoroutineScope, update: Update, quit: () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                state.value = UpdateState.Downloading(update, null)
                val file = client.download(update, dir.resolve("updates").toFile(), { isActive }) { done, total ->
                    state.value = UpdateState.Downloading(update, if (total > 0) done.toFloat() / total else null)
                }
                state.value = UpdateState.Installing(update)
                if (handOver(file)) quit()
                else { openFile(file); state.value = UpdateState.Failed(updateText("UPDATE_NOT_WRITABLE"), update) }
            } catch (e: CancellationException) { state.value = UpdateState.Available(update); throw e }
            catch (e: Exception) { state.value = UpdateState.Failed(updateText((e as? ApiFailure)?.code ?: "UPDATE_OFFLINE"), update) }
        }
    }

    fun cancel() { job?.cancel() }

    private fun openFile(file: File) = runCatching { java.awt.Desktop.getDesktop().open(file) }

    /** The installed launcher (set by jpackage); null for development runs. */
    private val launcher: Path? get() = System.getProperty("jpackage.app-path")?.let(Paths::get)

    private fun handOver(file: File): Boolean {
        val app = launcher ?: return false
        val pid = ProcessHandle.current().pid().toString()
        val log = dir.resolve("updates").resolve("install.log").toFile()
        return when (os) {
            Os.MAC -> {
                // .../FuckPo0JiXian.app/Contents/MacOS/FuckPo0JiXian → the bundle
                val bundle = app.parent?.parent?.parent ?: return false
                if (!bundle.toString().endsWith(".app") || !Files.isWritable(bundle) || !Files.isWritable(bundle.parent)) return false
                val script = dir.resolve("updates").resolve("install.sh")
                Files.writeString(script, MAC_SCRIPT)
                ProcessBuilder("/bin/sh", script.toString(), pid, file.absolutePath, bundle.toString())
                    .redirectErrorStream(true).redirectOutput(log).start()
                true
            }
            Os.WINDOWS -> {
                val script = dir.resolve("updates").resolve("install.ps1")
                Files.writeString(script, WINDOWS_SCRIPT)
                ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString(),
                    "-Owner", pid, "-Msi", file.absolutePath, "-App", app.toString())
                    .redirectErrorStream(true).redirectOutput(log).start()
                true
            }
            Os.OTHER -> false
        }
    }

    private companion object {
        /** Waits for the app to quit, swaps the bundle (keeping the old one until the copy succeeds), relaunches. */
        const val MAC_SCRIPT = """#!/bin/sh
PID="${'$'}1"; DMG="${'$'}2"; APP="${'$'}3"
i=0; while kill -0 "${'$'}PID" 2>/dev/null && [ ${'$'}i -lt 120 ]; do sleep 0.5; i=${'$'}((i+1)); done
MNT=${'$'}(mktemp -d /tmp/fpjx-update.XXXXXX)
if hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "${'$'}MNT" "${'$'}DMG"; then
  SRC=${'$'}(ls -d "${'$'}MNT"/*.app | head -n 1)
  if [ -n "${'$'}SRC" ]; then
    rm -rf "${'$'}APP.old"
    if mv "${'$'}APP" "${'$'}APP.old" && ditto "${'$'}SRC" "${'$'}APP"; then rm -rf "${'$'}APP.old"
    else rm -rf "${'$'}APP"; mv "${'$'}APP.old" "${'$'}APP"; fi
  fi
  hdiutil detach "${'$'}MNT" -quiet || hdiutil detach "${'$'}MNT" -force -quiet
fi
rmdir "${'$'}MNT" 2>/dev/null
xattr -dr com.apple.quarantine "${'$'}APP" 2>/dev/null
open "${'$'}APP"
"""
        /** Per-user MSI: upgrades in place (same UpgradeCode) with a progress bar and no UAC prompt. */
        const val WINDOWS_SCRIPT = """param([int]${'$'}Owner, [string]${'$'}Msi, [string]${'$'}App)
try { Wait-Process -Id ${'$'}Owner -Timeout 60 -ErrorAction SilentlyContinue } catch {}
${'$'}p = Start-Process msiexec.exe -ArgumentList "/i `"${'$'}Msi`" /passive /norestart" -Wait -PassThru
"msiexec exit ${'$'}(${'$'}p.ExitCode)"
Start-Process -FilePath ${'$'}App
"""
    }
}
