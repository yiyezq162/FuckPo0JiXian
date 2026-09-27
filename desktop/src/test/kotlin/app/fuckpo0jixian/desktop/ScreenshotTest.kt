package app.fuckpo0jixian.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.fuckpo0jixian.core.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test

/** Renders the desktop screens with synthetic data to build/screenshots. Opt-in: FUCKPO0JIXIAN_SCREENSHOTS=1. */
class ScreenshotTest {
    @Test fun renderPages() {
        if (System.getenv("FUCKPO0JIXIAN_SCREENSHOTS") != "1") return
        val dir = Files.createTempDirectory("fpjx")
        val c = DesktopController(FileStore(dir), FileVault(dir.resolve("t")))
        c.vault.save("pgnfw_SCREENSHOT_ONLY"); c.credentialPresent.value = true
        val now = System.currentTimeMillis()
        c.store.save(State(mode = Mode.AUTO, paused = false, deviceName = "Mac", status = "SLOT_CURRENT", lastCheck = now,
            snapshot = Snapshot(Cidr("203.0.113.0/24"), listOf(Entry(Cidr("203.0.113.0/24"), 0), Entry(Cidr("198.51.100.0/24"), 1),
                Entry(Cidr("192.0.2.0/24"), 2)), 5),
            events = listOf(Event(now - 3_600_000, "SLOT_UPDATED"), Event(now, "SLOT_CURRENT")),
            layout = SlotLayout(slots = listOf(
                ManagedSlot(0, "家", SlotPurpose.FIXED, Writer.LOCAL, "home", true, authorized = true, baseline = Cidr("203.0.113.0/24"), shared = true, status = "SLOT_CURRENT"),
                ManagedSlot(1, "公司", SlotPurpose.FIXED, Writer.OTHER_DEVICE, owner = "Windows", status = "PEER_UPDATED", changedAt = now),
                ManagedSlot(2, "手机", SlotPurpose.MOBILE, Writer.OTHER_DEVICE, owner = "小米 14"),
                ManagedSlot(3, "笔记本外出", SlotPurpose.MOBILE, Writer.LOCAL, automatic = true, allowUnknownWifi = true, authorized = true, status = "AUTHORIZED_LOCAL"),
                ManagedSlot(4, "保留")),
                identities = listOf(NetworkIdentity("home", "家", "gw:192.168.5.1", setOf(AuthorizedAp("a4:11:22:33:44:55", WifiSecurity.GATEWAY)))))))
        c.link.value = DesktopLink("en6", "192.168.5.20", "192.168.5.1", "a4:11:22:33:44:55")
        val out = Paths.get("build", "screenshots").also { Files.createDirectories(it) }
        listOf(Triple("overview", 0, null), Triple("slots", 1, null), Triple("settings", 2, null), Triple("editor-home", 1, 0), Triple("editor-other", 1, 1))
            .forEach { (name, page, slot) -> listOf(false, true).forEach { dark ->
                ImageComposeScene(460 * 2, 900 * 2, Density(2f)) {
                    CompositionLocalProvider(LocalPalette provides if (dark) darkPalette else lightPalette) { App(c, page, slot) }
                }.use { scene ->
                    scene.render(); scene.render(1_000_000_000)
                    Files.write(out.resolve("$name${if (dark) "-dark" else ""}.png"), scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            } }
    }
}
