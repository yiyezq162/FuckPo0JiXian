package app.fuckpo0jixian.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fuckpo0jixian.core.*
import app.fuckpo0jixian.core.State
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Desktop wording where the phone's does not fit (routers instead of Wi-Fi, TUN instead of Android VPN). */
fun desktopText(code: String) = when (code) {
    "WIFI_UNAVAILABLE", "GATEWAY_UNAVAILABLE" -> "无法识别路由器，未更新"
    "UNKNOWN_WIFI" -> "当前网络未绑定，未更新"
    "WIFI_SECURITY_CHANGED" -> "路由器信息已变化，请重新绑定"
    "IDENTITY_REQUIRED" -> "未绑定网络，可手动更新"
    "IDENTITY_AMBIGUOUS" -> "当前网络匹配多个槽位，请检查绑定"
    "CONFIG_MOBILE_LIMIT" -> "每台设备只能有一个外出跟随槽"
    "OFFLINE" -> "当前没有可用网络"
    else -> statusText(code)
}

private val dateFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
fun time(value: Long) = if (value <= 0) "暂无" else dateFormat.format(Instant.ofEpochMilli(value))

private fun purposeText(p: SlotPurpose) = when (p) { SlotPurpose.FIXED -> "固定网络"; SlotPurpose.MOBILE -> "外出跟随"; SlotPurpose.RESERVED -> "保留用途" }
private fun writerText(w: Writer) = when (w) { Writer.LOCAL -> "本机"; Writer.OTHER_DEVICE -> "其他设备"; Writer.EXTERNAL -> "外部 / 未知" }
private fun managerText(slot: ManagedSlot) = when (slot.writer) {
    Writer.LOCAL -> if (slot.shared) "本机 · 共管" else "本机"
    Writer.OTHER_DEVICE -> slot.owner.ifBlank { "其他设备" }
    Writer.EXTERNAL -> "外部 / 未知"
}
@Composable private fun purposeColor(p: SlotPurpose) = when (p) { SlotPurpose.FIXED -> colors.accent; SlotPurpose.MOBILE -> colors.green; SlotPurpose.RESERVED -> colors.gray }
private val healthy = setOf("SLOT_CURRENT", "SLOT_UPDATED", "RECOVERED_VERIFIED", "AUTHORIZED_LOCAL", "PEER_UPDATED")
private val attention = setOf("SLOT_CONFLICT", "SLOT_VERIFY_FAILED", "IDENTITY_AMBIGUOUS", "WIFI_SECURITY_CHANGED", "EXTERNAL_CHANGE",
    "COVERED_OTHER_SLOT", "PENDING_REVIEW", "PENDING_CONFIG_CHANGED", "LEGACY_UNINITIALIZED", "SHARED_RECENT")

/** Which bound network (if any) the computer is on right now. */
private fun matchedName(s: State, link: DesktopLink?): String? {
    val o = link?.observation(System.currentTimeMillis()) ?: return null
    return s.layout?.identities?.singleOrNull { it.ssid == o.ssid && AuthorizedAp(o.bssid!!, o.security) in it.aps }?.name
}

@Composable fun App(c: DesktopController, initialPage: Int = 0, initialSlot: Int? = null) {
    val s by c.store.flow.collectAsState()
    val busy by c.busy.collectAsState()
    val feedback by c.feedback.collectAsState()
    var page by remember { mutableStateOf(initialPage) }
    var editing by remember { mutableStateOf(initialSlot) }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        val slot = editing
        if (slot != null) SlotEditor(c, s, busy, s.layout?.slots?.find { it.number == slot } ?: ManagedSlot(slot)) { editing = null }
        else {
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                Segmented(listOf("概览", "白名单", "设置"), page) { page = it }
            }
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    when (page) {
                        0 -> Overview(c, s, busy) { page = 2 }
                        1 -> Slots(c, s, busy) { editing = it }
                        else -> Settings(c, s, busy)
                    }
                }
                if (feedback.isNotBlank() && feedback != desktopText(s.status)) Toast(feedback, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
    ManualDialog(c, busy)
}

