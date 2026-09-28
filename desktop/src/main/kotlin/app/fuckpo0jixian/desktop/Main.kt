package app.fuckpo0jixian.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    controller.quit = { controller.stop(); exitProcess(0) }
    controller.start()
    application {
        var visible by remember { mutableStateOf("--background" !in args) }
        SingleInstance.onShow = { visible = true }
        val icon = remember { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }
        val state by controller.store.flow.collectAsState()
        val busy by controller.busy.collectAsState()
        val update by controller.updater.state.collectAsState()
        val quit = { controller.stop(); exitApplication() }
        val open = { page: Page? -> visible = true; if (page != null) controller.requestedPage.value = page }
        LaunchedEffect(Unit) { runCatching { Desktop.getDesktop().addAppEventListener(AppReopenedListener { visible = true }) } }
        val menu = TrayMenu(state, busy, (update as? app.fuckpo0jixian.core.UpdateState.Available)?.update?.version)
        if (os == Os.MAC) MacStatusItem(controller, menu, open, quit = quit)
        else {
            // Windows: the colour icon, as tray icons normally are there.
            val tray = rememberTrayState()
            LaunchedEffect(Unit) { controller.alerts.collect { tray.sendNotification(Notification("白名单需要处理", it, Notification.Type.Warning)) } }
            Tray(icon, state = tray, tooltip = "去他妈的鸡险 · ${menu.status}", onAction = { open(null) }, menu = {
                Item(menu.status, enabled = false, onClick = {})
                Item(menu.exit, enabled = false, onClick = {})
                Separator()
                Item("立即检查", enabled = menu.canCheck, onClick = controller::check)
                Item(menu.pauseLabel, onClick = { controller.pause(!state.paused) })
                Separator()
                Item("打开主窗口", onClick = { open(null) })
                menu.update?.let { Item("有新版本 $it", onClick = { open(Page.SETTINGS) }) }
                Separator()
                Item("退出", onClick = quit)
            })
        }
        // Closing the window keeps the app running in the tray / menu bar.
        Window(onCloseRequest = { visible = false }, visible = visible, title = "去他妈的鸡险", icon = icon,
            state = rememberWindowState(size = DpSize(880.dp, 600.dp), position = WindowPosition(androidx.compose.ui.Alignment.Center))) {
            val systemDark = isSystemInDarkTheme()
            var windowsDark by remember { mutableStateOf(SystemAppearance.windowsDark()) }
            var accent by remember { mutableStateOf<androidx.compose.ui.graphics.Color?>(null) }
            val dark = windowsDark ?: systemDark
            // Follow changes to dark mode and the accent colour while the app runs.
            LaunchedEffect(dark) {
                while (true) {
                    windowsDark = withContext(Dispatchers.IO) { SystemAppearance.windowsDark() }
                    accent = withContext(Dispatchers.IO) { SystemAppearance.accent(dark) }
                    delay(5_000)
                }
            }
            val theme = remember(dark, accent) { desktopTheme(SystemAppearance.look, dark, accent) }
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(720, 480)
                if (os == Os.MAC) window.rootPane.apply {
                    // Content runs under a transparent title bar, like System Settings.
                    putClientProperty("apple.awt.fullWindowContent", true)
                    putClientProperty("apple.awt.transparentTitleBar", true)
                    putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
            LaunchedEffect(theme) { if (os == Os.WINDOWS) WindowsTitleBar.match(window, dark, theme.colors.window) }
            CompositionLocalProvider(LocalTheme provides theme) {
                App(controller, titleBar = if (os == Os.MAC) { m -> WindowDraggableArea(m) { Box(Modifier.fillMaxSize()) } } else null)
            }
        }
    }
}

/** What the tray / menu bar menu shows: live status and exit, then the actions. */
class TrayMenu(s: app.fuckpo0jixian.core.State, busy: Boolean, val update: String?) {
    val status = when {
        busy -> "正在检查…"
        s.globalBlock != null || s.authBlocked -> "需要处理 · ${desktopText(s.globalBlock ?: "AUTH_PAUSED")}"
        s.paused -> "已暂停"
        else -> "${if (s.mode == app.fuckpo0jixian.core.Mode.AUTO && s.layout != null) "自动同步中" else "仅查询"} · ${desktopText(s.status)}"
    }
    val exit = "出口 ${s.snapshot?.current?.value ?: "未检查"}"
    val canCheck = !busy && !s.paused
    val pauseLabel = if (s.paused) "恢复检查" else "暂停检查"
}

