package app.allowmate

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.allowmate.core.*
import app.allowmate.core.State
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val controller = (application as AllowMateApp).controller
        setContent { AllowMateTheme { AllowMate(controller) {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } } }
    }
}

@Composable fun AllowMateTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF8ED5C5), onPrimary = Color(0xFF00382F), secondary = Color(0xFFBBCBC4),
        background = Color(0xFF101512), surface = Color(0xFF101512), surfaceContainerLow = Color(0xFF1A211D),
        surfaceContainer = Color(0xFF1E2923), secondaryContainer = Color(0xFF334D42), onSecondaryContainer = Color(0xFFD6EADF),
        onSurface = Color(0xFFE1E9E3), onSurfaceVariant = Color(0xFFC0CEC5), outline = Color(0xFF87968C))
    else lightColorScheme(
        primary = Color(0xFF145E55), onPrimary = Color.White, secondary = Color(0xFF4A635C),
        background = Color(0xFFF6F8F5), surface = Color(0xFFF6F8F5), surfaceContainerLow = Color.White,
        surfaceContainer = Color(0xFFEDF3EE), secondaryContainer = Color(0xFFD7EADF), onSecondaryContainer = Color(0xFF173B2C),
        onSurface = Color(0xFF19231D), onSurfaceVariant = Color(0xFF46564B), outline = Color(0xFF718278))
    MaterialTheme(colorScheme = colors, content = content)
}
private val dateFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault())
internal fun time(value: Long) = if (value == 0L) "尚无记录" else dateFormat.format(Instant.ofEpochMilli(value))

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AllowMate(c: Controller, requestNotifications: () -> Unit) {
    val s by c.store.flow.collectAsStateWithLifecycle()
    val busy by c.busy.collectAsStateWithLifecycle()
    val feedback by c.feedback.collectAsStateWithLifecycle()
    val network by c.networkLabel.collectAsStateWithLifecycle()
    val currentKey by c.currentNetworkKey.collectAsStateWithLifecycle()
    val credential by c.credentialPresent.collectAsStateWithLifecycle()
    val history = remember(s.observations) { NetworkHistory.summarize(s.observations, System.currentTimeMillis()) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val homeScroll = rememberLazyListState()
    val listScroll = rememberLazyListState()
    val configScroll = rememberLazyListState()
    val diagnosticScroll = rememberLazyListState()
    val scroll = when (page) { 0 -> homeScroll; 1 -> listScroll; 2 -> configScroll; else -> diagnosticScroll }
    BackHandler(page != 0) { page = 0 }
    val tabs = listOf("首页", "白名单", "Po0", "诊断")
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.List, Icons.Outlined.Settings, Icons.Outlined.Info)
    Scaffold(topBar = { TopAppBar(title = { Column {
        Text("白名单随行", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (s.demo) "演示模式 · 不连接真实平台" else "Po0 白名单助手", style = MaterialTheme.typography.labelMedium)
    } }) }, bottomBar = {
        NavigationBar { tabs.forEachIndexed { i, title -> NavigationBarItem(selected = page == i, onClick = { page = i },
            icon = { Icon(icons[i], contentDescription = null) }, label = { Text(title) }) } }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth().testTag("page-list"), state = scroll,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (s.demo) item(key = "demo") { Text("演示数据 · 仅本机模拟", color = MaterialTheme.colorScheme.primary) }
                if (feedback.isNotEmpty() && feedback != statusText(s.status)) item(key = "feedback") { Text(feedback, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                when (page) {
                    0 -> homeItems(s, busy, network, currentKey, history, c, configure = { page = 2 }) { page = 1 }
                    1 -> if (s.demo && s.slotPlan == null) item(key = "profiles") { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { Profiles(s, c) } } else whitelistItems(s) { page = 2 }
                    2 -> po0Items(s, c, credential, busy, requestNotifications)
                    3 -> diagnosticItems(s, history, c)
                }
                item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
@Composable internal fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
@Composable private fun Notice(title: String, body: String) = Panel(title) { Text(body, style = MaterialTheme.typography.bodyMedium) }
private fun LazyListScope.homeItems(s: State, busy: Boolean, network: String, currentKey: String?, history: List<FamiliarNetwork>, c: Controller, configure: () -> Unit, manage: () -> Unit) {
    item(key = "home-status") { Panel(when { busy -> "正在检查"; s.paused -> "已暂停"; s.mode == Mode.AUTO && (s.demo || s.slotPlan != null) -> "自动同步已开启"; else -> "仅查询 · 不更新" }) {
        Text(if (s.demo) "模拟网络" else network, style = MaterialTheme.typography.titleLarge)
        val stale = !s.demo && s.snapshot != null && s.networkKey != currentKey
        Text(if (stale) "网络已切换，等待检查" else statusText(s.status), style = MaterialTheme.typography.titleMedium)
        s.snapshot?.let { Text("${if (stale) "上次网段" else "当前网段"}：${it.current.value}", style = MaterialTheme.typography.bodyMedium) }
        Text("检查时间：${time(s.lastCheck)}", style = MaterialTheme.typography.bodySmall)
        Button(onClick = c::check, enabled = !busy && !s.paused, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text("立即检查")
        }
        OutlinedButton(onClick = { c.pause(!s.paused) }, modifier = Modifier.fillMaxWidth()) { Text(if (s.paused) "恢复检查" else "暂停检查") }
        Text(if (s.mode == Mode.AUTO && s.slotPlan != null) "离家后更新手机网段；回家后保留手机记录。" else "检查 Po0 是否已收录当前网络。", style = MaterialTheme.typography.bodySmall)
    } }
    item(key = "home-capacity") { Panel("名额") {
        val snap = s.snapshot
        if (snap == null) Text("容量待查询，不预占名额。") else {
            Text("已用 ${snap.entries.size} / ${snap.capacity} · 剩余 ${snap.remaining}", style = MaterialTheme.typography.titleLarge)
            Text(if (s.slotPlan != null) "家宽固定保护 · 手机专用 1 个槽位" else "已有条目默认保护。本机预算：固定 ${s.budget.fixed} · 流动 ${s.budget.mobile}")
        }
        TextButton(onClick = manage) { Text("查看白名单") }
    } }
    if (history.isNotEmpty()) item(key = "home-familiar") { Panel("常用与最近") {
        history.take(3).forEach { n -> Text("${n.cidr.value} · ${if (n.common) "常用" else "最近出现"}") }
        Text("经常使用的网络会在这里优先显示。", style = MaterialTheme.typography.bodySmall)
    } }
    item(key = "home-boundary") { if (s.slotPlan == null && !s.demo) OutlinedButton(onClick = configure, modifier = Modifier.fillMaxWidth()) { Text("配置 Po0 自动同步") }
        else s.slotPlan?.let { Text("家宽 ${it.home.value} · ${if (it.homeReady) "已固定保护" else "等待初始化"}", style = MaterialTheme.typography.bodySmall) } }
}

private fun LazyListScope.whitelistItems(s: State, configure: () -> Unit) {
    item(key = "whitelist-summary") { Panel("Po0 白名单") {
        Text(s.snapshot?.let { "已收录 ${it.entries.size} 个网段，还可用 ${it.remaining} 个名额" } ?: "尚未获取白名单")
        Text("家宽固定保留，手机随网络变化更新。其他已有网段不动。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = configure) { Text("前往 Po0 配置") }
    } }
    val p = s.slotPlan
    items(s.snapshot?.entries.orEmpty(), key = { "entry-${it.cidr.value}" }, contentType = { "whitelist-entry" }) { e ->
        val home = p != null && e.cidr == p.home
        val mobile = p != null && e.slot == p.mobileSlot
        Panel(when { home -> "家宽 · 固定保护"; mobile -> "手机 · 自动更新"; else -> "其他已有网段" }) {
            Text(e.cidr.value, style = MaterialTheme.typography.titleLarge)
            Text(when { home -> "手机切网不会替换这一条"; mobile -> "下一次使用新网段时更新这一条"; else -> "由原有设备维护，本应用不修改" }, style = MaterialTheme.typography.bodySmall)
            Text(if (e.slot == null) "普通条目" else "Po0 槽位 ${e.slot}", style = MaterialTheme.typography.labelMedium)
        }
    }
    if (s.snapshot?.entries.isNullOrEmpty()) item(key = "whitelist-empty") { Text("先在 Po0 页面保存 token，再点击“检查连接”。") }
}

@Composable private fun Profiles(s: State, c: Controller) {
    s.slotPlan?.let { p ->
        Panel("专用名额") {
            Text("家宽 ${p.home.value} · 槽位 ${p.homeSlot} · ${if (p.homeReady) "固定保护" else "等待初始化"}")
            Text("手机 · 槽位 ${p.mobileSlot} · 切网时更新")
            Text("其他条目保留。手机槽位只供本机使用，发现冲突会暂停。")
        }
        Panel("平台最近记录") {
            s.snapshot?.entries?.forEach { e ->
                Text("${e.cidr.value} · ${when (e.slot) {
                    p.homeSlot -> "家宽固定保护"
                    p.mobileSlot -> "手机专用"
                    null -> "其他已有网段"
                    else -> "其他固定槽位 ${e.slot}"
                }}")
            } ?: Text("尚未检查")
        }
        return
    }
    var fixed by remember(s.budget.fixed) { mutableStateOf(s.budget.fixed.toString()) }
    var mobile by remember(s.budget.mobile) { mutableStateOf(s.budget.mobile.toString()) }
    var name by remember { mutableStateOf("") }
    var fixedKind by remember { mutableStateOf(true) }
    var manageProfiles by remember { mutableStateOf(false) }
    var manageEntries by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    confirm?.let { pair -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认本机管理操作") }, text = { Text(pair.first) },
        confirmButton = { TextButton(onClick = { pair.second(); confirm = null }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
    Panel("自由分配名额") {
        Text("先保留平台已有保护项，再分配本机预算。减少预算不会删除平台条目。")
        OutlinedTextField(fixed, { fixed = it.filter(Char::isDigit).take(4) }, label = { Text("相对固定预算") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(mobile, { mobile = it.filter(Char::isDigit).take(4) }, label = { Text("流动缓存预算") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { c.budget(fixed.toIntOrNull() ?: 0, mobile.toIntOrNull() ?: 0) }, enabled = s.snapshot != null) { Text("保存预算") }
        s.snapshot?.let { if (!Allocation.budgetFits(s, it)) Text("当前预算超出可用容量，请调整。", color = MaterialTheme.colorScheme.error) }
    }
    Panel("逻辑档位") {
        Text("同一 /24 可关联多个档位，只占一个平台条目。")
        Text("当前选择：${s.profiles.find { it.id == s.activeProfileId }?.name ?: "未指定，按流动预算检查"}")
        TextButton(onClick = { manageProfiles = !manageProfiles }) { Text(if (manageProfiles) "收起档位管理" else "管理档位") }
        if (manageProfiles) {
        if (s.activeProfileId != null) TextButton(onClick = { c.selectProfile(null) }) { Text("取消当前档位选择") }
        s.profiles.forEach { p ->
            HorizontalDivider()
            Text("${p.name} · ${if (p.kind == Kind.FIXED) "相对固定" else "流动"}", fontWeight = FontWeight.SemiBold)
            Text(p.cidr?.value ?: "尚未关联")
            OutlinedButton(onClick = { c.selectProfile(p.id) }, enabled = s.activeProfileId != p.id) { Text(if (s.activeProfileId == p.id) "已用于当前网络" else "用于当前网络") }
            Text("网络关联：${if (p.networkHint == null) "未设置" else "检查时网络实例（非身份凭据）"}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                confirm = "将最近检查的 /24 关联到此档位。固定项漂移需此次确认；只改本地关联，不替换平台条目。" to { c.associate(p.id, true) }
            }, enabled = s.snapshot?.let { it.contains(it.current) } == true) { Text("关联最近观察条目") }
            TextButton(onClick = { c.removeProfile(p.id) }) { Text("移除本地档位（不删平台条目）") }
        }
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("新档位名称") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(fixedKind, { fixedKind = it }); Spacer(Modifier.width(8.dp)); Text(if (fixedKind) "相对固定" else "流动缓存")
        }
        OutlinedButton(onClick = { c.addProfile(name, if (fixedKind) Kind.FIXED else Kind.MOBILE); name = "" }, enabled = name.isNotBlank()) { Text("添加本地档位") }
        }
    }
    Panel("平台实际条目 · 最近快照") {
        if (s.snapshot == null) Text("未查询")
        if (s.snapshot != null) TextButton(onClick = { manageEntries = !manageEntries }) { Text(if (manageEntries) "收起保护管理" else "管理保护与认领") }
        s.snapshot?.entries?.forEach { e ->
            val own = s.ownership.find { it.cidr == e.cidr }
            Text(e.cidr.value, fontWeight = FontWeight.SemiBold)
            Text("${if (Allocation.protected(s, e.cidr)) "受保护" else "本机管理"} · 引用 ${s.profiles.count { it.cidr == e.cidr }} · 平台槽位 ${e.slot ?: "无"}")
            if (manageEntries && own?.authorized != true) TextButton(onClick = {
                confirm = "认领仅表示授权本机管理；不能证明服务端独占。此操作不会解除保护，也不会删除或替换。" to { c.claim(e.cidr) }
            }) { Text("明确认领") }
            else if (manageEntries && own != null) TextButton(onClick = {
                confirm = "${if (own.protected) "解除" else "启用"}本地保护。固定档位关联条目不能解除；当前版本仍不支持安全替换。" to { c.protection(e.cidr, !own.protected) }
            }) { Text(if (own.protected) "解除本地保护" else "启用本地保护") }
            HorizontalDivider()
        }
        Text(if (s.slotPlan != null) "专用槽位：家宽固定保留，手机槽位随出口更新；不自动淘汰其他条目。" else "未分配专用槽位；本地认领不会授权服务端替换。")
    }
}

private fun LazyListScope.diagnosticItems(s: State, history: List<FamiliarNetwork>, c: Controller) {
    item(key = "diag-status") { Panel("检查状态") {
        Text(statusText(s.status), style = MaterialTheme.typography.titleMedium)
        Text("最近检查：${time(s.lastCheck)}\n下次允许请求：${time(s.nextAllowed)}\n连续失败：${s.failures}", style = MaterialTheme.typography.bodySmall)
        if (s.authBlocked) Text("鉴权失败，请在 Po0 页面更新 token。", color = MaterialTheme.colorScheme.error)
    } }
    item(key = "diag-exit") { Panel("出口核对") {
        s.domesticExit?.let { Text("${it.ipv4}\n${it.cidr.value}\n${time(it.time)}\n${it.source.url}") } ?: Text("尚无国内出口观察")
        Text("Po0 最近识别：${s.snapshot?.current?.value ?: "尚未检查"}", style = MaterialTheme.typography.bodySmall)
        Text(statusText(s.probeStatus), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = c::checkDomestic, enabled = !s.demo && !s.paused) { Text("仅检查国内出口") }
    } }
    item(key = "diag-history") { Panel("最近使用的网络") {
        Text("按近 7 天的跨天使用与独立返回次数推荐常用网络；不会据此改动白名单。", style = MaterialTheme.typography.bodySmall)
        if (history.isEmpty()) Text("暂无观察数据")
        ClearHistoryButton(c)
    } }
    items(history.take(20), key = { "history-${it.cidr.value}" }) { n -> Panel(n.cidr.value) {
        Text("${if (n.common) "常用" else "最近出现"} · ${n.days} 天 · ${n.visits} 次独立访问")
        Text("最近：${time(n.lastSeen)}", style = MaterialTheme.typography.bodySmall)
    } }
    item(key = "diag-events-title") { Text("脱敏事件", style = MaterialTheme.typography.titleMedium) }
    val events = s.events.filter { it.time >= System.currentTimeMillis() - c.policy.retentionMs }.takeLast(30).reversed()
    if (events.isEmpty()) item(key = "diag-empty") { Text("暂无事件") }
    items(events.withIndex().toList(), key = { "event-${it.index}-${it.value.time}" }) { (_, e) ->
        Text("${time(e.time)}  ${statusText(e.code)}", style = MaterialTheme.typography.bodyMedium)
    }
    item(key = "diag-boundary") { Notice("验证范围", "这里记录 Po0 白名单和国内查询站看到的出口，不代表所有业务线路已连通。Android 可能延后后台检查；手机槽位请勿与其他设备共用。") }
}

@Composable private fun ClearHistoryButton(c: Controller) {
    var confirm by remember { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }) { Text("清空本机记录") }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清空本机记录？") },
        text = { Text("清空网络历史和事件，保留 token、槽位配置和平台白名单。") },
        confirmButton = { TextButton(onClick = { c.clearHistory(); confirm = false }) { Text("清空记录") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
