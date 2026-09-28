package app.fuckpo0jixian.desktop

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
/** "刚刚", "5 分钟前", "3 小时前", else the date. */
fun ago(value: Long, now: Long = System.currentTimeMillis()): String {
    if (value <= 0) return "暂无"
    val minutes = (now - value) / 60_000
    return when { minutes < 1 -> "刚刚"; minutes < 60 -> "$minutes 分钟前"; minutes < 24 * 60 -> "${minutes / 60} 小时前"; else -> time(value) }
}

enum class Page(val title: String, val icon: ImageVector) {
    OVERVIEW("概览", Glyph.Overview), SLOTS("白名单", Glyph.List), RECORDS("记录", Glyph.History), SETTINGS("设置", Glyph.Settings)
}

internal val healthy = setOf("SLOT_CURRENT", "SLOT_UPDATED", "RECOVERED_VERIFIED", "AUTHORIZED_LOCAL", "PEER_UPDATED")
internal val attention = setOf("SLOT_CONFLICT", "SLOT_VERIFY_FAILED", "IDENTITY_AMBIGUOUS", "WIFI_SECURITY_CHANGED", "EXTERNAL_CHANGE",
    "COVERED_OTHER_SLOT", "PENDING_REVIEW", "PENDING_CONFIG_CHANGED", "LEGACY_UNINITIALIZED", "SHARED_RECENT")

/** Which bound network (if any) the computer is on right now. */
internal fun matchedName(s: State, link: DesktopLink?): String? {
    val o = link?.observation(System.currentTimeMillis()) ?: return null
    return s.layout?.identities?.singleOrNull { it.ssid == o.ssid && AuthorizedAp(o.bssid!!, o.security) in it.aps }?.name
}

internal data class Headline(val title: String, val tint: Color, val icon: ImageVector)
@Composable internal fun headline(s: State, busy: Boolean, credential: Boolean): Headline = when {
    busy -> Headline("正在检查", colors.accent, Glyph.Sync)
    !credential -> Headline("未连接 Po0", colors.gray, Glyph.Key)
    s.globalBlock != null || s.authBlocked -> Headline("需要处理", colors.red, Glyph.Warning)
    s.paused -> Headline("已暂停", colors.orange, Glyph.Pause)
    s.mode == Mode.AUTO && s.layout != null -> Headline("自动同步中", colors.green, Glyph.Shield)
    else -> Headline("仅查询", colors.accent, Glyph.Info)
}

/**
 * The main window: sidebar navigation plus the selected page. [titleBar] draws the draggable strip over the
 * top of a macOS window whose content runs under its transparent title bar.
 */
@Composable fun App(c: DesktopController, initialPage: Page = Page.OVERVIEW, initialSlot: Int? = null,
                    titleBar: (@Composable (Modifier) -> Unit)? = null) {
    val s by c.store.flow.collectAsState()
    val busy by c.busy.collectAsState()
    val feedback by c.feedback.collectAsState()
    var page by remember { mutableStateOf(initialPage) }
    var slot by remember { mutableStateOf(initialSlot ?: 0) }
    val requested by c.requestedPage.collectAsState()
    LaunchedEffect(requested) { requested?.let { page = it; c.requestedPage.value = null } }
    Box(Modifier.fillMaxSize().background(colors.window)) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(c, s, busy, page) { page = it }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Crossfade(page, label = "page") { p ->
                    when (p) {
                        Page.SLOTS -> SlotsPage(c, s, busy, slot) { slot = it }
                        else -> PageScroll(p.title, header = { if (p == Page.OVERVIEW) OverviewActions(c, s, busy) }) {
                            when (p) {
                                Page.OVERVIEW -> Overview(c, s, busy, open = { n -> slot = n; page = Page.SLOTS }, go = { page = it })
                                Page.RECORDS -> Records(c, s)
                                else -> Settings(c, s, busy)
                            }
                        }
                    }
                }
                if (feedback.isNotBlank() && feedback != desktopText(s.status)) Toast(feedback, Modifier.align(Alignment.BottomCenter))
            }
        }
        titleBar?.invoke(Modifier.fillMaxWidth().height(theme.metrics.titleBarInset))
    }
    ManualDialog(c, busy)
}

