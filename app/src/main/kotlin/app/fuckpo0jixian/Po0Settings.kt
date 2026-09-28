package app.fuckpo0jixian

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fuckpo0jixian.core.*
import app.fuckpo0jixian.core.State

internal fun LazyListScope.po0Items(s: State, c: Controller, credential: Boolean, busy: Boolean, notifications: () -> Unit) {
    if (s.demo) item(key = "demo-exit") { DemoExitSection(c, busy) }
    item(key = "po0-connect") { ConnectionSection(s, c, credential, busy) }
    item(key = "po0-sync") {
        val colors = Apple.colors
        Section(header = "同步", inset = 58.dp, footer = when {
            s.layout == null && !s.demo -> "请先在白名单中设置并授权槽位。"
            s.paused -> "检查已暂停，可在概览中恢复。"
            s.mode == Mode.AUTO -> "按已授权的槽位自动更新白名单。"
            else -> "仅查询，不修改白名单。"
        }) {
            ListRow("自动同步", leading = { IconTile(Glyphs.Sync, colors.green) }, trailing = {
                IosSwitch(s.mode == Mode.AUTO, { c.mode(if (it) Mode.AUTO else Mode.OBSERVE) }, "自动同步",
                    enabled = !busy && (s.demo || s.layout != null || s.mode == Mode.AUTO))
            })
            ListRow("异常提醒", leading = { IconTile(Icons.Rounded.Notifications, colors.red) }, chevron = true, onClick = notifications)
        }
    }
    item(key = "po0-devices") { DevicesSection(s, c, busy) }
    item(key = "po0-frequency") {
        Section(header = "检查频率", footer = "网络不变时只在本机比对出口，变了才查 Po0。省电时系统可能延后。") {
            ListRow("切换网络后", value = "约 3 秒")
            ListRow("兜底检查间隔", trailing = {
                Stepper(s.fallbackMinutes, FallbackInterval.MIN..FallbackInterval.MAX, c::fallbackMinutes) { "$it 分钟" }
            })
            ListRow("与 Po0 核对", value = "至少每小时")
        }
    }
    item(key = "po0-background") { BackgroundSection(c) }
    item(key = "po0-runtime") { RuntimeSection(s, c, busy) }
    item(key = "po0-about") { AboutSection(s, c) }
}