@Composable private fun Toast(text: String, modifier: Modifier) {
    var shown by remember { mutableStateOf(text) }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(text) { shown = text; visible = true; kotlinx.coroutines.delay(2_500); visible = false }
    if (visible) Text(shown, style = Type.footnote, color = colors.label, modifier = modifier.padding(bottom = 14.dp)
        .clip(CircleShape).background(colors.card).padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable private fun Overview(c: DesktopController, s: State, busy: Boolean, configure: () -> Unit) {
    val link by c.link.collectAsState()
    val auto = s.mode == Mode.AUTO && s.layout != null
    val (title, tint) = when {
        busy -> "正在检查" to colors.accent
        s.paused -> "已暂停" to colors.orange
        auto -> "自动同步中" to colors.green
        else -> "仅查询" to colors.accent
    }
    Section {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(tint.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) { Dot(tint) }
            Column {
                Text(title, style = Type.title, color = colors.label)
                Text(if (busy) "请稍候" else desktopText(s.status), style = Type.footnote, color = colors.secondary)
            }
        }
        Hairline()
        ListRow("网络", value = listOfNotNull(link?.label, matchedName(s, link)).joinToString(" · ").ifBlank { "未连接" })
        Hairline()
        ListRow("当前出口", value = s.snapshot?.current?.value ?: "未检查")
        Hairline()
        ListRow("上次检查", value = time(s.lastCheck))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton("立即检查", c::check, enabled = !busy && !s.paused && c.credentialPresent.value)
        PrimaryButton(if (s.paused) "恢复检查" else "暂停检查", { c.pause(!s.paused) }, tinted = true)
    }
    if (!c.credentialPresent.value) Section(footer = "添加 Po0 Token 后才能查询白名单。") { Action("前往设置", onClick = configure) }
    s.snapshot?.let { Capacity(s, it) }
    val events = s.events.takeLast(5).reversed()
    if (events.isNotEmpty()) Section(header = "最近事件") {
        events.forEachIndexed { i, e -> if (i > 0) Hairline(); ListRow(desktopText(e.code), value = time(e.time)) }
    }
}

@Composable private fun Capacity(s: State, snap: Snapshot) {
    val used = snap.entries.map { e -> e.slot?.let { n -> s.layout?.slots?.find { it.number == n }?.purpose } ?: SlotPurpose.RESERVED }.sortedBy { it.ordinal }
    Section {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("已用 ${snap.entries.size} / ${snap.capacity}", style = Type.title, color = colors.label)
                Spacer(Modifier.weight(1f))
                Text("剩余 ${snap.remaining}", style = Type.footnote, color = colors.secondary)
            }
            Row(Modifier.fillMaxWidth().height(7.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(maxOf(snap.capacity, used.size)) { i ->
                    Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(used.getOrNull(i)?.let { purposeColor(it) } ?: colors.fill))
                }
            }
        }
    }
}

@Composable private fun Slots(c: DesktopController, s: State, busy: Boolean, edit: (Int) -> Unit) {
    val snap = s.snapshot
    if (snap == null) { Section(footer = "请先在「设置」中添加 Token 并检查连接。") { ListRow("尚未获取白名单", titleColor = colors.secondary) }; return }
    Capacity(s, snap)
    Section(header = "槽位", footer = "修改只保存在本机，开启自动同步后才会写入 Po0。") {
        (0 until snap.capacity).forEach { number ->
            if (number > 0) Hairline()
            val slot = s.layout?.slots?.find { it.number == number } ?: ManagedSlot(number)
            val tint = when (slot.status) { in healthy -> colors.green; in attention -> colors.orange; else -> colors.gray }
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { edit(number) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Badge("${number + 1}", purposeColor(slot.purpose))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(slot.name.ifBlank { "槽 ${number + 1}" }, style = Type.body, color = colors.label, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(snap.entries.find { it.slot == number }?.cidr?.value ?: "空", style = Type.footnote, color = colors.secondary)
                    }
                    Text("${purposeText(slot.purpose)} · ${managerText(slot)}", style = Type.footnote, color = colors.secondary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dot(tint); Text(desktopText(slot.status), style = Type.footnote, color = if (tint == colors.orange) colors.orange else colors.secondary)
                    }
                }
                Text("›", style = Type.headline, color = colors.tertiary)
            }
        }
    }
    val unassigned = snap.entries.filter { it.slot == null }
    if (unassigned.isNotEmpty()) Section(header = "未分配槽号", footer = "这些记录同样占用名额，本机不会改动它们。") {
        unassigned.forEachIndexed { i, e -> if (i > 0) Hairline(); ListRow(e.cidr.value, value = "受保护") }
    }
}

