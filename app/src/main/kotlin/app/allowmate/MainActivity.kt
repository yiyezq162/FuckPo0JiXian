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
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
    override fun onResume() { super.onResume(); (application as AllowMateApp).controller.foreground() }
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

private val dateFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault())
internal fun time(value: Long) = if (value == 0L) "暂无" else dateFormat.format(Instant.ofEpochMilli(value))

private class Tab(val title: String, val icon: ImageVector)
private val tabs = listOf(Tab("概览", Icons.Rounded.Home), Tab("白名单", Icons.Rounded.List),
    Tab("设置", Icons.Rounded.Settings), Tab("记录", Glyphs.History))

@Composable fun AllowMate(c: Controller, requestNotifications: () -> Unit) {
    val s by c.store.flow.collectAsStateWithLifecycle()
    val busy by c.busy.collectAsStateWithLifecycle()
    val feedback by c.feedback.collectAsStateWithLifecycle()
    val network by c.networkLabel.collectAsStateWithLifecycle()
    val currentKey by c.currentNetworkKey.collectAsStateWithLifecycle()
    val credential by c.credentialPresent.collectAsStateWithLifecycle()
    val history = remember(s.observations) { NetworkHistory.summarize(s.observations, System.currentTimeMillis()) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val scrolls = List(tabs.size) { rememberLazyListState() }
    val colors = Apple.colors
    val background = rememberBackgroundLocation()
    ManualDialog(c, busy)
    AnimatedContent(editing, Modifier.fillMaxSize().background(colors.background), label = "editor", transitionSpec = {
        if (targetState != null) (slideInVertically { it } + fadeIn()) togetherWith fadeOut()
        else fadeIn() togetherWith (slideOutVertically { it } + fadeOut())
    }) { slot ->
        if (slot != null) SlotEditor(s, s.layout?.slots?.find { it.number == slot } ?: ManagedSlot(slot), c, busy) { editing = null }
        else Column(Modifier.fillMaxSize()) {
            BackHandler(page != 0) { page = 0 }
            val scroll = scrolls[page]
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth().testTag("page-list"), state = scroll,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 12.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        item(key = "title-$page") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LargeTitle(if (page == 0) "白名单随行" else tabs[page].title)
                                if (s.demo) Box(Modifier.padding(start = 4.dp)) { Capsule("演示数据 · 不连接平台", colors.orange) }
                            }
                        }
                        when (page) {
                            0 -> homeItems(s, busy, network, currentKey, history, credential, c, configure = { page = 2 }) { page = 1 }
                            1 -> slotItems(s, c, busy, background, edit = { editing = it }) { page = 2 }
                            2 -> po0Items(s, c, credential, busy, requestNotifications)
                            3 -> recordItems(s, history, c)
                        }
                    }
                }
                val compact by remember(scroll) { derivedStateOf { scroll.firstVisibleItemIndex > 0 || scroll.firstVisibleItemScrollOffset > 60 } }
                Column(Modifier.fillMaxWidth().background(if (compact) colors.bar else colors.background)) {
                    Spacer(Modifier.height(top))
                    AnimatedVisibility(compact, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Column {
                            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                                Text(if (page == 0) "概览" else tabs[page].title, style = Apple.headline, color = colors.label)
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
                        }
                    }
                }
                Toast(feedback.takeIf { it != statusText(s.status) } ?: "", Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            }
            TabBar(page) { page = it }
        }
    }
}

@Composable private fun TabBar(page: Int, select: (Int) -> Unit) {
    val c = Apple.colors
    Column(Modifier.fillMaxWidth().background(c.bar)) {
        HorizontalDivider(thickness = 0.5.dp, color = c.separator)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp).selectableGroup()) {
            tabs.forEachIndexed { i, tab ->
                val tint = if (page == i) c.accent else c.gray
                Column(Modifier.weight(1f).fillMaxHeight().testTag("tab-$i")
                    .selectable(page == i, remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { select(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(tab.icon, null, tint = tint, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(tab.title, style = Apple.caption, color = tint)
                }
            }
        }
    }
}

@Composable private fun StatusHero(icon: ImageVector, tint: Color, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).background(tint.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Apple.title, color = Apple.colors.label)
            Text(subtitle, style = Apple.subhead, color = Apple.colors.secondary)
        }
    }
}

