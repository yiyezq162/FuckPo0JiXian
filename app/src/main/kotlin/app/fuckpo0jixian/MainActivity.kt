package app.fuckpo0jixian

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import app.fuckpo0jixian.core.*
import app.fuckpo0jixian.core.State
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onResume() { super.onResume(); Recents.apply(this); (application as FuckPo0JiXianApp).controller.foreground() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val controller = (application as FuckPo0JiXianApp).controller
        setContent { FuckPo0JiXianTheme { FuckPo0JiXian(controller) {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } } }
    }
}

/** Optional: keep the task out of the recents screen so "clear all" there cannot kill the app. */
internal object Recents {
    private fun prefs(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    fun hidden(context: Context) = prefs(context).getBoolean("hide_recents", false)
    fun set(context: Context, value: Boolean) { prefs(context).edit().putBoolean("hide_recents", value).apply(); apply(context) }
    fun apply(context: Context) {
        val hide = hidden(context)
        context.getSystemService(ActivityManager::class.java).appTasks.forEach { runCatching { it.setExcludeFromRecents(hide) } }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault())
internal fun time(value: Long) = if (value == 0L) "暂无" else dateFormat.format(Instant.ofEpochMilli(value))

private class Tab(val title: String, val icon: ImageVector)
private val tabs = listOf(Tab("概览", Icons.Rounded.Home), Tab("白名单", Icons.Rounded.List),
    Tab("设置", Icons.Rounded.Settings), Tab("记录", Glyphs.History))

@Composable fun FuckPo0JiXian(c: Controller, requestNotifications: () -> Unit) {
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
                                LargeTitle(if (page == 0) "去他妈的鸡险" else tabs[page].title)
                                if (s.demo) Box(Modifier.padding(start = 4.dp)) { Capsule("演示数据 · 不连接平台", colors.orange) }
                            }
                        }
                        when (page) {
                            0 -> homeItems(s, busy, network, currentKey, credential, c, configure = { page = 2 }) { page = 1 }
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
            val update by c.updater.state.collectAsStateWithLifecycle()
            TabBar(page, badge = if (update is UpdateState.Available) 2 else -1) { page = it }
        }
    }
}

@Composable private fun TabBar(page: Int, badge: Int, select: (Int) -> Unit) {
    val c = Apple.colors
    Column(Modifier.fillMaxWidth().background(c.bar)) {
        HorizontalDivider(thickness = 0.5.dp, color = c.separator)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp).selectableGroup()) {
            tabs.forEachIndexed { i, tab ->
                val tint = if (page == i) c.accent else c.gray
                Column(Modifier.weight(1f).fillMaxHeight().testTag("tab-$i")
                    .selectable(page == i, remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { select(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box {
                        Icon(tab.icon, null, tint = tint, modifier = Modifier.size(26.dp))
                        if (badge == i) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-1).dp).size(9.dp).background(c.red, CircleShape))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(tab.title, style = Apple.caption, color = tint)
                }
            }
        }
    }
}

