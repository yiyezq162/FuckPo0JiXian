package app.fuckpo0jixian.desktop

import app.fuckpo0jixian.core.*
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Per-user data folder; nothing here is synced or backed up by the app. */
val dataDir: Path = (System.getenv("FUCKPO0JIXIAN_DATA_DIR")?.let { Paths.get(it) } ?: when (os) {
    Os.MAC -> Paths.get(System.getProperty("user.home"), "Library", "Application Support", "FuckPo0JiXian")
    Os.WINDOWS -> Paths.get(System.getenv("APPDATA") ?: System.getProperty("user.home"), "FuckPo0JiXian")
    Os.OTHER -> Paths.get(System.getProperty("user.home"), ".config", "fuckpo0jixian")
}).also { dir ->
    Files.createDirectories(dir)
    if (os != Os.WINDOWS) runCatching { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------")) }
}

internal fun writeAtomically(file: Path, bytes: ByteArray) {
    val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
    FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
        it.write(java.nio.ByteBuffer.wrap(bytes)); it.force(true)
    }
    if (os != Os.WINDOWS) runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/** Same contract as the Android store: an unreadable file is protected, never overwritten with an empty state. */
class FileStore(val dir: Path = dataDir, private val clock: () -> Long = System::currentTimeMillis) : StateStore {
    private val file = dir.resolve("state.json")
    private var recoveryRequired = false
    val flow = MutableStateFlow(read())
    private fun read(): State = try {
        if (!Files.exists(file)) State() else {
            val decoded = StateCodec.decode(Files.readString(file))
            val now = clock()
            val cutoff = now - Policy().retentionMs
            decoded.loaded(now).copy(observations = decoded.observations.filter { it.time >= cutoff }.takeLast(500),
                events = decoded.events.filter { it.time >= cutoff }.takeLast(200))
        }
    } catch (_: Exception) { recoveryRequired = true; State(status = "STORAGE_RECOVERY_REQUIRED", globalBlock = "STORAGE_RECOVERY_REQUIRED") }
    override fun load() = flow.value
    @Synchronized override fun save(state: State) {
        check(!recoveryRequired) { "STORAGE_RECOVERY_REQUIRED" }
        writeAtomically(file, StateCodec.encode(state).toByteArray())
        flow.value = state
    }
}

/** Token storage: macOS Keychain, Windows DPAPI (bound to the Windows account). Never written in plain text. */
interface TokenVault {
    fun exists(): Boolean
    fun read(): String?
    fun save(token: String)
    fun clear()
    companion object {
        fun create(dir: Path = dataDir): TokenVault = when (os) {
            Os.MAC -> KeychainVault()
            Os.WINDOWS -> DpapiVault(dir.resolve("credential.bin"))
            Os.OTHER -> FileVault(dir.resolve("credential.txt"))
        }
    }
}

private val tokenFormat = Regex("pgnfw_[A-Za-z0-9_-]+")

class KeychainVault(private val service: String = "app.fuckpo0jixian", private val account: String = "po0-token") : TokenVault {
    @Volatile private var cached: String? = null
    private fun security(vararg args: String, stdin: String? = null): Pair<Int, String> {
        val p = ProcessBuilder("/usr/bin/security", *args).redirectErrorStream(false).start()
        stdin?.let { p.outputStream.use { o -> o.write(it.toByteArray()) } } ?: p.outputStream.close()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "" }
        return p.exitValue() to p.inputStream.bufferedReader().use { it.readText() }
    }
    override fun exists() = cached != null || security("find-generic-password", "-s", service, "-a", account).first == 0
    @Synchronized override fun read(): String? = cached ?: security("find-generic-password", "-s", service, "-a", account, "-w")
        .takeIf { it.first == 0 }?.second?.trim()?.takeIf { tokenFormat.matches(it) }?.also { cached = it }
    @Synchronized override fun save(token: String) {
        require(tokenFormat.matches(token))
        // Interactive mode reads the command from stdin, so the token never appears in a process list.
        val (code, _) = security("-i", stdin = "add-generic-password -U -s $service -a $account -w $token\n")
        check(code == 0 && read(forceReload = true) == token) { "KEYCHAIN_WRITE_FAILED" }
    }
    private fun read(forceReload: Boolean): String? { if (forceReload) cached = null; return read() }
    @Synchronized override fun clear() { cached = null; security("delete-generic-password", "-s", service, "-a", account) }
}

class DpapiVault(private val file: Path) : TokenVault {
    override fun exists() = Files.exists(file)
    @Synchronized override fun read(): String? = if (!exists()) null else
        String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(file)), Charsets.UTF_8).takeIf { tokenFormat.matches(it) }
    @Synchronized override fun save(token: String) {
        require(tokenFormat.matches(token))
        writeAtomically(file, Crypt32Util.cryptProtectData(token.toByteArray()))
    }
    @Synchronized override fun clear() { Files.deleteIfExists(file) }
}

/** Development fallback for other systems: owner-only file. */
class FileVault(private val file: Path) : TokenVault {
    override fun exists() = Files.exists(file)
    override fun read(): String? = if (exists()) Files.readString(file).trim().takeIf { tokenFormat.matches(it) } else null
    override fun save(token: String) { require(tokenFormat.matches(token)); writeAtomically(file, token.toByteArray()) }
    override fun clear() { Files.deleteIfExists(file) }
}

/** Start at login, hidden in the tray. Only for the installed app, whose launcher path jpackage provides. */
object Autostart {
    private val launcher: String? get() = System.getProperty("jpackage.app-path")
    val available get() = launcher != null && os != Os.OTHER
    private val plist = Paths.get(System.getProperty("user.home"), "Library", "LaunchAgents", "app.fuckpo0jixian.desktop.plist")
    private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    fun enabled(): Boolean = when (os) {
        Os.MAC -> Files.exists(plist)
        Os.WINDOWS -> runCatching { ProcessBuilder("reg", "query", RUN_KEY, "/v", "FuckPo0JiXian").start().waitFor() == 0 }.getOrDefault(false)
        Os.OTHER -> false
    }
    fun set(on: Boolean) {
        val path = launcher ?: return
        when (os) {
            Os.MAC -> if (on) {
                Files.createDirectories(plist.parent)
                val escaped = path.replace("&", "&amp;").replace("<", "&lt;")
                Files.writeString(plist, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0"><dict>
                      <key>Label</key><string>app.fuckpo0jixian.desktop</string>
                      <key>ProgramArguments</key><array><string>$escaped</string><string>--background</string></array>
                      <key>RunAtLoad</key><true/>
                      <key>ProcessType</key><string>Interactive</string>
                    </dict></plist>
                """.trimIndent())
            } else Files.deleteIfExists(plist)
            Os.WINDOWS -> (if (on) listOf("reg", "add", RUN_KEY, "/v", "FuckPo0JiXian", "/t", "REG_SZ", "/d", "\"$path\" --background", "/f")
                else listOf("reg", "delete", RUN_KEY, "/v", "FuckPo0JiXian", "/f")).let { ProcessBuilder(it).start().waitFor(10, TimeUnit.SECONDS) }
            Os.OTHER -> Unit
        }
    }
}