/** Windows 11: dark title bar in dark mode and a caption colour matching the window, so both read as one surface. */
private object WindowsTitleBar {
    private interface Dwm : com.sun.jna.Library {
        fun DwmSetWindowAttribute(hwnd: com.sun.jna.platform.win32.WinDef.HWND, attribute: Int, value: com.sun.jna.ptr.IntByReference, size: Int): Int
    }
    private val dwm by lazy { runCatching { com.sun.jna.Native.load("dwmapi", Dwm::class.java) }.getOrNull() }
    fun match(window: java.awt.Window, dark: Boolean, color: androidx.compose.ui.graphics.Color) {
        val handle = (window as? androidx.compose.ui.awt.ComposeWindow)?.windowHandle ?: return
        val hwnd = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer(handle))
        runCatching {
            dwm?.DwmSetWindowAttribute(hwnd, 20, com.sun.jna.ptr.IntByReference(if (dark) 1 else 0), 4) // DWMWA_USE_IMMERSIVE_DARK_MODE
            val bgr = ((color.blue * 255).toInt() shl 16) or ((color.green * 255).toInt() shl 8) or (color.red * 255).toInt()
            dwm?.DwmSetWindowAttribute(hwnd, 35, com.sun.jna.ptr.IntByReference(bgr), 4) // DWMWA_CAPTION_COLOR (Windows 11)
        }
    }
}

/**
 * macOS menu bar item built directly on AWT: Compose's Tray draws the icon at 1x, which looks blurry on Retina.
 * A 1x + 2x template image stays sharp and is tinted by the system like every other menu bar icon.
 */
@Composable private fun MacStatusItem(controller: DesktopController, menu: TrayMenu, open: (Page?) -> Unit, quit: () -> Unit) {
    val status = remember { java.awt.MenuItem().apply { isEnabled = false } }
    val exit = remember { java.awt.MenuItem().apply { isEnabled = false } }
    val pause = remember { java.awt.MenuItem() }
    val check = remember { java.awt.MenuItem("立即检查") }
    val update = remember { java.awt.MenuItem() }
    val popup = remember { java.awt.PopupMenu() }
    LaunchedEffect(menu.status, menu.exit, menu.pauseLabel, menu.canCheck, menu.update) {
        status.label = menu.status; exit.label = menu.exit
        pause.label = menu.pauseLabel; check.isEnabled = menu.canCheck
        update.label = "有新版本 ${menu.update}…"
        val index = (0 until popup.itemCount).indexOfFirst { popup.getItem(it) === update }
        if (menu.update != null && index < 0) popup.insert(update, popup.itemCount - 2) else if (menu.update == null && index >= 0) popup.remove(update)
    }
    DisposableEffect(Unit) {
        fun image(name: String) = javax.imageio.ImageIO.read(DesktopController::class.java.getResourceAsStream("/$name"))
        popup.apply {
            add(status); add(exit); addSeparator()
            add(check.apply { addActionListener { controller.check() } })
            add(pause.apply { addActionListener { controller.pause(!controller.store.load().paused) } })
            addSeparator()
            add(java.awt.MenuItem("打开主窗口").apply { addActionListener { open(null) } })
            update.addActionListener { open(Page.SETTINGS) }
            addSeparator()
            add(java.awt.MenuItem("退出").apply { addActionListener { quit() } })
        }
        val item = java.awt.TrayIcon(java.awt.image.BaseMultiResolutionImage(image("tray-22.png"), image("tray-44.png")), "去他妈的鸡险", popup)
        item.isImageAutoSize = false
        runCatching { java.awt.SystemTray.getSystemTray().add(item) }
        val alerts = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            controller.alerts.collect { item.displayMessage("白名单需要处理", it, java.awt.TrayIcon.MessageType.WARNING) }
        }
        onDispose { alerts.cancel(); java.awt.SystemTray.getSystemTray().remove(item) }
    }
}