@Composable private fun Settings(c: DesktopController, s: State, busy: Boolean) {
    val credential by c.credentialPresent.collectAsState()
    var tokenDialog by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var peer by remember { mutableStateOf<PeerLayout?>(null) }
    var autostart by remember { mutableStateOf(runCatching { Autostart.enabled() }.getOrDefault(false)) }
    Section(header = "Po0 账户", footer = "Token 保存在${if (os == Os.WINDOWS) " Windows 凭据加密（DPAPI）" else "系统钥匙串"}中。检查连接只读取白名单。") {
        ListRow("Token", value = if (credential) "已保存" else "未添加")
        Hairline(); Action(if (busy) "正在检查…" else "检查连接", enabled = !busy && credential, onClick = c::checkConnection)
        Hairline(); Action(if (credential) "更换 Token" else "添加 Token", enabled = !busy) { tokenDialog = true }
        if (credential) { Hairline(); Action("移除连接", color = colors.red, enabled = !busy) { remove = true } }
    }
    Section(header = "同步", footer = when {
        s.layout == null -> "请先在白名单中设置并授权槽位。"
        s.mode == Mode.AUTO -> "按已授权的槽位自动更新白名单。"
        else -> "仅查询，不修改白名单。"
    }) {
        Toggle("自动同步", s.mode == Mode.AUTO, { c.mode(if (it) Mode.AUTO else Mode.OBSERVE) }, enabled = !busy && (s.layout != null || s.mode == Mode.AUTO))
        Hairline()
        Toggle("开机启动", autostart, { Autostart.set(it); autostart = Autostart.enabled() }, enabled = Autostart.available,
            subtitle = if (Autostart.available) "登录后在后台运行" else "安装版可用")
    }
    Section(header = "多设备", footer = "每台设备只授权自己负责的槽位。导入其他设备的导出数据，只会标注它管理的槽位，不会授权或写入。") {
        ListRow("本机名称", value = s.deviceName.ifBlank { "未设置" }, onClick = { rename = true })
        Hairline()
        Action("从剪贴板导入其他设备的分工", enabled = !busy && s.snapshot != null) {
            val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrDefault("")
            runCatching { PeerImport.parse(text) }.onSuccess { peer = it }.onFailure { c.feedback.value = statusText("IMPORT_INVALID") }
        }
        Hairline(); Action("导出脱敏数据") { export = true }
    }
    Section(header = "关于", footer = "电脑按路由器识别网络，不需要定位权限。请求直接走本机网卡，不受系统代理和 VPN / TUN 影响。") {
        ListRow("版本", value = c.version)
    }
    if (tokenDialog) {
        var input by remember { mutableStateOf("") }
        val token = Po0Credential.extract(input)
        Alert("连接 Po0", { tokenDialog = false }, message = "粘贴 Token 或官方接口链接", content = {
            Field(input, { input = it.take(2048) }, "pgnfw_…", secret = true)
            Text(if (input.isNotBlank() && token == null) "格式不正确，应以 pgnfw_ 开头或为官方链接" else "保存后检查会先暂停。",
                style = Type.footnote, color = if (input.isNotBlank() && token == null) colors.red else colors.secondary)
        }, actions = listOf(DialogAction("保存", preferred = true, enabled = token != null) { token?.let(c::saveToken); tokenDialog = false },
            DialogAction("取消") { tokenDialog = false }))
    }
    if (remove) Alert("移除连接？", { remove = false }, message = "将删除本机的 Token 和槽位配置并暂停同步。Po0 上的白名单不受影响。",
        actions = listOf(DialogAction("移除", destructive = true) { c.clearToken(); remove = false }, DialogAction("取消") { remove = false }))
    if (rename) {
        var input by remember { mutableStateOf(s.deviceName) }
        Alert("本机名称", { rename = false }, message = "其他设备导入后，会用这个名字标注本机管理的槽位。", content = { Field(input, { input = it.take(24) }, "例如 Mac、办公室电脑") },
            actions = listOf(DialogAction("保存", preferred = true, enabled = input.isNotBlank()) { c.deviceName(input); rename = false },
                DialogAction("取消") { rename = false }))
    }
    if (export) Alert("导出脱敏数据", { export = false },
        message = "Token 已去除，账户标识、网络和路由器信息不导出；IP 只保留前两段（如 203.0.*.0/24）。可用于复盘，也可在其他设备上导入分工。",
        actions = listOf(
            DialogAction("复制", preferred = true) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(c.exportText()), null)
                c.feedback.value = "已复制脱敏数据"; export = false
            },
            DialogAction("保存为文件") {
                export = false
                val dialog = FileDialog(null as Frame?, "导出脱敏数据", FileDialog.SAVE).apply { file = "FuckPo0JiXian-脱敏数据.json"; isVisible = true }
                if (dialog.file != null) runCatching { File(dialog.directory, dialog.file).writeText(c.exportText()) }
                    .onSuccess { c.feedback.value = "已保存" }.onFailure { c.feedback.value = "保存失败" }
            },
            DialogAction("取消") { export = false }))
    peer?.let { p ->
        val preview = remember(p, s) { runCatching { PeerImport.apply(s, p) } }
        val error = (preview.exceptionOrNull() as? IllegalArgumentException)?.message
        val count = preview.getOrNull()?.changed?.size ?: 0
        Alert("导入「${p.device}」的分工？", { peer = null }, message = when {
            error != null -> statusText(error)
            count == 0 -> "本机已经是最新的标注。"
            else -> "将把 $count 个槽位标注为其他设备管理。本机负责的槽位不受影响，也不会写入 Po0。"
        }, actions = if (error != null || count == 0) listOf(DialogAction("好", preferred = true) { peer = null })
            else listOf(DialogAction("导入", preferred = true) { c.importPeers(p); peer = null }, DialogAction("取消") { peer = null }))
    }
}

