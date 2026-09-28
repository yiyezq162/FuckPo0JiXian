package app.fuckpo0jixian.desktop

import kotlin.test.*

/** Opt-in (FUCKPO0JIXIAN_GUI=1): briefly shows this test process in the Dock, then hides it again. */
class MacDockTest {
    private fun type(): String {
        val p = ProcessBuilder("lsappinfo", "info", "-only", "ApplicationType", ProcessHandle.current().pid().toString()).start()
        return p.inputStream.bufferedReader().readText()
    }
    private fun waitFor(expected: String): String {
        var seen = ""
        repeat(40) { seen = type(); if (expected in seen) return seen; Thread.sleep(100) }
        return seen
    }
    @Test fun dockIconFollowsTheSetting() {
        org.junit.Assume.assumeTrue("macOS GUI session", os == Os.MAC && System.getenv("FUCKPO0JIXIAN_GUI") == "1")
        java.awt.Toolkit.getDefaultToolkit() // starts AppKit and its main-thread run loop
        MacDock.show(true)
        assertTrue("Foreground" in waitFor("Foreground"))
        MacDock.show(false)
        assertTrue("UIElement" in waitFor("UIElement"), "hidden: menu bar only")
        MacDock.show(true)
        assertTrue("Foreground" in waitFor("Foreground"))
        MacDock.show(false)
    }
}
