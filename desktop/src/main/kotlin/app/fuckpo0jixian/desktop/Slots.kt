package app.fuckpo0jixian.desktop

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fuckpo0jixian.core.*
import app.fuckpo0jixian.core.State

internal fun purposeText(p: SlotPurpose) = when (p) { SlotPurpose.FIXED -> "固定网络"; SlotPurpose.MOBILE -> "外出跟随"; SlotPurpose.RESERVED -> "保留用途" }
@Composable internal fun purposeColor(p: SlotPurpose) = when (p) { SlotPurpose.FIXED -> colors.accent; SlotPurpose.MOBILE -> colors.green; SlotPurpose.RESERVED -> colors.gray }
@Composable internal fun statusTint(code: String) = when (code) { in healthy -> colors.green; in attention -> colors.orange; else -> colors.gray }

/** Who writes this slot, readable at a glance: this computer, a named device, or unknown; plus co-management. */
@Composable internal fun ManagerTags(slot: ManagedSlot) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (slot.writer) {
            Writer.LOCAL -> Tag("本机", colors.accent, Glyph.Computer)
            Writer.OTHER_DEVICE -> Tag(slot.owner.ifBlank { "其他设备" }, colors.indigo, Glyph.Devices)
            Writer.EXTERNAL -> Tag("外部", colors.gray)
        }
        if (slot.shared) Tag("共管", colors.green, Glyph.People)
    }
}

/** The capacity bar: one cell per slot number, coloured by that slot's purpose. */
@Composable internal fun CapacityCells(s: State, snap: Snapshot, height: androidx.compose.ui.unit.Dp = 6.dp) {
    val cells = CapacityBar.cells(snap, s.layout)
    Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        cells.forEach { cell ->
            val tint by animateColorAsState(cell?.let { purposeColor(it) } ?: colors.fill, label = "cell")
            Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(tint))
        }
    }
}

/**
 * One slot. Wide (overview): name and manager on the first line, status below, address on the right.
 * Compact (whitelist list): name and status dot, then address and manager.
 */
@Composable internal fun SlotLine(slot: ManagedSlot, remote: Cidr?, selected: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    val c = colors
    val name = slot.name.ifBlank { "槽 ${slot.number + 1}" }
    Row(Modifier.fillMaxWidth().background(if (selected) c.selected else Color.Transparent).hoverBackground(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Badge("${slot.number + 1}", purposeColor(slot.purpose))
        if (compact) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = Type.body.copy(fontWeight = FontWeight.Medium), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Dot(statusTint(slot.status), 7.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(remote?.value ?: "空", style = Type.caption.copy(fontFeatureSettings = "tnum"), color = if (remote == null) c.tertiary else c.secondary,
                    maxLines = 1, modifier = Modifier.weight(1f))
                ManagerTags(slot)
            }
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, style = Type.body.copy(fontWeight = FontWeight.Medium), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ManagerTags(slot)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(statusTint(slot.status), 6.dp)
                    Text(desktopText(slot.status), style = Type.caption, color = if (slot.status in attention) c.orange else c.secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(remote?.value ?: "空", style = Type.numeric, color = if (remote == null) c.tertiary else c.secondary)
        }
    }
}

@Composable internal fun SlotsPage(c: DesktopController, s: State, busy: Boolean, selected: Int, select: (Int) -> Unit) {
    val snap = s.snapshot
    val mac = theme.look == Look.MAC
    if (snap == null) {
        PageScroll(Page.SLOTS.title) {
            Group(footer = "先在「设置」中添加 Token 并检查连接。") { SettingRow("尚未获取白名单", icon = Glyph.List, iconTint = colors.gray, titleColor = colors.secondary) }
        }
        return
    }
    val number = selected.coerceIn(0, snap.capacity - 1)
    Row(Modifier.fillMaxSize()) {
        // Master: the whole list, always visible.
        Column(Modifier.width(if (mac) 296.dp else 304.dp).fillMaxHeight()
            .padding(start = if (mac) 20.dp else 28.dp, top = theme.metrics.titleBarInset + if (mac) 8.dp else 24.dp, bottom = 20.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(Page.SLOTS.title, style = Type.pageTitle, color = colors.label)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("已用 ${snap.entries.size} / ${snap.capacity}", style = Type.headline, color = colors.label)
                    Spacer(Modifier.weight(1f))
                    Text("剩余 ${snap.remaining}", style = Type.caption, color = colors.secondary)
                }
                CapacityCells(s, snap)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(SlotPurpose.FIXED, SlotPurpose.MOBILE, SlotPurpose.RESERVED).forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Dot(purposeColor(p), 6.dp); Text(purposeText(p).take(2), style = Type.caption, color = colors.secondary)
                        }
                    }
                }
            }
            val scroll = rememberScrollState()
            Card(Modifier.weight(1f, fill = false)) {
                Column(Modifier.verticalScroll(scroll)) {
                    (0 until snap.capacity).forEach { n ->
                        if (n > 0) Divider(50.dp)
                        SlotLine(s.layout?.slots?.find { it.number == n } ?: ManagedSlot(n), snap.entries.find { it.slot == n }?.cidr, n == number, compact = true) { select(n) }
                    }
                    snap.entries.filter { it.slot == null }.forEach { e ->
                        Divider(50.dp)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Badge("–", colors.gray)
                            Text("未分配槽号 · 受保护", style = Type.caption, color = colors.secondary, modifier = Modifier.weight(1f))
                            Text(e.cidr.value, style = Type.numeric, color = colors.secondary)
                        }
                    }
                }
            }
            Text("修改只保存在本机，开启自动同步后才写入 Po0。", style = Type.caption, color = colors.secondary)
        }
        Box(Modifier.width(0.5.dp).fillMaxHeight().padding(top = theme.metrics.titleBarInset).background(colors.separator))
        // Detail: everything about one slot, edited in place.
        Box(Modifier.weight(1f).fillMaxHeight()) {
            key(number, s.layout?.version) {
                SlotDetail(c, s, busy, s.layout?.slots?.find { it.number == number } ?: ManagedSlot(number))
            }
        }
    }
}

