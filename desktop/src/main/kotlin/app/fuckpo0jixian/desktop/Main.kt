package app.fuckpo0jixian.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * One copy per user. A second launch (double-clicking the app while it sits in the tray) asks the running copy
 * to show its window over a loopback socket, then exits.
 */
private object SingleInstance {
    private val lockFile = dataDir.resolve("instance.lock")
    private val portFile = dataDir.resolve("instance.port")
    var onShow: () -> Unit = {}
    // Held for the life of the process; an unreferenced channel would be closed by GC and drop the lock.
    private var lock: java.nio.channels.FileLock? = null
    fun acquire(): Boolean {
        val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = channel.tryLock()
        if (lock == null) {
            channel.close()
            runCatching { Socket(InetAddress.getLoopbackAddress(), Files.readString(portFile).trim().toInt()).use { it.getOutputStream().write(1) } }
            return false
        }
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        Files.writeString(portFile, server.localPort.toString())
        thread(isDaemon = true, name = "single-instance") {
            while (true) runCatching { server.accept().use { if (it.getInputStream().read() == 1) onShow() } }
        }
        return true
    }
}

fun main(args: Array<String>) {
    // Menu bar icons on macOS are template images: the system tints them to match the menu bar.
    if (os == Os.MAC) System.setProperty("apple.awt.enableTemplateImages", "true")
    if (!SingleInstance.acquire()) exitProcess(0)
    val controller = DesktopController()
    controller.start()
    application {
        var visible by remember { mutableStateOf("--background" !in args) }
        SingleInstance.onShow = { visible = true }
        val icon = remember { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }
        // Monochrome glyph on macOS (black/white picked by appearance in case templates are unsupported);
        // the colour icon on Windows, where tray icons are normally coloured.
        val dark = isSystemInDarkTheme()
        val trayIcon = remember(dark) {
            if (os != Os.MAC) icon else BitmapPainter(useResource(if (dark) "tray-white.png" else "tray-black.png", ::loadImageBitmap))
        }
        val tray = rememberTrayState()
        val state by controller.store.flow.collectAsState()
        LaunchedEffect(Unit) {
            runCatching { Desktop.getDesktop().addAppEventListener(AppReopenedListener { visible = true }) }
            controller.alerts.collect { tray.sendNotification(Notification("白名单需要处理", it, Notification.Type.Warning)) }
        }
        Tray(trayIcon, state = tray, tooltip = "去他妈的鸡险", onAction = { visible = true }, menu = {
            Item("打开", onClick = { visible = true })
            Item("立即检查", enabled = !state.paused, onClick = controller::check)
            Item(if (state.paused) "恢复检查" else "暂停检查", onClick = { controller.pause(!state.paused) })
            Separator()
            Item("退出", onClick = { controller.stop(); exitApplication() })
        })
        // Closing the window keeps the app running in the tray / menu bar.
        Window(onCloseRequest = { visible = false }, visible = visible, title = "去他妈的鸡险", icon = icon,
            state = rememberWindowState(size = DpSize(460.dp, 760.dp), position = WindowPosition(androidx.compose.ui.Alignment.Center))) {
            CompositionLocalProvider(LocalPalette provides if (isSystemInDarkTheme()) darkPalette else lightPalette) { App(controller) }
        }
    }
}