@Composable private fun SlotEditor(c: DesktopController, s: State, busy: Boolean, original: ManagedSlot, close: () -> Unit) {
    var name by remember { mutableStateOf(original.name) }
    var purpose by remember { mutableStateOf(original.purpose) }
    var writer by remember { mutableStateOf(original.writer) }
    var owner by remember { mutableStateOf(original.owner) }
    var automatic by remember { mutableStateOf(original.automatic) }
    var shared by remember { mutableStateOf(original.shared) }
    var hold by remember { mutableStateOf(original.temporaryHold) }
    var acknowledge by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var bind by remember { mutableStateOf(false) }
    val link by c.link.collectAsState()
    val remote = s.snapshot?.entries?.find { it.slot == original.number }?.cidr
    val identity = s.layout?.identities?.find { it.id == original.identityId }
    val bindable = original.purpose == SlotPurpose.FIXED && original.writer == Writer.LOCAL && original.authorized
    fun save() {
        // On a computer "外出跟随" means: any network that is not bound to a fixed slot.
        val candidate = original.copy(name = name, purpose = purpose, writer = writer, owner = owner, automatic = automatic,
            allowUnknownWifi = purpose == SlotPurpose.MOBILE && automatic, temporaryHold = hold, shared = shared)
        val result = runCatching { LayoutRules.saveSlot(s, candidate, acknowledge, System.currentTimeMillis()) }
        if (result.isSuccess) { c.configureSlot(candidate, acknowledge); error = null
            val saved = result.getOrNull()?.layout?.slots?.find { it.number == original.number }
            if (!(saved != null && saved.purpose == SlotPurpose.FIXED && saved.writer == Writer.LOCAL && saved.authorized && saved.identityId == null)) close()
            else acknowledge = false
        } else error = desktopText((result.exceptionOrNull() as? ApiFailure)?.code ?: result.exceptionOrNull()?.message ?: "CONFIG_INVALID")
    }
    Row(Modifier.fillMaxWidth().background(colors.card).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("完成".takeIf { bindable && identity == null && original.authorized } ?: "取消", style = Type.body, color = colors.accent,
            modifier = Modifier.clickable(onClick = close).padding(10.dp))
        Text("槽 ${original.number + 1}", style = Type.headline, color = colors.label, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Text("保存", style = Type.headline, color = if (busy) colors.tertiary else colors.accent, modifier = Modifier.clickable(enabled = !busy, onClick = ::save).padding(10.dp))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        error?.let { Section { ListRow(it, titleColor = colors.red) } }
        Section(header = "名称", footer = "平台编号 ${original.number} · 当前记录 ${remote?.value ?: "空"}" +
            if (original.changedAt > 0) " · ${time(original.changedAt)} 发现他处改动" else "") {
            Box(Modifier.padding(10.dp)) { Field(name, { name = it.take(40) }, "例如：家、公司、这台电脑") }
        }
        Section(header = "用途", footer = when (purpose) {
            SlotPurpose.FIXED -> "绑定家里或公司的路由器，出口变化时更新此槽。"
            SlotPurpose.MOBILE -> "在未绑定的网络上跟随这台电脑的出口，适合笔记本外出。"
            SlotPurpose.RESERVED -> "留作他用，不会自动更新。"
        }) { Segmented(SlotPurpose.entries.map(::purposeText), purpose.ordinal) { purpose = SlotPurpose.entries[it] } }
        Section(header = "管理者", footer = when (writer) {
            Writer.LOCAL -> "由这台电脑写入此槽。"
            Writer.OTHER_DEVICE -> "由另一台设备管理，本机只读，变化会标注出来。"
            Writer.EXTERNAL -> "来源不明，本机不会改动。"
        }) {
            Segmented(Writer.entries.map(::writerText), writer.ordinal) { writer = Writer.entries[it] }
            if (writer == Writer.OTHER_DEVICE) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(owner, { owner = it.take(24) }, "设备名称，例如 手机、办公室电脑")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("手机", "Mac", "Windows").forEach { Chip(it, owner == it) { owner = it } }
                }
            }
        }
        if (writer == Writer.LOCAL && purpose != SlotPurpose.RESERVED) {
            Section(header = "自动更新") {
                Toggle("自动更新此槽", automatic, { automatic = it })
                if (purpose == SlotPurpose.FIXED) { Hairline(); Toggle("与其他设备共管", shared, { shared = it }, subtitle = "同一网络的手机或电脑也可更新此槽") }
                if (purpose == SlotPurpose.MOBILE) { Hairline(); Toggle("锁定当前 IP", hold, { hold = it }, subtitle = "网络变化时不替换这一格") }
            }
            val authorizedNow = original.authorized && purpose == original.purpose && writer == original.writer &&
                (original.shared || !shared || purpose != SlotPurpose.FIXED)
            if (authorizedNow && !acknowledge) Section(footer = "授权基线：${original.baseline?.value ?: "空槽"}。" +
                if (original.shared) "共管设备的写入会被接受；刚被改成其他值时本机会先等待。" else "此槽被其他设备改动时会自动取消授权。") {
                ListRow("授权本机管理", value = "已授权 ✓")
            } else Section(footer = "当前记录（${remote?.value ?: "空"}）将作为授权基线。" +
                if (shared && purpose == SlotPurpose.FIXED) "同一网络的其他设备也可写入此槽。" else "请确认没有其他设备在写入此槽。") {
                Toggle("授权本机管理", acknowledge, { acknowledge = it })
            }
        }
        if (purpose == SlotPurpose.FIXED && writer == Writer.LOCAL) {
            if (!bindable) Section(header = "网络绑定", footer = "打开「授权本机管理」并保存后，即可绑定当前网络。") { ListRow("保存后可绑定", titleColor = colors.secondary) }
            else Section(header = "网络绑定", footer = "按路由器识别，有线和 Wi-Fi 都适用，不需要定位权限。换路由器后需重新绑定。") {
                ListRow("已绑定", value = identity?.let { "${it.name} · ${it.ssid.removePrefix("gw:")}" } ?: "未绑定")
                Hairline()
                ListRow("当前网络", value = link?.label ?: "未连接")
                Hairline()
                val here = identity != null && identity.ssid == "gw:${link?.gatewayIp}" && identity.aps.any { it.bssid == link?.gatewayMac }
                if (!here) Action(if (identity == null) "绑定当前网络" else "改为绑定当前网络", enabled = !busy && link?.gatewayMac != null) { bind = true }
                if (identity != null) { Hairline(); Action("撤销绑定", color = colors.red, enabled = !busy) { c.revokeNetwork(original.number); close() } }
            }
            if (bindable) Section(footer = if (s.paused) "需先在概览中恢复检查。" else "现场核对出口后更新一次，不保存网络绑定。") {
                Action("手动更新一次", enabled = !busy && !s.paused) { c.previewManual(original.number); close() }
            }
        }
    }
    if (bind) Alert("绑定当前网络？", { bind = false }, message = "${link?.label ?: "未知网络"}\n出口变化时将自动更新槽 ${original.number + 1}。可防止误连，但无法防范刻意仿冒的路由器。",
        actions = listOf(DialogAction("授权并绑定", preferred = true, enabled = !busy) { c.bindNetwork(original.number, name); bind = false; close() },
            DialogAction("取消") { bind = false }))
}

@Composable private fun ManualDialog(c: DesktopController, busy: Boolean) {
    val permit by c.manualPreview.collectAsState()
    permit?.let { p ->
        LaunchedEffect(p) { kotlinx.coroutines.delay((p.expires - System.currentTimeMillis()).coerceAtLeast(0)); c.cancelManual() }
        Alert("更新槽 ${p.slot + 1}", c::cancelManual, message = "${p.before.entries.find { it.slot == p.slot }?.cidr?.value ?: "空"}  →  ${p.before.current.value}\n" +
            "3 分钟内有效，执行前会再次核对。", actions = listOf(DialogAction("确认更新", preferred = true, enabled = !busy, onClick = c::confirmManual),
            DialogAction("取消", onClick = c::cancelManual)))
    }
}