@Composable internal fun PageScroll(title: String, header: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val mac = theme.look == Look.MAC
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (mac) 28.dp else 36.dp)
            .padding(top = theme.metrics.titleBarInset + if (mac) 8.dp else 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(if (mac) 22.dp else 24.dp)) {
            Row(Modifier.fillMaxWidth().widthIn(max = 760.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = Type.pageTitle, color = colors.label, modifier = Modifier.weight(1f))
                header()
            }
            Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(if (mac) 22.dp else 24.dp), content = content)
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp))
    }
}

@Composable private fun Sidebar(c: DesktopController, s: State, busy: Boolean, page: Page, select: (Page) -> Unit) {
    val mac = theme.look == Look.MAC
    val credential by c.credentialPresent.collectAsState()
    val update by c.updater.state.collectAsState()
    val h = headline(s, busy, credential)
    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(theme.metrics.sidebarWidth).fillMaxHeight().background(colors.sidebar)
            .padding(top = theme.metrics.titleBarInset + if (mac) 6.dp else 16.dp, start = 10.dp, end = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Identity block: which app, which device.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (mac) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(if (mac) 28.dp else 36.dp).clip(RoundedCornerShape(if (mac) 7.dp else 18.dp)).background(colors.accent), contentAlignment = Alignment.Center) {
                    Icon(Glyph.Shield, null, tint = Color.White, modifier = Modifier.size(if (mac) 17.dp else 20.dp))
                }
                Column {
                    Text("去他妈的鸡险", style = Type.headline, color = colors.label, maxLines = 1)
                    Text("本机 · ${s.deviceName.ifBlank { "未命名" }}", style = Type.caption, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(if (mac) 10.dp else 14.dp))
            Page.entries.forEach { p -> NavItem(p, p == page, badge = p == Page.SETTINGS && update is UpdateState.Available) { select(p) } }
            Spacer(Modifier.weight(1f))
            // Live status, always in view.
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(theme.metrics.radius)).background(colors.fill).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PulseDot(h.tint, pulsing = busy, size = 7.dp)
                Column(Modifier.weight(1f)) {
                    Text(h.title, style = Type.body.copy(fontWeight = FontWeight.Medium), color = colors.label, maxLines = 1)
                    Text(when { busy -> "请稍候"; s.lastCheck <= 0 -> "尚未检查"; else -> "检查于 ${ago(s.lastCheck)}" }, style = Type.caption, color = colors.secondary, maxLines = 1)
                }
            }
        }
        if (mac) Box(Modifier.width(0.5.dp).fillMaxHeight().background(colors.sidebarBorder))
    }
}

@Composable private fun NavItem(p: Page, selected: Boolean, badge: Boolean, onClick: () -> Unit) {
    val mac = theme.look == Look.MAC
    val shape = RoundedCornerShape(if (mac) 6.dp else 4.dp)
    Box(Modifier.fillMaxWidth().height(if (mac) 30.dp else 36.dp).clip(shape).background(if (selected) colors.selected else Color.Transparent)
        .hoverBackground(onClick = onClick)) {
        if (selected && !mac) Box(Modifier.align(Alignment.CenterStart).size(3.dp, 16.dp).clip(CircleShape).background(colors.accent))
        Row(Modifier.fillMaxSize().padding(horizontal = if (mac) 8.dp else 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (mac) 8.dp else 14.dp)) {
            Icon(p.icon, null, tint = if (selected || !mac) (if (mac) colors.accent else colors.label) else colors.secondary, modifier = Modifier.size(if (mac) 16.dp else 17.dp))
            Text(p.title, style = Type.body.copy(fontWeight = if (selected && mac) FontWeight.Medium else FontWeight.Normal), color = colors.label, modifier = Modifier.weight(1f))
            if (badge) Dot(colors.red)
        }
    }
}

// ---- Overview ----

@Composable private fun OverviewActions(c: DesktopController, s: State, busy: Boolean) {
    val credential by c.credentialPresent.collectAsState()
    Button(if (s.paused) "恢复" else "暂停", { c.pause(!s.paused) }, icon = if (s.paused) Glyph.Play else Glyph.Pause)
    Button("立即检查", c::check, ButtonKind.PRIMARY, icon = Glyph.Sync, enabled = !busy && !s.paused && credential, loading = busy)
}