/** Big, calm status: what the app is doing, then the two facts people check (exit and network). */
@Composable private fun StatusHero(icon: ImageVector, tint: Color, title: String, subtitle: String, busy: Boolean,
                                   facts: List<Pair<String, String>>) {
    val colors = Apple.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.35f,
                        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "scale")
                    Box(Modifier.size(56.dp * pulse).background(tint.copy(alpha = 0.10f), CircleShape))
                }
                Box(Modifier.size(56.dp).background(tint.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    AnimatedContent(icon, label = "icon") { Icon(it, null, tint = tint, modifier = Modifier.size(30.dp)) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AnimatedContent(title, label = "title", transitionSpec = { fadeIn() togetherWith fadeOut() }) {
                    Text(it, style = Apple.largeTitle.copy(fontSize = Apple.title.fontSize * 1.25f, lineHeight = Apple.title.lineHeight * 1.2f), color = colors.label)
                }
                Text(subtitle, style = Apple.subhead, color = colors.secondary)
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            facts.forEach { (label, value) ->
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(colors.fill.copy(alpha = colors.fill.alpha * 0.7f))
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = Apple.footnote, color = colors.secondary)
                    Text(value, style = Apple.headline.copy(fontFeatureSettings = "tnum"), color = colors.label, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "刚刚", "5 分钟前", else the time. */
internal fun ago(value: Long): String {
    if (value <= 0) return "尚未检查"
    val minutes = (System.currentTimeMillis() - value) / 60_000
    return when { minutes < 1 -> "刚刚检查"; minutes < 60 -> "$minutes 分钟前检查"; minutes < 24 * 60 -> "${minutes / 60} 小时前检查"; else -> "${time(value)} 检查" }
}

private fun LazyListScope.homeItems(s: State, busy: Boolean, network: String, currentKey: String?,
                                    credential: Boolean, c: Controller, configure: () -> Unit, manage: () -> Unit) {
    item(key = "home-status") {
        val colors = Apple.colors
        val wifi by c.wifiObservation.collectAsState()
        val stale = !s.demo && s.snapshot != null && s.networkKey != currentKey
        val auto = s.mode == Mode.AUTO && s.layout != null
        val (icon, tint, title) = when {
            busy -> Triple(Glyphs.Sync, colors.accent, "正在检查")
            s.globalBlock != null || s.authBlocked -> Triple(Icons.Rounded.Warning, colors.red, "需要处理")
            s.paused -> Triple(Glyphs.Pause, colors.orange, "已暂停")
            auto -> Triple(Glyphs.Shield, colors.green, "自动同步中")
            else -> Triple(Icons.Rounded.Info, colors.accent, "仅查询")
        }
        val subtitle = when {
            busy -> "请稍候"
            stale -> "网络已切换，等待检查"
            s.lastCheck <= 0 -> statusText(s.status)
            else -> "${statusText(s.status)} · ${ago(s.lastCheck)}"
        }
        val identity = wifiIdentityText(s, wifi)
        Section {
            StatusHero(icon, tint, title, subtitle, busy, listOf(
                (if (stale) "上次出口" else "当前出口") to (s.snapshot?.current?.value ?: "未检查"),
                "网络" to (if (s.demo) "模拟网络" else listOfNotNull(networkText(network), identity).joinToString(" · "))))
        }
    }
    item(key = "home-actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("立即检查", c::check, Modifier.weight(1f), enabled = !busy && !s.paused, loading = busy)
            PrimaryButton(if (s.paused) "恢复检查" else "暂停检查", { c.pause(!s.paused) }, Modifier.width(128.dp), tinted = true)
        }
    }
    item(key = "home-capacity") {
        val snap = s.snapshot
        Section(header = "名额", footer = if (snap == null) "连接 Po0 并检查后显示。" else null) {
            if (snap == null) {
                ListRow("尚未获取", titleColor = Apple.colors.secondary)
                if (!credential && !s.demo) ActionRow("连接 Po0", onClick = configure)
            } else {
                CapacitySummary(s)
                ListRow("查看槽位", value = "${s.layout?.slots?.count { it.writer == Writer.LOCAL && it.authorized } ?: 0} 个由本机管理",
                    chevron = true, onClick = manage)
            }
        }
    }
}

private fun LazyListScope.recordItems(s: State, history: List<FamiliarNetwork>, c: Controller) {
    item(key = "rec-status") {
        Section(header = "检查") {
            ListRow("状态", value = statusText(s.status))
            ListRow("上次检查", value = time(s.lastCheck))
            // Only worth a row when something is actually holding checks back.
            if (s.failures > 0) ListRow("连续失败 ${s.failures} 次", value = "下次 ${time(s.nextAllowed)}", titleColor = Apple.colors.orange)
            if (s.authBlocked) ListRow("Token 无效，请在设置中更新", titleColor = Apple.colors.red)
        }
    }
    item(key = "rec-exit") {
        Section(header = "出口", footer = "国内出口经 ip.3322.net 查询，与 Po0 识别一致才会写入。") {
            val d = s.domesticExit
            val current = s.snapshot?.current
            ListRow("国内出口", subtitle = d?.let { "${time(it.time)} 查询" } ?: statusText(s.probeStatus), value = d?.ipv4 ?: "未查询")
            ListRow("Po0 识别", value = current?.value ?: "未检查", trailing = if (d != null && current != null) {
                { Capsule(if (d.cidr == current) "一致" else "不一致", if (d.cidr == current) Apple.colors.green else Apple.colors.orange) }
            } else null)
            ActionRow("查询国内出口", enabled = !s.demo && !s.paused, onClick = c::checkDomestic)
        }
    }
    item(key = "rec-history") {
        var all by rememberSaveable { mutableStateOf(false) }
        Section(header = "常用网络", footer = "近 7 天的使用统计，只作参考。", modifier = Modifier.animateContentSize()) {
            if (history.isEmpty()) ListRow("暂无记录", titleColor = Apple.colors.secondary)
            history.take(if (all) 20 else 3).forEach { n ->
                ListRow(n.cidr.value, subtitle = "${n.days} 天 · ${n.visits} 次 · ${time(n.lastSeen)}",
                    trailing = if (n.common) { { Capsule("常用", Apple.colors.green) } } else null)
            }
            if (history.size > 3) ActionRow(if (all) "收起" else "显示全部 ${minOf(history.size, 20)} 个") { all = !all }
        }
    }
    item(key = "rec-events") {
        var all by rememberSaveable { mutableStateOf(false) }
        val events = s.events.filter { it.time >= System.currentTimeMillis() - c.policy.retentionMs }.takeLast(30).reversed()
        Section(header = "事件", modifier = Modifier.animateContentSize()) {
            if (events.isEmpty()) ListRow("暂无事件", titleColor = Apple.colors.secondary)
            events.take(if (all) 30 else 3).forEach { e -> ListRow(statusText(e.code), value = time(e.time)) }
            if (events.size > 3) ActionRow(if (all) "收起" else "显示全部 ${events.size} 条") { all = !all }
        }
    }
    item(key = "rec-clear") {
        var confirm by remember { mutableStateOf(false) }
        Section(footer = "只反映 Po0 与国内查询站看到的出口，不代表所有线路都可用。") {
            ActionRow("清空记录", color = Apple.colors.red) { confirm = true }
        }
        if (confirm) IosAlert("清空记录？", { confirm = false }, message = "清空网络历史和事件，不影响设置与白名单。", actions = listOf(
            AlertAction("取消") { confirm = false },
            AlertAction("清空", destructive = true) { c.clearHistory(); confirm = false }))
    }
}