/** Version and in-app update: check, download (verified), then Android's own install confirmation. */
@Composable private fun AboutSection(s: State, c: Controller) {
    val state by c.updater.state.collectAsState()
    val colors = Apple.colors
    Section(header = "关于", inset = 58.dp, footer = "Token 与配置只保存在本机，不会备份或上传。" +
        if (s.runtimeMode == RuntimeMode.MODULE) "更新应用后，可在「运行模式」中一键更新模块。" else "") {
        ListRow("版本", value = c.updater.version, leading = { IconTile(Icons.Rounded.Info, colors.gray) })
        when (val u = state) {
            is UpdateState.Available -> {
                ListRow("新版本 ${u.update.version}", subtitle = Updates.summary(u.update.release.notes, 4).ifBlank { null },
                    leading = { IconTile(Glyphs.Download, colors.red) })
                ActionRow("下载并安装") { c.updater.install(u.update) }
            }
            is UpdateState.Downloading -> {
                ListRow("正在下载 ${u.update.version}", value = u.fraction?.let { "${(it * 100).toInt()}%" },
                    leading = { IconTile(Glyphs.Download, colors.accent) })
                LinearProgressIndicator(progress = { u.fraction ?: 0f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    color = colors.accent, trackColor = colors.fill, drawStopIndicator = {})
                ActionRow("取消", color = colors.red, onClick = c.updater::cancel)
            }
            is UpdateState.Installing -> ListRow("等待系统安装确认…", leading = { IconTile(Glyphs.Download, colors.accent) })
            is UpdateState.Failed -> {
                ListRow(u.message, titleColor = colors.orange, leading = { IconTile(Icons.Rounded.Warning, colors.orange) })
                ActionRow(if (u.update != null) "重试" else "检查更新") { u.update?.let(c.updater::install) ?: c.updater.check(manual = true) }
            }
            else -> ActionRow(when (u) { is UpdateState.Latest -> "已是最新 · 再次检查"; else -> "检查更新" },
                loading = u == UpdateState.Checking) { c.updater.check(manual = true) }
        }
    }
}

@Composable private fun ConnectionSection(s: State, c: Controller, credential: Boolean, busy: Boolean) {
    var edit by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    val colors = Apple.colors
    Section(header = "Po0 账户", footer = "Token 加密保存在本机。检查连接只读取白名单，不会修改。") {
        ListRow("Token", value = when { s.demo -> "演示模式"; credential -> "已保存"; else -> "未添加" },
            leading = { IconTile(Glyphs.Key, colors.accent) })
        if (credential && !s.demo) ListRow("上次连接", value = if (s.lastSuccess > 0) time(s.lastSuccess) else "尚未连接")
        if (credential && !s.demo && s.endpoint != Po0Credential.DEFAULT_ENDPOINT) ListRow("服务器", value = s.endpoint.removePrefix("https://"))
        ActionRow(if (busy) "正在检查…" else "检查连接", enabled = !busy && (credential || s.demo), loading = busy, onClick = c::checkConnection)
        ActionRow(if (credential) "更换 Token" else "添加 Token", enabled = !busy) { edit = true }
        if (credential) ActionRow("移除连接", color = colors.red, enabled = !busy) { remove = true }
    }
    if (edit) TokenDialog(onDismiss = { edit = false }) { c.saveToken(it); edit = false }
    if (remove) IosAlert("移除连接？", { remove = false }, message = "将删除本机的 Token 和槽位配置并暂停同步。Po0 上的白名单不受影响。",
        actions = listOf(AlertAction("取消") { remove = false }, AlertAction("移除", destructive = true) { c.clearToken(); remove = false }))
}

@Composable private fun TokenDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var input by remember { mutableStateOf("") } // No saved-state or credential readback.
    val token = remember(input) { Po0Credential.extract(input) }
    val colors = Apple.colors
    IosAlert("连接 Po0", onDismiss, message = "粘贴 Token 或官方接口链接", content = {
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.fill).padding(horizontal = 10.dp, vertical = 9.dp)) {
            if (input.isEmpty()) Text("pgnfw_…", style = Apple.subhead, color = colors.tertiary)
            BasicTextField(input, { input = it.take(2048) }, Modifier.fillMaxWidth().testTag("token-input"), singleLine = true,
                textStyle = Apple.subhead.copy(color = colors.label), cursorBrush = SolidColor(colors.accent),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
        if (input.isNotBlank() && token == null) Text("格式不正确，应以 pgnfw_ 开头或为官方链接", style = Apple.footnote, color = colors.red, textAlign = TextAlign.Center)
        else Text("保存后检查会先暂停。", style = Apple.footnote, color = colors.secondary, textAlign = TextAlign.Center)
    }, actions = listOf(AlertAction("取消", onClick = onDismiss), AlertAction("保存", preferred = true, enabled = token != null) { if (token != null) onSave(input) }))
}

