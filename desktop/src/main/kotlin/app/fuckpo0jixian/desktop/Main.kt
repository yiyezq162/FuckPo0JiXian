package app.fuckpo0jixian.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.launch
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
    if (os == Os.MAC) {
        // Menu bar icons on macOS are template images: the system tints them to match the menu bar.
        System.setProperty("apple.awt.enableTemplateImages", "true")
        // Title bar follows the system light / dark appearance like the content does.
        System.setProperty("apple.awt.application.appearance", "system")
    }
    if (!SingleInstance.acquire()) exitProcess(0)
    val controller = DesktopController()
    controller.start()
    application {
        var visible by remember { mutableStateOf("--background" !in args) }
        SingleInstance.onShow = { visible = true }
        val icon = remember { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }
        val state by controller.store.flow.collectAsState()
        val quit = { controller.stop(); exitApplication() }
        LaunchedEffect(Unit) { runCatching { Desktop.getDesktop().addAppEventListener(AppReopenedListener { visible = true }) } }
        if (os == Os.MAC) MacStatusItem(controller, state.paused, show = { visible = true }, quit = quit)
        else {
            // Windows: the colour icon, as tray icons normally are there.
            val tray = rememberTrayState()
            LaunchedEffect(Unit) { controller.alerts.collect { tray.sendNotification(Notification("白名单需要处理", it, Notification.Type.Warning)) } }
            Tray(icon, state = tray, tooltip = "去他妈的鸡险", onAction = { visible = true }, menu = {
                Item("打开", onClick = { visible = true })
                Item("立即检查", enabled = !state.paused, onClick = controller::check)
                Item(if (state.paused) "恢复检查" else "暂停检查", onClick = { controller.pause(!state.paused) })
                Separator()
                Item("退出", onClick = quit)
            })
        }
        // Closing the window keeps the app running in the tray / menu bar.
        Window(onCloseRequest = { visible = false }, visible = visible, title = "去他妈的鸡险", icon = icon,
            state = rememberWindowState(size = DpSize(460.dp, 760.dp), position = WindowPosition(androidx.compose.ui.Alignment.Center))) {
            CompositionLocalProvider(LocalPalette provides if (isSystemInDarkTheme()) darkPalette else lightPalette) { App(controller) }
        }
    }
}

/**
 * macOS menu bar item built directly on AWT: Compose's Tray draws the icon at 1x, which looks blurry on Retina.
 * A 1x + 2x template image stays sharp and is tinted by the system like every other menu bar icon.
 */
@Composable private fun MacStatusItem(controller: DesktopController, paused: Boolean, show: () -> Unit, quit: () -> Unit) {
    val pause = remember { java.awt.MenuItem() }
    val check = remember { java.awt.MenuItem("立即检查") }
    LaunchedEffect(paused) { pause.label = if (paused) "恢复检查" else "暂停检查"; check.isEnabled = !paused }
    DisposableEffect(Unit) {
        fun image(name: String) = javax.imageio.ImageIO.read(DesktopController::class.java.getResourceAsStream("/$name"))
        val menu = java.awt.PopupMenu().apply {
            add(java.awt.MenuItem("打开").apply { addActionListener { show() } })
            add(check.apply { addActionListener { controller.check() } })
            add(pause.apply { addActionListener { controller.pause(!controller.store.load().paused) } })
            addSeparator()
            add(java.awt.MenuItem("退出").apply { addActionListener { quit() } })
        }
        val item = java.awt.TrayIcon(java.awt.image.BaseMultiResolutionImage(image("tray-22.png"), image("tray-44.png")), "去他妈的鸡险", menu)
        item.isImageAutoSize = false
        runCatching { java.awt.SystemTray.getSystemTray().add(item) }
        val alerts = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            controller.alerts.collect { item.displayMessage("白名单需要处理", it, java.awt.TrayIcon.MessageType.WARNING) }
        }
        onDispose { alerts.cancel(); java.awt.SystemTray.getSystemTray().remove(item) }
    }
}