@Composable private fun Overview(c: DesktopController, s: State, busy: Boolean, open: (Int) -> Unit, go: (Page) -> Unit) {
    val link by c.link.collectAsState()
    val credential by c.credentialPresent.collectAsState()
    val h = headline(s, busy, credential)
    // Hero: one sentence of state, big and calm.
    Card(padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(colors.soft(h.tint)), contentAlignment = Alignment.Center) {
                AnimatedContent(h.icon, label = "icon") { Icon(it, null, tint = h.tint, modifier = Modifier.size(26.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                AnimatedContent(h.title, label = "title") { Text(it, style = Type.title, color = colors.label) }
                Text(when { busy -> "请稍候"; s.lastCheck <= 0 -> desktopText(s.status); else -> "${desktopText(s.status)} · ${ago(s.lastCheck)}检查" },
                    style = Type.body, color = colors.secondary)
            }
        }
        if (busy) { Spacer(Modifier.height(16.dp)); ProgressBar(null) }
    }
    if (!credential) Group(footer = "添加 Po0 Token 后才能查询白名单。") {
        SettingRow("连接 Po0", icon = Glyph.Key, onClick = { go(Page.SETTINGS) })
    }
    Notices(s)
    // Three facts at a glance.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val exit = s.snapshot?.current?.value
        val d = s.domesticExit
        Fact("当前出口", exit ?: "未检查", if (d != null && s.snapshot != null) (if (d.cidr == s.snapshot!!.current) "直连出口一致" else "直连出口 ${d.cidr.value}") else "Po0 识别",
            Glyph.Globe, Modifier.weight(1f), mono = exit != null)
        Fact("网络", matchedName(s, link) ?: link?.label ?: "未连接", matchedName(s, link)?.let { link?.label } ?: if (link?.online == true) "未绑定到固定槽" else "",
            Glyph.Router, Modifier.weight(1f))
        s.snapshot?.let { snap ->
            Card(Modifier.weight(1f).fillMaxHeight(), padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Glyph.List, null, tint = colors.secondary, modifier = Modifier.size(14.dp))
                    Text("名额", style = Type.caption, color = colors.secondary)
                }
                Spacer(Modifier.height(6.dp))
                Text("${snap.entries.size} / ${snap.capacity}", style = Type.title, color = colors.label)
                Spacer(Modifier.height(8.dp))
                CapacityCells(s, snap)
            }
        } ?: Fact("名额", "未获取", "", Glyph.List, Modifier.weight(1f))
    }
    s.snapshot?.let { snap ->
        Group("槽位", action = { Button("管理", { go(Page.SLOTS) }, ButtonKind.SUBTLE) }) {
            (0 until snap.capacity).forEach { n ->
                if (n > 0) Divider(50.dp)
                val slot = s.layout?.slots?.find { it.number == n } ?: ManagedSlot(n)
                SlotLine(slot, snap.entries.find { it.slot == n }?.cidr, onClick = { open(n) })
            }
        }
    }
    val events = s.events.takeLast(4).reversed()
    if (events.isNotEmpty()) Group("最近事件", action = { Button("全部", { go(Page.RECORDS) }, ButtonKind.SUBTLE) }) {
        events.forEachIndexed { i, e -> if (i > 0) Divider(); EventRow(e) }
    }
}