@Composable private fun RuntimeSection(s: State, c: Controller, busy: Boolean) {
    val status by c.runtime.status.collectAsState()
    val checkedAt by c.runtime.checkedAt.collectAsState()
    val module by c.runtime.module.collectAsState()
    var details by remember { mutableStateOf(false) }
    // null: hidden; false: install; true: update an older module.
    var sheet by remember { mutableStateOf<Boolean?>(null) }
    val outdated = module?.outdated(c.runtime.apkVersion) == true
    LaunchedEffect(s.runtimeMode, s.paused) {
        while (true) { c.refreshRuntime(); kotlinx.coroutines.delay(30_000) }
    }
    fun offer(update: Boolean) { c.moduleInstaller.reset(); sheet = update }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Section(header = "运行模式", footer = "一般保持标准模式即可。模块增强需要 root，仅用于在后台更及时地触发检查。") {
            CheckRow("标准模式", s.runtimeMode == RuntimeMode.STANDARD, subtitle = "无需 root", enabled = !busy) { c.runtimeMode(RuntimeMode.STANDARD) }
            CheckRow("模块增强", s.runtimeMode == RuntimeMode.MODULE, subtitle = "Magisk / KernelSU", enabled = !busy) {
                // An installed module works even when older, so switch and suggest the update; without one, offer to install.
                if (module != null) { c.runtimeMode(RuntimeMode.MODULE); if (outdated) offer(update = true) } else offer(update = false)
            }
        }
        Section(footer = if (details) "模块会监听底层网络与路由器出口（仅局域网查询），并让本应用免于电池优化。" +
            "它不保存 Token，也不保证常驻；小米 HyperOS 需允许本应用自启动。要停用，请切回标准模式，或在管理器中禁用模块。" else null) {
            ListRow("状态", subtitle = status, value = if (checkedAt > 0) time(checkedAt) else null)
            if (s.runtimeMode == RuntimeMode.MODULE && outdated) {
                ListRow("模块有新版本", subtitle = "当前 ${module?.versionName ?: "旧版"}，与应用 ${c.updater.version} 配套的版本可用",
                    titleColor = Apple.colors.orange)
                ActionRow("更新模块") { offer(update = true) }
            }
            module?.wan?.let { wan -> ListRow("路由器出口", subtitle = gatewayText(module?.gateway), value = wan) }
            ListRow("诊断与安装说明", chevron = !details, leading = null) { c.refreshRuntime(); details = !details }
            if (details) {
                val lastConnection by c.runtime.lastConnection.collectAsState()
                val result by c.runtime.result.collectAsState()
                ListRow("模块版本", value = module?.versionName ?: "未连接")
                if (module?.wan == null && module != null) ListRow("路由器出口", value = gatewayText(module?.gateway))
                ListRow("最近连接", subtitle = readableEvidence(lastConnection))
                ListRow("最近结果", subtitle = readableEvidence(result))
                ActionRow(if (module == null) "安装模块" else "重新安装模块") { offer(update = module != null) }
            }
        }
    }
    sheet?.let { update ->
        ModuleSheet(c, update, onDismiss = { sheet = null }) {
            if (s.runtimeMode != RuntimeMode.MODULE) c.runtimeMode(RuntimeMode.MODULE)
            c.moduleInstaller.install()
        }
    }
}

/** How the module reads the router's WAN address, in words. */
private fun gatewayText(status: String?) = when (status) {
    "NATPMP" -> "经 NAT-PMP 读取"; "UPNP" -> "经 UPnP 读取"
    "NATPMP_PRIVATE", "UPNP_PRIVATE" -> "路由器处于另一层 NAT 之后"
    "UNSUPPORTED" -> "路由器未开启 UPnP / NAT-PMP"
    "DISCOVERING" -> "正在查找路由器"
    "OFF" -> "连接 Wi-Fi 时读取"
    else -> "模块版本较旧，暂不支持"
}

