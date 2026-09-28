package app.fuckpo0jixian.desktop

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Executes the shipped script with fake system commands, entirely inside a temporary directory. */
class MacUpdateSafetyTest {
    private fun exercise(fault: String, alive: Boolean = false): Pair<java.nio.file.Path, Int> {
        org.junit.Assume.assumeTrue("macOS shell fault harness", os == Os.MAC)
        val root = Files.createTempDirectory("mac-update-test")
        val bin = Files.createDirectory(root.resolve("bin"))
        val app = Files.createDirectory(root.resolve("Example.app"))
        Files.writeString(app.resolve("old"), "old")
        Files.createDirectory(root.resolve("Example.app.old"))
        Files.writeString(root.resolve("Example.app.old/older"), "earlier recovery material")
        fun command(name: String, body: String) {
            Files.writeString(bin.resolve(name), "#!/bin/sh\n$body\n").toFile().setExecutable(true)
        }
        command("sleep", "exit 0")
        command("mktemp", "exec /usr/bin/mktemp -d '${root}/mount.XXXXXX'")
        command("hdiutil", """if [ "${'$'}1" = attach ]; then
          mkdir -p "${'$'}6/New.app"; echo new > "${'$'}6/New.app/new"
        fi
        exit 0""")
        command("mv", """case "${'$'}FAULT:${'$'}1" in
          backup:*Example.app) exit 1;;
          restore:*.old|restore:*/backup.app) exit 1;;
        esac
        exec /bin/mv "${'$'}@"""")
        command("ditto", """mkdir -p "${'$'}2"; echo partial > "${'$'}2/partial"
        [ "${'$'}FAULT" = copy ] || [ "${'$'}FAULT" = restore ] && exit 1
        exec /bin/cp -R "${'$'}1/." "${'$'}2/"""")
        command("xattr", "exit 0")
        command("open", "echo open >> '${root}/opened'")
        val field = Updater::class.java.getDeclaredField("MAC_SCRIPT").apply { isAccessible = true }
        val script = root.resolve("install.sh")
        Files.writeString(script, field.get(null) as String)
        val process = ProcessBuilder("/bin/sh", script.toString(), if (alive) "0" else "99999999",
            root.resolve("fake.dmg").toString(), app.toString())
            .redirectErrorStream(true).redirectOutput(root.resolve("log").toFile())
        process.environment()["PATH"] = "$bin:/usr/bin:/bin"
        process.environment()["FAULT"] = fault
        val p = process.start()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        return root to p.exitValue()
    }
    @Test fun backupFailurePreservesOriginal() {
        val (root, code) = exercise("backup")
        assertTrue(Files.exists(root.resolve("Example.app/old")), Files.readString(root.resolve("log")))
        assertTrue(Files.exists(root.resolve("Example.app.old/older")))
        assertNotEquals(0, code)
    }
    @Test fun ownerStillAlivePreventsReplacement() {
        val (root, code) = exercise("", alive = true)
        assertTrue(Files.exists(root.resolve("Example.app/old")))
        assertFalse(Files.exists(root.resolve("opened")))
        assertNotEquals(0, code)
    }
    @Test fun copyFailureRestoresOriginal() {
        val (root, code) = exercise("copy")
        assertTrue(Files.exists(root.resolve("Example.app/old")))
        assertNotEquals(0, code)
    }
    @Test fun restoreFailureRetainsBackupAndDoesNotLaunchPartialApp() {
        val (root, code) = exercise("restore")
        assertTrue(Files.walk(root).use { paths -> paths.anyMatch { it.fileName.toString() == "old" } })
        assertFalse(Files.exists(root.resolve("opened")))
        assertNotEquals(0, code)
    }
    @Test fun successfulReplacementLaunchesNewApp() {
        val (root, code) = exercise("")
        assertEquals(0, code, Files.readString(root.resolve("log")))
        assertTrue(Files.exists(root.resolve("Example.app/new")))
        assertTrue(Files.exists(root.resolve("Example.app.old/older")))
        assertTrue(Files.exists(root.resolve("opened")))
    }
}