@Composable private fun Fact(label: String, value: String, detail: String, icon: ImageVector, modifier: Modifier, mono: Boolean = false) {
    Card(modifier.fillMaxHeight(), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = colors.secondary, modifier = Modifier.size(14.dp))
            Text(label, style = Type.caption, color = colors.secondary)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = if (mono) Type.title.copy(fontFeatureSettings = "tnum") else Type.title, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(detail, style = Type.caption, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable private fun Notices(s: State) {
    val items = listOfNotNull(
        s.globalBlock?.let(::desktopText), "Token 无效，请在设置中更换".takeIf { s.authBlocked },
        "有待确认的写入，下次检查会先核对".takeIf { s.layout?.pending != null },
        s.layout?.slots?.count { it.status in attention }?.takeIf { it > 0 }?.let { "$it 个槽位需要处理" })
    if (items.isEmpty()) return
    Card {
        items.forEachIndexed { i, text ->
            if (i > 0) Divider(44.dp)
            SettingRow(text, icon = Glyph.Warning, iconTint = colors.orange)
        }
    }
}

@Composable internal fun EventRow(e: Event) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Dot(eventTint(e.code), 6.dp)
        Text(desktopText(e.code), style = Type.body, color = colors.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(time(e.time), style = Type.caption, color = colors.secondary)
    }
}

@Composable private fun eventTint(code: String) = when {
    code in healthy || code in setOf("PRESENT_CURRENT_CHECK", "LOCAL_UNCHANGED", "MOBILE_SLOT_VERIFIED") -> colors.green
    code in attention || code.startsWith("HTTP_") || code.contains("FAILED") || code.contains("ERROR") -> colors.orange
    else -> colors.gray
}

// ---- Records ----

@Composable private fun Records(c: DesktopController, s: State) {
    val now = System.currentTimeMillis()
    val history = remember(s.observations) { NetworkHistory.summarize(s.observations, now) }
    var confirm by remember { mutableStateOf(false) }
    Group("检查") {
        ValueRow("状态", desktopText(s.status), icon = Glyph.Shield, iconTint = colors.green)
        Divider(48.dp); ValueRow("上次检查", time(s.lastCheck), icon = Glyph.Clock, iconTint = colors.accent)
        Divider(48.dp); ValueRow("下次可请求", if (s.nextAllowed > now) time(s.nextAllowed) else "随时", icon = Glyph.Sync, iconTint = colors.indigo)
        Divider(48.dp); ValueRow("连续失败", "${s.failures} 次", icon = Glyph.Warning, iconTint = if (s.failures > 0) colors.orange else colors.gray,
            valueColor = if (s.failures > 0) colors.orange else colors.secondary)
    }
    Group("国内出口", footer = "写入前要求直连出口与 Po0 识别一致，代理的地址不会写入。") {
        val d = s.domesticExit
        SettingRow("直连出口", subtitle = d?.let { "${if (it.source == ProbeSource.STUN) "STUN（物理网卡）" else "ip.3322.net"} · ${time(it.time)}" }
            ?: desktopText(s.probeStatus), icon = Glyph.Router, iconTint = colors.accent) {
            Text(d?.ipv4 ?: "未查询", style = Type.numeric, color = colors.secondary)
        }
        Divider(48.dp)
        SettingRow("Po0 识别", icon = Glyph.Globe, iconTint = colors.indigo) {
            s.snapshot?.current?.let { cur ->
                if (d != null) Tag(if (d.cidr == cur) "一致" else "不一致", if (d.cidr == cur) colors.green else colors.orange)
            }
            Text(s.snapshot?.current?.value ?: "未检查", style = Type.numeric, color = colors.secondary)
        }
        Divider(48.dp)
        SettingRow("比对出口", subtitle = "只查出口，不请求 Po0", icon = Glyph.Sync, iconTint = colors.green) {
            Button("立即比对", c::checkDomestic, enabled = !s.paused)
        }
    }
    Group("常用网络", footer = "近 7 天的使用统计，只作参考，不会据此修改白名单。") {
        if (history.isEmpty()) SettingRow("暂无记录", titleColor = colors.secondary)
        history.take(3).forEachIndexed { i, n -> if (i > 0) Divider(); NetworkRow(n) }
        if (history.size > 3) { Divider(); Expander("其余 ${history.size - 3} 个") { history.drop(3).take(17).forEach { Divider(); NetworkRow(it) } } }
    }
    val events = s.events.filter { it.time >= now - c.policy.retentionMs }.reversed()
    Group("事件", footer = "只反映 Po0 与出口查询看到的结果。") {
        if (events.isEmpty()) SettingRow("暂无事件", titleColor = colors.secondary)
        events.take(5).forEachIndexed { i, e -> if (i > 0) Divider(); EventRow(e) }
        if (events.size > 5) { Divider(); Expander("显示全部 ${events.size} 条") { events.drop(5).forEach { Divider(); EventRow(it) } } }
    }
    Group("诊断") {
        val trace = remember(s.lastCheck) { BoundTransport.trace() }
        Expander("请求记录", subtitle = if (trace.isEmpty()) "本次运行还没有请求" else "最近 ${trace.size} 条 · IP 只保留前两段", icon = Glyph.Code, iconTint = colors.gray) {
            if (trace.isNotEmpty()) {
                Divider()
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        trace.takeLast(40).forEach { Text(it, style = Type.mono, color = colors.secondary) }
                    }
                }
                Row(Modifier.padding(start = 14.dp, bottom = 12.dp)) {
                    Button("复制", { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(trace.joinToString("\n")), null); c.feedback.value = "已复制" })
                }
            }
        }
    }
    Row { Button("清空记录", { confirm = true }, ButtonKind.DESTRUCTIVE) }
    if (confirm) Alert("清空记录？", { confirm = false }, message = "清空网络历史和事件，不影响设置与白名单。",
        actions = listOf(DialogAction("清空", destructive = true) { c.clearHistory(); confirm = false }, DialogAction("取消") { confirm = false }))
}