/** Standard mode depends on the system letting the app wake up; these are the switches that decide it. */
@Composable private fun BackgroundSection(c: Controller) {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val exempt = remember(lifecycle) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val colors = Apple.colors
    var hidden by remember { mutableStateOf(Recents.hidden(context)) }
    var resident by remember { mutableStateOf(KeepAlive.enabled(context)) }
    fun requestExemption() {
        runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
    Section(header = "后台运行", inset = 58.dp, footer = "「常驻运行」通过前台服务提高后台存活概率，通知栏会有一条静默通知；不保证所有 OEM、Doze 或进程回收场景都能常驻、及时检查或自动重启，也不保证零额外耗电。" +
        "OPPO、一加、小米等系统还需在应用设置中允许「自启动」和「后台运行」。已 root 的设备可改用「模块增强」。") {
        ToggleRow("常驻运行", resident, { resident = it; c.keepAlive(it); if (it && !exempt) requestExemption() },
            subtitle = "提高后台存活概率，不保证常驻", leading = { IconTile(Glyphs.Shield, colors.green) })
        ToggleRow("不显示后台任务", hidden, { hidden = it; Recents.set(context, it) },
            subtitle = "在最近任务中隐藏本应用", leading = { IconTile(Icons.Rounded.Lock, colors.gray) })
        ListRow("电池优化", value = if (exempt) "不受限制" else "受限制", leading = { IconTile(Glyphs.Bolt, colors.orange) })
        if (!exempt) ActionRow("允许在后台运行") { requestExemption() }
        val exit = remember(lifecycle) { LifeLog.lastExit() }
        if (Build.VERSION.SDK_INT >= 30) ListRow("上次被结束", value = exit?.let { "${time(it.first)} · ${it.second}" } ?: "暂无记录",
            leading = { IconTile(Glyphs.History, colors.gray) })
        ActionRow("自启动与后台设置") {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }
}

/** Evidence lines embed epoch milliseconds; show them as local time. */
private fun readableEvidence(text: String) = text.replace(Regex("\\b1\\d{12}\\b")) { time(it.value.toLong()) }

/** An escape for old/test demo states; ordinary users cannot enable demo from settings. */
@Composable private fun DemoExitSection(c: Controller, busy: Boolean) {
    var confirm by remember { mutableStateOf(false) }
    Section(header = "演示数据", footer = "当前内容不会连接真实平台。") {
        ActionRow("退出演示", color = Apple.colors.red, enabled = !busy) { confirm = true }
    }
    if (confirm) IosAlert("退出演示？", { confirm = false }, message = "清除演示数据，保留已保存的 Token，检查保持暂停。",
        actions = listOf(AlertAction("取消") { confirm = false }, AlertAction("退出", destructive = true) { c.demo(false); confirm = false }))
}

/** Each device authorizes its own slots; importing another device's export only labels the ones it manages. */
@Composable private fun DevicesSection(s: State, c: Controller, busy: Boolean) {
    val context = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    var peer by remember { mutableStateOf<PeerLayout?>(null) }
    var export by remember { mutableStateOf(false) }
    var screenshots by remember { mutableStateOf(Screenshots.allowed(context)) }
    Section(header = "多设备", footer = "每台设备只授权自己负责的槽位。导入其他设备的分工，只会标注它管理的槽位，不会授权或写入。") {
        ListRow("本机名称", value = s.deviceName.ifBlank { "未设置" }, chevron = true, enabled = !busy) { rename = true }
        ActionRow("从剪贴板导入其他设备的分工", enabled = !busy && s.snapshot != null) {
            val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            runCatching { PeerImport.parse(text) }.onSuccess { peer = it }.onFailure { c.feedback.value = statusText("IMPORT_INVALID") }
        }
        ActionRow("导出本机分工") { export = true }
        ToggleRow("允许截图", screenshots, { value -> (context as? android.app.Activity)?.let { screenshots = value; Screenshots.set(it, value) } },
            subtitle = "截图会包含 IP 和槽位，分享前请检查")
    }
    if (export) IosAlert("导出本机分工", { export = false },
        message = "包含 IP 段、本机名称、槽位绑定的 Wi-Fi 名称和接入点，不含 Token。用于在自己的其他设备上导入，请勿公开发布。",
        actions = listOf(
            AlertAction("分享", preferred = true) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.shareText())
                    .putExtra(Intent.EXTRA_SUBJECT, "去他妈的鸡险 · ${s.deviceName.ifBlank { "本机" }}的分工")
                context.startActivity(Intent.createChooser(send, "导出本机分工")); export = false
            },
            AlertAction("复制") {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FuckPo0JiXian", c.shareText()))
                c.feedback.value = "已复制本机分工"; export = false
            },
            AlertAction("取消") { export = false }))
    if (rename) {
        var input by remember { mutableStateOf(s.deviceName) }
        val colors = Apple.colors
        IosAlert("本机名称", { rename = false }, message = "其他设备导入后，会用这个名字标注本机管理的槽位。", content = {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.fill).padding(horizontal = 10.dp, vertical = 9.dp)) {
                BasicTextField(input, { input = it.take(24) }, Modifier.fillMaxWidth().testTag("device-name"), singleLine = true,
                    textStyle = Apple.subhead.copy(color = colors.label), cursorBrush = SolidColor(colors.accent))
            }
        }, actions = listOf(AlertAction("取消") { rename = false },
            AlertAction("保存", preferred = true, enabled = input.isNotBlank()) { c.deviceName(input); rename = false }))
    }
    peer?.let { p ->
        val preview = remember(p, s) { runCatching { PeerImport.apply(s, p) } }
        val error = (preview.exceptionOrNull() as? IllegalArgumentException)?.message
        val count = preview.getOrNull()?.changed?.size ?: 0
        IosAlert("导入「${p.device}」的分工？", { peer = null },
            message = when {
                error != null -> statusText(error)
                count == 0 -> "本机已经是最新的标注。"
                else -> "将把 $count 个槽位标注为其他设备管理。本机负责的槽位不受影响，也不会写入 Po0。"
            },
            actions = if (error != null || count == 0) listOf(AlertAction("好", preferred = true) { peer = null })
                else listOf(AlertAction("取消") { peer = null }, AlertAction("导入", preferred = true) { c.importPeers(p); peer = null }))
    }
}