private fun LazyListScope.homeItems(s: State, busy: Boolean, network: String, currentKey: String?, history: List<FamiliarNetwork>,
                                    credential: Boolean, c: Controller, configure: () -> Unit, manage: () -> Unit) {
    item(key = "home-status") {
        val colors = Apple.colors
        val wifi by c.wifiObservation.collectAsState()
        val stale = !s.demo && s.snapshot != null && s.networkKey != currentKey
        val auto = s.mode == Mode.AUTO && s.layout != null
        Section {
            when {
                busy -> StatusHero(Glyphs.Sync, colors.accent, "正在检查", "请稍候")
                s.paused -> StatusHero(Glyphs.Pause, colors.orange, "已暂停", if (stale) "网络已切换" else statusText(s.status))
                else -> StatusHero(if (auto) Glyphs.Shield else Icons.Rounded.Info, if (auto) colors.green else colors.accent,
                    if (auto) "自动同步中" else "仅查询", if (stale) "网络已切换，等待检查" else statusText(s.status))
            }
            val identity = wifiIdentityText(s, wifi)
            ListRow("网络", value = if (s.demo) "模拟网络" else listOfNotNull(networkText(network), identity).joinToString(" · "))
            s.snapshot?.let { ListRow(if (stale) "上次出口" else "当前出口", value = it.current.value) }
            ListRow("上次检查", value = time(s.lastCheck))
        }
    }
    item(key = "home-actions") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("立即检查", c::check, enabled = !busy && !s.paused, loading = busy)
            PrimaryButton(if (s.paused) "恢复检查" else "暂停检查", { c.pause(!s.paused) }, tinted = true)
        }
    }
    item(key = "home-capacity") {
        val snap = s.snapshot
        Section(header = "名额", footer = if (snap == null) "连接 Po0 并检查后显示。" else null, inset = if (snap == null) 16.dp else 60.dp) {
            if (snap == null) {
                ListRow("尚未获取", titleColor = Apple.colors.secondary)
                if (!credential && !s.demo) ActionRow("连接 Po0", onClick = configure)
            } else {
                CapacitySummary(s)
                (0 until snap.capacity).forEach { number ->
                    val slot = s.layout?.slots?.find { it.number == number } ?: ManagedSlot(number)
                    ListRow(slot.name.ifBlank { purposeText(slot.purpose) }, value = snap.entries.find { it.slot == number }?.cidr?.value ?: "空",
                        leading = { NumberBadge("${number + 1}", purposeColor(slot.purpose)) }, chevron = true, onClick = manage)
                }
                snap.entries.filter { it.slot == null }.forEach {
                    ListRow(it.cidr.value, subtitle = "未分配槽号", leading = { NumberBadge("–", Apple.colors.gray) }, chevron = true, onClick = manage)
                }
            }
        }
    }
    if (history.isNotEmpty()) item(key = "home-familiar") {
        Section(header = "常用网络") {
            history.take(3).forEach { n -> ListRow(n.cidr.value, value = if (n.common) "常用" else "最近") }
        }
    }
}

private fun LazyListScope.recordItems(s: State, history: List<FamiliarNetwork>, c: Controller) {
    item(key = "rec-status") {
        Section(header = "检查") {
            ListRow("状态", value = statusText(s.status))
            ListRow("上次检查", value = time(s.lastCheck))
            ListRow("下次可请求", value = time(s.nextAllowed))
            ListRow("连续失败", value = "${s.failures} 次")
            if (s.authBlocked) ListRow("Token 无效，请在设置中更新", titleColor = Apple.colors.red)
        }
    }
    item(key = "rec-exit") {
        Section(header = "出口", footer = "国内出口经 ip.3322.net 查询，用于核对 Po0 识别的网段。") {
            val d = s.domesticExit
            ListRow("国内出口", value = d?.ipv4 ?: "未查询")
            d?.let { ListRow("所在网段", value = it.cidr.value); ListRow("查询时间", value = time(it.time)) }
            ListRow("Po0 识别", value = s.snapshot?.current?.value ?: "未检查")
            ListRow("查询状态", value = statusText(s.probeStatus))
            ActionRow("查询国内出口", enabled = !s.demo && !s.paused, onClick = c::checkDomestic)
        }
    }
    item(key = "rec-history") {
        var confirm by remember { mutableStateOf(false) }
        Section(header = "常用网络", footer = "根据近 7 天的使用情况统计，不会据此修改白名单。") {
            if (history.isEmpty()) ListRow("暂无记录", titleColor = Apple.colors.secondary)
            history.take(20).forEach { n ->
                ListRow(n.cidr.value, subtitle = "${if (n.common) "常用" else "最近"} · ${n.days} 天 · ${n.visits} 次", value = time(n.lastSeen))
            }
            ActionRow("清空记录", color = Apple.colors.red) { confirm = true }
        }
        if (confirm) IosAlert("清空记录？", { confirm = false }, message = "清空网络历史和事件，不影响设置与白名单。", actions = listOf(
            AlertAction("取消") { confirm = false },
            AlertAction("清空", destructive = true) { c.clearHistory(); confirm = false }))
    }
    item(key = "rec-events") {
        val events = s.events.filter { it.time >= System.currentTimeMillis() - c.policy.retentionMs }.takeLast(30).reversed()
        Section(header = "事件", footer = "仅反映 Po0 与国内查询站看到的出口，不代表所有线路都可用。") {
            if (events.isEmpty()) ListRow("暂无事件", titleColor = Apple.colors.secondary)
            events.forEach { e -> ListRow(statusText(e.code), value = time(e.time)) }
        }
    }
}