@Composable private fun NetworkRow(n: FamiliarNetwork) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(n.cidr.value, style = Type.numeric, color = colors.label, modifier = Modifier.weight(1f))
        if (n.common) Tag("常用", colors.green)
        Text("${n.days} 天 · ${n.visits} 次 · ${time(n.lastSeen)}", style = Type.caption, color = colors.secondary)
    }
}

// ---- Settings ----

@Composable private fun Settings(c: DesktopController, s: State, busy: Boolean) {
    val credential by c.credentialPresent.collectAsState()
    val notify by c.notify.collectAsState()
    var tokenDialog by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var peer by remember { mutableStateOf<PeerLayout?>(null) }
    var autostart by remember { mutableStateOf(runCatching { Autostart.enabled() }.getOrDefault(false)) }
    UpdateGroup(c)
    Group("Po0 账户", footer = "Token 保存在${if (os == Os.WINDOWS) " Windows 凭据加密（DPAPI）" else "系统钥匙串"}中。") {
        SettingRow("Token", subtitle = if (credential) "上次连接 ${time(s.lastSuccess)}" else "未添加", icon = Glyph.Key, iconTint = colors.accent) {
            if (credential) Button("检查连接", c::checkConnection, enabled = !busy, loading = busy)
            Button(if (credential) "更换" else "添加", { tokenDialog = true }, if (credential) ButtonKind.SECONDARY else ButtonKind.PRIMARY, enabled = !busy)
        }
        if (credential) { Divider(48.dp); SettingRow("移除连接", subtitle = "删除本机 Token 与槽位配置", icon = Glyph.Power, iconTint = colors.red, titleColor = colors.red, enabled = !busy) { Button("移除", { remove = true }, ButtonKind.DESTRUCTIVE) } }
    }
    Group("同步") {
        ToggleRow("自动同步", s.mode == Mode.AUTO, { c.mode(if (it) Mode.AUTO else Mode.OBSERVE) },
            subtitle = if (s.layout == null) "先在白名单中授权槽位" else if (s.mode == Mode.AUTO) "按已授权的槽位更新" else "仅查询，不修改白名单",
            icon = Glyph.Sync, iconTint = colors.green, enabled = !busy && (s.layout != null || s.mode == Mode.AUTO))
        Divider(48.dp)
        SettingRow("兜底检查间隔", subtitle = "网络不变时本机比对出口，变了才查 Po0", icon = Glyph.Clock, iconTint = colors.indigo) {
            Stepper(s.fallbackMinutes, FallbackInterval.MIN..FallbackInterval.MAX, c::fallbackMinutes) { "$it 分钟" }
        }
        Divider(48.dp)
        ValueRow("切换网络后", "约 3 秒", icon = Glyph.Router, iconTint = colors.accent)
        Divider(48.dp)
        ValueRow("与 Po0 核对", "至少每小时", icon = Glyph.Globe, iconTint = colors.gray)
    }
    Group("提醒") {
        ToggleRow("异常时通知", notify, c::notify, subtitle = "写入失败、槽位冲突、Token 失效等", icon = Glyph.Bell, iconTint = colors.red)
    }
    Group("多设备", footer = "每台设备只授权自己负责的槽位；导入只标注，不授权也不写入。") {
        SettingRow("本机名称", icon = Glyph.Computer, iconTint = colors.gray, onClick = { rename = true }) {
            Text(s.deviceName.ifBlank { "未设置" }, style = Type.body, color = colors.secondary)
            Icon(Glyph.Chevron, null, tint = colors.tertiary, modifier = Modifier.size(16.dp))
        }
        Divider(48.dp)
        SettingRow("导入其他设备的分工", subtitle = "读取剪贴板中的脱敏数据", icon = Glyph.Devices, iconTint = colors.indigo, enabled = !busy && s.snapshot != null, onClick = {
            val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrDefault("")
            runCatching { PeerImport.parse(text) }.onSuccess { peer = it }.onFailure { c.feedback.value = statusText("IMPORT_INVALID") }
        })
        Divider(48.dp)
        SettingRow("导出脱敏数据", subtitle = "用于复盘，或在其他设备导入", icon = Glyph.Download, iconTint = colors.green, onClick = { export = true })
    }
    Group("通用") {
        ToggleRow("开机启动", autostart, { Autostart.set(it); autostart = Autostart.enabled() }, icon = Glyph.Power, iconTint = colors.gray,
            subtitle = if (Autostart.available) "登录后在${if (os == Os.MAC) "菜单栏" else "托盘"}运行" else "安装版可用", enabled = Autostart.available)
    }
    Text("按路由器识别网络，不需要定位权限。出口直接从本机网卡核对，开着代理或 TUN 也不会把代理地址写入白名单。",
        style = Type.caption, color = colors.secondary, modifier = Modifier.padding(horizontal = 2.dp))
    if (tokenDialog) {
        var input by remember { mutableStateOf("") }
        val token = Po0Credential.extract(input)
        Alert("连接 Po0", { tokenDialog = false }, message = "粘贴 Token 或官方接口链接", content = {
            TextField(input, { input = it.take(2048) }, "pgnfw_…", secret = true)
            Text(if (input.isNotBlank() && token == null) "格式不正确，应以 pgnfw_ 开头或为官方链接" else "保存后检查会先暂停。",
                style = Type.caption, color = if (input.isNotBlank() && token == null) colors.red else colors.secondary)
        }, actions = listOf(DialogAction("保存", preferred = true, enabled = token != null) { token?.let(c::saveToken); tokenDialog = false },
            DialogAction("取消") { tokenDialog = false }))
    }
    if (remove) Alert("移除连接？", { remove = false }, message = "将删除本机的 Token 和槽位配置并暂停同步。Po0 上的白名单不受影响。",
        actions = listOf(DialogAction("移除", destructive = true) { c.clearToken(); remove = false }, DialogAction("取消") { remove = false }))
    if (rename) {
        var input by remember { mutableStateOf(s.deviceName) }
        Alert("本机名称", { rename = false }, message = "其他设备导入后，会用这个名字标注本机管理的槽位。",
            content = { TextField(input, { input = it.take(24) }, "例如 Mac、办公室电脑") },
            actions = listOf(DialogAction("保存", preferred = true, enabled = input.isNotBlank()) { c.deviceName(input); rename = false },
                DialogAction("取消") { rename = false }))
    }
    if (export) Alert("导出脱敏数据", { export = false },
        message = "不含 Token、账户标识、网络和路由器信息；IP 只保留前两段。",
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
            else -> "将把 $count 个槽位标注为其他设备管理，不影响本机负责的槽位。"
        }, actions = if (error != null || count == 0) listOf(DialogAction("好", preferred = true) { peer = null })
            else listOf(DialogAction("导入", preferred = true) { c.importPeers(p); peer = null }, DialogAction("取消") { peer = null }))
    }
}