@Composable private fun SlotDetail(c: DesktopController, s: State, busy: Boolean, original: ManagedSlot) {
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
    val candidate = original.copy(name = name, purpose = purpose, writer = writer, owner = owner, automatic = automatic,
        // On a computer "外出跟随" means: any network that is not bound to a fixed slot.
        allowUnknownWifi = purpose == SlotPurpose.MOBILE && automatic, temporaryHold = hold, shared = shared)
    val dirty = candidate != original || acknowledge
    fun save() {
        val result = runCatching { LayoutRules.saveSlot(s, candidate, acknowledge, System.currentTimeMillis()) }
        if (result.isSuccess) { c.configureSlot(candidate, acknowledge); error = null; acknowledge = false }
        else error = desktopText((result.exceptionOrNull() as? ApiFailure)?.code ?: result.exceptionOrNull()?.message ?: "CONFIG_INVALID")
    }
    val mac = theme.look == Look.MAC
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (mac) 24.dp else 32.dp)
            .padding(top = theme.metrics.titleBarInset + if (mac) 8.dp else 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // Header: identity of the slot and who owns it, then the only two actions that matter here.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Badge("${original.number + 1}", purposeColor(purpose), size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name.ifBlank { "槽 ${original.number + 1}" }, style = Type.title, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(remote?.value ?: "空槽", style = Type.numeric, color = colors.secondary)
                        ManagerTags(original)
                    }
                }
                AnimatedVisibility(dirty, enter = fadeIn(), exit = fadeOut()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button("还原", { name = original.name; purpose = original.purpose; writer = original.writer; owner = original.owner
                            automatic = original.automatic; shared = original.shared; hold = original.temporaryHold; acknowledge = false; error = null })
                        Button("保存", ::save, ButtonKind.PRIMARY, enabled = !busy)
                    }
                }
            }
            if (original.changedAt > 0 || original.status in attention || error != null) Card {
                error?.let { SettingRow(it, icon = Glyph.Warning, iconTint = colors.red, titleColor = colors.red) }
                if (original.status in attention) SettingRow(desktopText(original.status), icon = Glyph.Warning, iconTint = colors.orange)
                if (original.changedAt > 0) SettingRow("${time(original.changedAt)} 发现他处改动", subtitle = "平台上的记录被其他设备或外部改过",
                    icon = Glyph.Clock, iconTint = colors.orange)
            }
            Group(footer = when {
                purpose == SlotPurpose.RESERVED -> "留作他用，不会自动更新。"
                writer == Writer.OTHER_DEVICE -> "另一台设备负责，本机只读并标注变化。"
                writer == Writer.EXTERNAL -> "来源不明，本机不会改动。"
                purpose == SlotPurpose.FIXED -> "绑定家里或公司的路由器，出口变化时由本机更新。"
                else -> "在未绑定的网络上跟随这台电脑，适合笔记本外出。"
            }) {
                FieldRow("名称") { TextField(name, { name = it.take(40) }, "例如：家、公司、这台电脑") }
                FieldRow("用途") { Segmented(SlotPurpose.entries.map(::purposeText), purpose.ordinal, { purpose = SlotPurpose.entries[it] }, modifier = Modifier.fillMaxWidth()) }
                FieldRow("管理者") {
                    Segmented(listOf("本机", "其他设备", "外部"), writer.ordinal, { writer = Writer.entries[it] }, modifier = Modifier.fillMaxWidth())
                    if (writer == Writer.OTHER_DEVICE) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextField(owner, { owner = it.take(24) }, "设备名称", modifier = Modifier.weight(1f))
                        listOf("手机", "Mac", "Windows").forEach { choice ->
                            Box(Modifier.clip(RoundedCornerShape(5.dp)).hoverBackground { owner = choice }) { Tag(choice, colors.indigo, strong = owner == choice) }
                        }
                    }
                }
            }
            if (writer == Writer.LOCAL && purpose != SlotPurpose.RESERVED) {
                Group("自动更新") {
                    ToggleRow("自动更新此槽", automatic, { automatic = it }, icon = Glyph.Sync, iconTint = colors.green)
                    if (purpose == SlotPurpose.FIXED) { Divider(48.dp); ToggleRow("与其他设备共管", shared, { shared = it }, subtitle = "同一网络的手机或电脑也可更新", icon = Glyph.People, iconTint = colors.green) }
                    if (purpose == SlotPurpose.MOBILE) { Divider(48.dp); ToggleRow("锁定当前 IP", hold, { hold = it }, subtitle = "网络变化时不替换这一格", icon = Glyph.Lock, iconTint = colors.gray) }
                }
                // Any edit that adds automatic power must bring the switch back, or saving deadlocks.
                val authorizedNow = original.authorized && purpose == original.purpose && writer == original.writer &&
                    !LayoutRules.needsAuthorization(original, candidate)
                Group("授权", footer = if (authorizedNow && !acknowledge) "基线 ${original.baseline?.value ?: "空槽"}。" +
                    (if (original.shared) "接受共管设备的写入。" else "被其他设备改动时会自动取消授权。")
                    else (if (original.authorized) "新增了自动更新权限，需再次确认。" else "") + "以当前记录（${remote?.value ?: "空"}）为基线。" + if (shared && purpose == SlotPurpose.FIXED) "" else "请确认没有其他设备写入此槽。") {
                    if (authorizedNow && !acknowledge) SettingRow("授权本机管理", icon = Glyph.Shield, iconTint = colors.green) { Tag("已授权", colors.green, Glyph.Check) }
                    else ToggleRow("授权本机管理", acknowledge, { acknowledge = it }, icon = Glyph.Shield, iconTint = colors.orange)
                }
            }
            if (purpose == SlotPurpose.FIXED && writer == Writer.LOCAL) {
                if (!bindable) Group("网络绑定", footer = "授权并保存后即可绑定当前网络。") { SettingRow("保存后可绑定", icon = Glyph.Router, iconTint = colors.gray, titleColor = colors.secondary) }
                else Group("网络绑定", footer = "按路由器识别，有线和 Wi-Fi 都适用，不需要定位权限。") {
                    ValueRow("已绑定", identity?.let { "${it.name} · ${it.ssid.removePrefix("gw:")}" } ?: "未绑定", icon = Glyph.Router, iconTint = colors.accent)
                    Divider(48.dp)
                    val here = identity != null && identity.ssid == "gw:${link?.gatewayIp}" && identity.aps.any { it.bssid == link?.gatewayMac }
                    SettingRow("当前网络", subtitle = link?.label ?: "未连接", icon = Glyph.Globe, iconTint = colors.indigo) {
                        if (here) Tag("就是这里", colors.green, Glyph.Check)
                        else Button(if (identity == null) "绑定" else "改绑到这里", { bind = true }, enabled = !busy && link?.gatewayMac != null)
                    }
                    if (identity != null) { Divider(48.dp); SettingRow("撤销绑定", icon = Glyph.Power, iconTint = colors.red, titleColor = colors.red, enabled = !busy, onClick = { c.revokeNetwork(original.number) }) }
                }
                if (bindable) Group(footer = if (s.paused) "需先恢复检查。" else "现场核对出口后更新一次，不保存绑定。") {
                    SettingRow("手动更新一次", icon = Glyph.Sync, iconTint = colors.accent, enabled = !busy && !s.paused, onClick = { c.previewManual(original.number) })
                }
            }
            Text("平台编号 ${original.number}", style = Type.caption, color = colors.tertiary)
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp))
    }
    if (bind) Alert("绑定当前网络？", { bind = false }, message = "${link?.label ?: "未知网络"}\n出口变化时自动更新槽 ${original.number + 1}。能防误连，防不了刻意仿冒的路由器。",
        actions = listOf(DialogAction("授权并绑定", preferred = true, enabled = !busy) { c.bindNetwork(original.number, name); bind = false },
            DialogAction("取消") { bind = false }))
}
