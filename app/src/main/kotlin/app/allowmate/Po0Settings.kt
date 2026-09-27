package app.allowmate

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import app.allowmate.core.*
import app.allowmate.core.State

internal fun LazyListScope.po0Items(s: State, c: Controller, credential: Boolean, busy: Boolean, notifications: () -> Unit) {
    item(key = "po0-connect", contentType = "card") { ConnectionCard(s, c, credential, busy) }
    item(key = "po0-runtime", contentType = "card") { RuntimeCard(s, c, busy) }
    item(key = "po0-sync", contentType = "card") { Panel("自动同步") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("切网后更新手机网段")
                Text(if (s.mode == Mode.AUTO) "只更新手机专用槽位，保留家宽" else "当前仅查询，不更改白名单", style = MaterialTheme.typography.bodySmall)
            }
            Switch(s.mode == Mode.AUTO, { c.mode(if (it) Mode.AUTO else Mode.OBSERVE) },
                modifier = Modifier.semantics { contentDescription = "自动同步" },
                enabled = !busy && (s.demo || s.slotPlan != null || s.mode == Mode.AUTO))
        }
        if (s.slotPlan == null && !s.demo) Text("先完成下方家宽与手机配置，再开启自动同步。", style = MaterialTheme.typography.bodySmall)
        if (s.paused) Text("检查已暂停。配置完成后，请到首页恢复检查。", style = MaterialTheme.typography.bodySmall)
    } }
    if (!s.demo) item(key = "po0-slots", contentType = "card") { SlotCard(s, c, busy) }
    item(key = "po0-frequency", contentType = "card") { Panel("检查频率") {
        Text("切网后等待约 15 秒；两次检查至少间隔 2 分钟。")
        Text("网络不变时约 30 分钟检查一次，系统休眠时可能延后。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = notifications) { Text("开启异常提醒") }
    } }
    item(key = "po0-advanced", contentType = "card") { AdvancedCard(s, c, busy) }
}