@Composable private fun UpdateGroup(c: DesktopController) {
    val state by c.updater.state.collectAsState()
    Group("版本") {
        SettingRow("去他妈的鸡险 ${c.version}", subtitle = when (val u = state) {
            UpdateState.Idle -> "自动检查更新，每天一次"
            UpdateState.Checking -> "正在检查…"
            is UpdateState.Latest -> "已是最新版本 · ${ago(u.checkedAt)}检查"
            is UpdateState.Available -> "新版本 ${u.update.version} 可用"
            is UpdateState.Downloading -> "正在下载 ${u.update.version}" + (u.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "")
            is UpdateState.Installing -> "即将退出并安装…"
            is UpdateState.Failed -> u.message
        }, icon = Glyph.Download, iconTint = if (state is UpdateState.Available) colors.red else colors.accent) {
            when (val u = state) {
                is UpdateState.Available -> Button("下载并安装", { c.installUpdate(u.update) }, ButtonKind.PRIMARY)
                is UpdateState.Downloading -> Button("取消", c.updater::cancel)
                is UpdateState.Installing -> Spinner(colors.secondary)
                is UpdateState.Failed -> u.update?.let { retry -> Button("重试", { c.installUpdate(retry) }) } ?: Button("检查更新", c::checkForUpdates)
                else -> Button("检查更新", c::checkForUpdates, enabled = state != UpdateState.Checking, loading = state == UpdateState.Checking)
            }
        }
        (state as? UpdateState.Downloading)?.let { ProgressBar(it.fraction, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) }
        val notes = when (val u = state) { is UpdateState.Available -> u.update.release.notes; is UpdateState.Downloading -> u.update.release.notes; else -> null }
        notes?.let(Updates::summary)?.takeIf { it.isNotBlank() }?.let {
            Divider(48.dp)
            Text(it, style = Type.caption, color = colors.secondary, modifier = Modifier.padding(start = 48.dp, end = 14.dp, top = 8.dp, bottom = 10.dp))
        }
    }
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
