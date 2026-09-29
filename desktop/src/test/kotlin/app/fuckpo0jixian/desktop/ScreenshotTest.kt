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

/**
 * Renders the desktop window (880×600) with synthetic data to build/screenshots, in both looks (macOS, Windows) and
 * both themes. Opt-in: FUCKPO0JIXIAN_SCREENSHOTS=1.
 */
class ScreenshotTest {
    @Test fun renderProtectionRecovery() {
        if (System.getenv("FUCKPO0JIXIAN_SCREENSHOTS") != "1") return
        val dir = Files.createTempDirectory("fpjx-recovery-render")
        val c = DesktopController(FileStore(dir), FileVault(dir.resolve("t")))
        c.credentialPresent.value = true // synthetic UI only; no credential or monitor
        c.store.save(State(globalBlock = "SLOT_VERIFY_FAILED", status = "SLOT_VERIFY_FAILED"))
        val out = Paths.get("build", "screenshots").also { Files.createDirectories(it) }
        try {
            for (dark in listOf(false, true)) {
                ImageComposeScene(880 * 2, 600 * 2, Density(2f)) {
                    CompositionLocalProvider(LocalTheme provides desktopTheme(Look.MAC, dark)) { App(c, Page.OVERVIEW, null) }
                }.use { scene ->
                    scene.render(); scene.render(1_000_000_000)
                    Files.write(out.resolve("recovery-${if (dark) "dark" else "light"}.png"),
                        scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        } finally { c.stop() }
    }
    @Test fun renderPages() {
        if (System.getenv("FUCKPO0JIXIAN_SCREENSHOTS") != "1") return
        val dir = Files.createTempDirectory("fpjx")
        val c = DesktopController(FileStore(dir), FileVault(dir.resolve("t")))
        c.vault.save("pgnfw_SCREENSHOT_ONLY"); c.credentialPresent.value = true
        val now = System.currentTimeMillis()
        val home = Cidr("203.0.113.0/24")
        c.store.save(State(mode = Mode.AUTO, paused = false, deviceName = "Mac", status = "SLOT_CURRENT", lastCheck = now - 120_000,
            lastSuccess = now - 120_000, fallbackMinutes = 5,
            domesticExit = DomesticExit("203.0.113.87", now - 120_000, "k", ProbeSource.STUN), probeStatus = "PROBE_OBSERVED",
            snapshot = Snapshot(home, listOf(Entry(home, 0), Entry(Cidr("198.51.100.0/24"), 1), Entry(Cidr("192.0.2.0/24"), 2)), 5),
            observations = (0 until 12).map { Observation(now - it * 7_200_000L, if (it % 3 == 0) Cidr("192.0.2.0/24") else home, "lan") },
            events = listOf("SLOT_UPDATED", "PEER_UPDATED", "SLOT_CURRENT", "LOCAL_UNCHANGED", "SLOT_CURRENT", "SLOT_CURRENT", "SLOT_CURRENT")
                .mapIndexed { i, code -> Event(now - (7 - i) * 1_800_000L, code) },
            layout = SlotLayout(slots = listOf(
                ManagedSlot(0, "家", SlotPurpose.FIXED, Writer.LOCAL, listOf("home", "home-wired"), true, authorized = true, baseline = home, shared = true, status = "SLOT_CURRENT"),
                ManagedSlot(1, "公司", SlotPurpose.MOBILE, Writer.OTHER_DEVICE, owner = "Windows", status = "PEER_UPDATED", changedAt = now - 3_600_000),
                ManagedSlot(2, "手机", SlotPurpose.FIXED, Writer.OTHER_DEVICE, owner = "小米 14"),
                ManagedSlot(3, "笔记本外出", SlotPurpose.MOBILE, Writer.LOCAL, automatic = true, allowUnknownWifi = true, authorized = true, status = "AUTHORIZED_LOCAL"),
                ManagedSlot(4, "保留")),
                identities = listOf(NetworkIdentity("home", "家", "gw:192.168.5.1", setOf(AuthorizedAp("a4:11:22:33:44:55", WifiSecurity.GATEWAY))),
                    NetworkIdentity("home-wired", "家", "gw:192.168.1.1", setOf(AuthorizedAp("a4:11:22:33:44:66", WifiSecurity.GATEWAY)))))))
        c.link.value = DesktopLink("en6", "192.168.5.20", "192.168.5.1", "a4:11:22:33:44:55")
        c.updater.state.value = UpdateState.Available(Update(Release("0.8.1-preview", "- 修复名额条顺序\n- 新增检查更新", Updates.PAGE, emptyList()),
            ReleaseAsset("FuckPo0JiXian-Desktop-0.8.1-preview-macos-arm64.dmg", Updates.PAGE, 1)))
        val out = Paths.get("build", "screenshots").also { Files.createDirectories(it) }
        val shots = listOf(Triple("overview", Page.OVERVIEW, null), Triple("slots", Page.SLOTS, 0), Triple("slots-other", Page.SLOTS, 1),
            Triple("records", Page.RECORDS, null), Triple("settings", Page.SETTINGS, null))
        for (look in Look.entries) for (dark in listOf(false, true)) for ((name, page, slot) in shots) {
            ImageComposeScene(880 * 2, 600 * 2, Density(2f)) {
                CompositionLocalProvider(LocalTheme provides desktopTheme(look, dark)) { App(c, page, slot) }
            }.use { scene ->
                scene.render(); scene.render(1_000_000_000)
                val file = "${look.name.lowercase()}-$name${if (dark) "-dark" else ""}.png"
                Files.write(out.resolve(file), scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        // A slot bound to two routers (Wi-Fi and wired at home), with the whole detail pane in view.
        ImageComposeScene(880 * 2, 1180 * 2, Density(2f)) {
            CompositionLocalProvider(LocalTheme provides desktopTheme(Look.MAC, false)) { App(c, Page.SLOTS, 0) }
        }.use { scene ->
            scene.render(); scene.render(1_000_000_000)
            Files.write(out.resolve("mac-slots-networks.png"), scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        // The dialog offered when the window comes back into view.
        c.updater.offer.value = (c.updater.state.value as UpdateState.Available).update
        for (look in Look.entries) for (dark in listOf(false, true)) {
            ImageComposeScene(880 * 2, 600 * 2, Density(2f)) {
                CompositionLocalProvider(LocalTheme provides desktopTheme(look, dark)) { App(c, Page.OVERVIEW, null) }
            }.use { scene ->
                scene.render(); scene.render(1_000_000_000)
                Files.write(out.resolve("${look.name.lowercase()}-update${if (dark) "-dark" else ""}.png"),
                    scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }
}
