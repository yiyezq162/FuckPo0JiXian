package app.fuckpo0jixian

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
    item(key = "po0-background") { BackgroundSection() }
    item(key = "po0-runtime") { RuntimeSection(s, c, busy) }
    item(key = "po0-about") {
        val context = LocalContext.current
        val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—" }
        Section(header = "关于", inset = 58.dp, footer = "Token 与配置只保存在本机，不会备份或上传。") {
            ListRow("版本", value = version, leading = { IconTile(Icons.Rounded.Info, Apple.colors.gray) })
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
    }, actions = listOf(AlertAction("取消", onClick = onDismiss), AlertAction("保存", preferred = true, enabled = token != null) { token?.let(onSave) }))
}

@Composable private fun RuntimeSection(s: State, c: Controller, busy: Boolean) {
    val status by c.runtime.status.collectAsState()
    val checkedAt by c.runtime.checkedAt.collectAsState()
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(s.runtimeMode, s.paused) {
        while (true) { c.refreshRuntime(); kotlinx.coroutines.delay(30_000) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Section(header = "运行模式", footer = "一般保持标准模式即可。模块增强需要 root，仅用于在后台更及时地触发检查。") {
            CheckRow("标准模式", s.runtimeMode == RuntimeMode.STANDARD, subtitle = "无需 root", enabled = !busy) { c.runtimeMode(RuntimeMode.STANDARD) }
            CheckRow("模块增强", s.runtimeMode == RuntimeMode.MODULE, subtitle = "Magisk / KernelSU", enabled = !busy) { c.runtimeMode(RuntimeMode.MODULE) }
        }
        Section(footer = if (details) "先安装同版本 APK，再在 Magisk / KernelSU 中安装对应模块，首次解锁后生效。" +
            "模块不保存 Token，也不保证常驻；小米 HyperOS 需允许本应用自启动。要停用，请在管理器中禁用模块或切回标准模式。" else null) {
            ListRow("状态", subtitle = status, value = if (checkedAt > 0) time(checkedAt) else null)
            ListRow("诊断与安装说明", chevron = !details, leading = null) { c.refreshRuntime(); details = !details }
            if (details) {
                val lastConnection by c.runtime.lastConnection.collectAsState()
                val result by c.runtime.result.collectAsState()
                ListRow("最近连接", subtitle = readableEvidence(lastConnection))
                ListRow("最近结果", subtitle = readableEvidence(result))
            }
        }
    }
}

/** Standard mode depends on the system letting the app wake up; these are the switches that decide it. */
@Composable private fun BackgroundSection() {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val exempt = remember(lifecycle) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val colors = Apple.colors
    var hidden by remember { mutableStateOf(Recents.hidden(context)) }
    Section(header = "后台运行", inset = 58.dp, footer = "OPPO、一加、小米等系统还需在应用设置中允许「自启动」和「后台运行」，否则可能被清理。" +
        "开启「不显示后台任务」后，一键清理最近任务不会清掉本应用。已 root 的设备可改用「模块增强」。") {
        ToggleRow("不显示后台任务", hidden, { hidden = it; Recents.set(context, it) },
            subtitle = "在最近任务中隐藏本应用", leading = { IconTile(Icons.Rounded.Lock, colors.gray) })
        ListRow("电池优化", value = if (exempt) "不受限制" else "受限制", leading = { IconTile(Glyphs.Bolt, colors.orange) })
        if (!exempt) ActionRow("允许在后台运行") {
            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
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
    Section(header = "多设备", footer = "每台设备只授权自己负责的槽位。导入其他设备的导出数据，只会标注它管理的槽位，不会授权或写入。") {
        ListRow("本机名称", value = s.deviceName.ifBlank { "未设置" }, chevron = true, enabled = !busy) { rename = true }
        ActionRow("从剪贴板导入其他设备的分工", enabled = !busy && s.snapshot != null) {
            val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            runCatching { PeerImport.parse(text) }.onSuccess { peer = it }.onFailure { c.feedback.value = statusText("IMPORT_INVALID") }
        }
        ActionRow("导出脱敏数据") { export = true }
    }
    if (export) IosAlert("导出脱敏数据", { export = false },
        message = "Token 已去除，账户标识、Wi-Fi 名称和接入点不导出；IP 只保留前两段（如 203.0.*.0/24）。可用于复盘，也可在其他设备上导入分工。",
        actions = listOf(
            AlertAction("分享", preferred = true) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.exportText())
                    .putExtra(Intent.EXTRA_SUBJECT, "去他妈的鸡险 · 脱敏数据")
                context.startActivity(Intent.createChooser(send, "导出脱敏数据")); export = false
            },
            AlertAction("复制") {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FuckPo0JiXian", c.exportText()))
                c.feedback.value = "已复制脱敏数据"; export = false
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