@Composable private fun RuntimeCard(s: State, c: Controller, busy: Boolean) {
    val status by c.runtime.status.collectAsState()
    val result by c.runtime.result.collectAsState()
    val checkedAt by c.runtime.checkedAt.collectAsState()
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(s.runtimeMode, s.paused) {
        while (true) { c.refreshRuntime(); kotlinx.coroutines.delay(30_000) }
    }
    Panel("运行模式") {
        Column(Modifier.selectableGroup()) {
            listOf(RuntimeMode.STANDARD to "标准模式（免 root）", RuntimeMode.MODULE to "模块增强（Magisk／KernelSU）").forEach { (mode, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(s.runtimeMode == mode,
                    enabled = !busy, role = Role.RadioButton, onClick = { c.runtimeMode(mode) }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.runtimeMode == mode, onClick = null, enabled = !busy)
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }
        }
        Text("已保存：${if (s.runtimeMode == RuntimeMode.STANDARD) "标准模式" else "模块增强"}")
        Text("实际状态：$status", style = MaterialTheme.typography.bodySmall)
        if (checkedAt > 0) Text("最近检测 · ${time(checkedAt)}", style = MaterialTheme.typography.bodySmall)
        Text("标准模式使用网络回调与系统任务；省电策略可能延后检查。增强预览先验证只读链路，不代表低耗电或整夜保活已通过。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { c.refreshRuntime(); details = !details }) { Text("模块诊断与安装说明") }
        if (details) {
            val lastConnection by c.runtime.lastConnection.collectAsState()
            Text("最近连接报告（历史）", style = MaterialTheme.typography.titleSmall)
            Text(lastConnection, style = MaterialTheme.typography.bodySmall)
            Text("最近执行结果（历史）", style = MaterialTheme.typography.titleSmall)
            Text(result, style = MaterialTheme.typography.bodySmall)
            Text("历史记录不代表当前在线。无法确认安装或启用状态时，请在管理器查看；模块可执行时，Action 可做一次只读诊断。禁用或卸载后该入口可能不可用。", style = MaterialTheme.typography.bodySmall)
            Text("先安装同版本 APK，再在 Magisk／KernelSU 安装对应 ZIP。首次解锁后生效；禁用模块或切回标准模式可回退。失联时无法确认是否安装、启用，请查看管理器。", style = MaterialTheme.typography.bodySmall)
            Text("小米 HyperOS 实测需要允许本应用自启动，才能在进程回收后唤起；应用不会代你开启。深度休眠时增强会降级并退出短时检查，等待系统调度，不保证即时同步。", style = MaterialTheme.typography.bodySmall)
            Text("协议 1 · 每次 helper 启动先完成只读请求，再按原自动同步开关工作。模块不保存 token。撤销 APK 的 su 授权不等于禁用模块，请在管理器禁用本模块。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ConnectionCard(s: State, c: Controller, credential: Boolean, busy: Boolean) {
    var edit by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    Panel("Po0 连接") {
        Text(if (credential) "Token 已加密保存" else "尚未连接 Po0", style = MaterialTheme.typography.titleMedium)
        Text(if (s.demo) "演示模式不连接平台" else if (!credential) "保存 token 后，检查连接以获取白名单" else if (s.lastSuccess > 0) "上次连接成功 · ${time(s.lastSuccess)}" else "点击检查连接，获取当前白名单", style = MaterialTheme.typography.bodySmall)
        Button(onClick = c::checkConnection, enabled = !busy && (credential || s.demo), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在检查…" else "检查连接") }
        Text("只读取白名单；不会新增或替换网段。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { edit = true }, enabled = !busy) { Text(if (credential) "更换 Token" else "添加 Token") }
        if (credential) TextButton(onClick = { remove = true }, enabled = !busy) { Text("移除本机连接") }
    }
    if (edit) TokenDialog(onDismiss = { edit = false }) { c.saveToken(it); edit = false }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("移除本机连接？") },
        text = { Text("删除本机保存的 token 和槽位配置，并暂停同步。Po0 上的白名单不会删除。") },
        confirmButton = { TextButton(onClick = { c.clearToken(); remove = false }) { Text("移除并暂停") } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("取消") } })
}

@Composable private fun TokenDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var input by remember { mutableStateOf("") } // No saved-state or credential readback.
    val token = remember(input) { Po0Credential.extract(input) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("连接 Po0") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("粘贴 token 或官网提供的完整接口链接。只提取 token，槽位在下方单独配置。")
            OutlinedTextField(input, { input = it.take(2048) }, label = { Text("Token 或接口链接") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = input.isNotBlank() && token == null, modifier = Modifier.fillMaxWidth())
            if (input.isNotBlank() && token == null) Text("格式不正确，请使用 pgnfw_ 开头的 token 或 Po0 官方接口链接。")
            Text("保存后先暂停检查。连接信息只保存在本机，不回显已有 token。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { token?.let(onSave) }, enabled = token != null) { Text("加密保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun SlotCard(s: State, c: Controller, busy: Boolean) {
    var edit by remember { mutableStateOf(false) }
    Panel("家宽与手机") {
        val p = s.slotPlan
        if (p == null) Text("为家宽和手机各指定一个不同的槽位。") else {
            Text("家宽：${p.home.value}")
            Text("家宽槽位 ${p.homeSlot} · ${if (p.homeReady) "已固定保护" else "等待在家宽初始化"}")
            HorizontalDivider()
            Text("手机槽位 ${p.mobileSlot} · 切网时更新")
        }
        Text("槽位是 Po0 的固定名额编号，不是列表顺序。家宽保留，手机只更新自己的名额。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { edit = true }, enabled = !busy && s.snapshot != null) { Text(if (p == null) "配置家宽与手机" else "修改槽位配置") }
        if (s.snapshot == null) Text("先点击“检查连接”获取可用名额。", style = MaterialTheme.typography.bodySmall)
    }
    if (edit) SlotDialog(s, onDismiss = { edit = false }) { c.configurePo0(it); edit = false }
}

@Composable private fun SlotDialog(s: State, onDismiss: () -> Unit, save: (SlotPlan) -> Unit) {
    val snapshot = s.snapshot ?: return
    val free = (0 until snapshot.capacity).filter { i -> snapshot.entries.none { it.slot == i } }
    var home by remember { mutableStateOf(s.slotPlan?.home?.value ?: snapshot.current.value) }
    var homeSlot by remember { mutableStateOf((s.slotPlan?.homeSlot ?: snapshot.entries.find { it.cidr == snapshot.current }?.slot ?: free.firstOrNull())?.toString() ?: "") }
    var mobileSlot by remember { mutableStateOf((s.slotPlan?.mobileSlot ?: free.firstOrNull { it.toString() != homeSlot })?.toString() ?: "") }
    val validation = remember(s, home, homeSlot, mobileSlot) { runCatching {
        SlotConfiguration.prepare(s, Cidr(home.trim()), homeSlot.toInt(), mobileSlot.toInt())
    } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("家宽与手机配置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("家宽网段需已在白名单内；不能占用其他设备的固定槽位。")
            OutlinedTextField(home, { home = it.take(32) }, label = { Text("家宽网段 /24") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(homeSlot, { homeSlot = it.filter(Char::isDigit).take(4) }, label = { Text("家宽槽位编号") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(mobileSlot, { mobileSlot = it.filter(Char::isDigit).take(4) }, label = { Text("手机槽位编号") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Text("编号范围：0–${snapshot.capacity - 1}。保存仅修改本机配置，并暂停检查；开启自动同步、恢复检查后才会执行更新。", style = MaterialTheme.typography.bodySmall)
            if (validation.isFailure) Text(configError(validation.exceptionOrNull()?.message), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { validation.getOrNull()?.let(save) }, enabled = validation.isSuccess) { Text("保存并暂停") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun configError(code: String?) = when (code) {
    "CONFIG_SLOT_RANGE" -> "家宽与手机编号必须不同，且在可用范围内。"
    "CONFIG_HOME_NOT_LISTED" -> "该家宽网段不在最近白名单中，请先核对。"
    "CONFIG_HOME_ALREADY_PINNED" -> "家宽已固定在其他编号，请沿用原编号。"
    "CONFIG_HOME_SLOT_BUSY" -> "家宽编号已被其他网段占用。"
    "CONFIG_MOBILE_SLOT_BUSY" -> "手机编号已被其他网段占用，不能接管。"
    "CONFIG_CAPACITY_FULL" -> "平台已满，不能预留新的手机槽位。"
    else -> "请填写有效的 /24 网段和槽位编号。"
}

@Composable private fun AdvancedCard(s: State, c: Controller, busy: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Panel("高级选项") {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起高级选项" else "查看演示模式与运行说明") }
        if (expanded) {
            Text("后台检查由 Android 调度；不需要 root 或安装模块。", style = MaterialTheme.typography.bodySmall)
            Text("版本 0.4.0 · 凭据加密保存，禁止备份。", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本机演示模式", Modifier.weight(1f))
                Switch(s.demo, { confirm = true }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "本机演示模式" })
            }
            if (s.demo) OutlinedButton(onClick = c::nextDemoNetwork) { Text("模拟出口变化") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("切换演示模式？") },
        text = { Text("会暂停检查并清除本机的网段关联、槽位配置和最近快照。保留加密 token，不修改 Po0 白名单。") },
        confirmButton = { TextButton(onClick = { c.demo(!s.demo); confirm = false }) { Text("确认切换") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
