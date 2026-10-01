package app.fuckpo0jixian

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fuckpo0jixian.core.*
import app.fuckpo0jixian.core.State
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The overview: one calm hero that says how things stand and shows the exit as the big fact, the two actions, then
 * what this phone (and its module) has actually done over its lifetime, then the quota.
 */
internal fun LazyListScope.homeItems(s: State, busy: Boolean, network: String, currentKey: String?,
                                     credential: Boolean, moduleDown: Boolean, c: Controller, configure: () -> Unit, manage: () -> Unit) {
    item(key = "home-status") { Reveal(0) { Hero(s, busy, network, currentKey, c) } }
    item(key = "home-actions") {
        Reveal(1) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("立即检查", c::check, Modifier.weight(1f), enabled = !busy && !s.paused, loading = busy, icon = Glyphs.Sync)
                PrimaryButton(if (s.paused) "恢复检查" else "暂停检查", { c.pause(!s.paused) }, Modifier.width(132.dp), tinted = true)
            }
        }
    }
    if (s.globalBlock != null) item(key = "protection-review") {
        Section(footer = "只读取 Po0，不重发上次写入。核对通过后仍暂停；其他设备槽的更新可通过，未知记录变化或丢失仍受保护。账户或文件恢复异常请先处理原因，勿清数据。") {
            ActionRow("只读复核保护状态", enabled = !busy, onClick = c::reviewProtection)
        }
    }
    if (moduleDown) item(key = "home-module") { Reveal(2) { ModuleDown(configure) } }
    item(key = "home-tally") { Reveal(2) { TallySection(s, c) } }
    item(key = "home-capacity") {
        Reveal(3) {
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
}

private data class Mood(val icon: ImageVector, val tint: Color, val title: String)

@Composable private fun Hero(s: State, busy: Boolean, network: String, currentKey: String?, c: Controller) {
    val colors = Apple.colors
    val wifi by c.wifiObservation.collectAsState()
    val stale = !s.demo && s.snapshot != null && s.networkKey != currentKey
    val auto = s.mode == Mode.AUTO && s.layout != null
    val mood = when {
        busy -> Mood(Glyphs.Sync, colors.accent, "正在检查")
        s.globalBlock != null || s.authBlocked -> Mood(Icons.Rounded.Warning, colors.red, "需要处理")
        s.paused -> Mood(Glyphs.Pause, colors.orange, "已暂停")
        auto -> Mood(Glyphs.Shield, colors.green, "自动同步中")
        else -> Mood(Icons.Rounded.Info, colors.accent, "仅查询")
    }
    val subtitle = when {
        busy -> "请稍候"
        stale -> "网络已切换，等待检查"
        else -> statusText(s.status)
    }
    val tint by animateColorAsState(mood.tint, tween(500), label = "mood")
    val identity = wifiIdentityText(s, wifi)
    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.card)
        // A soft light of the current mood from the top corner; the card itself stays calm and white.
        .drawBehind {
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = if (colors.dark) 0.22f else 0.13f), Color.Transparent),
                center = Offset(size.width * 0.92f, 0f), radius = size.width * 0.85f), radius = size.width * 0.85f,
                center = Offset(size.width * 0.92f, 0f))
        }
        .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 20.dp).testTag("home-hero")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalOrb(mood.icon, tint, busy)
            Spacer(Modifier.weight(1f))
            Ticker(s.lastCheck, busy)
        }
        Spacer(Modifier.height(18.dp))
        AnimatedContent(mood.title, label = "title", transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith (fadeOut(tween(160)) + slideOutVertically { -it / 3 })
        }) { Text(it, style = Apple.largeTitle.copy(fontSize = Apple.largeTitle.fontSize * 0.9f), color = colors.label,
            modifier = Modifier.semantics { heading() }) }
        Text(subtitle, style = Apple.subhead, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.separator))
        Spacer(Modifier.height(16.dp))
        Text(if (stale) "上次出口" else "当前出口", style = Apple.overline, color = colors.secondary)
        Spacer(Modifier.height(2.dp))
        AnimatedContent(s.snapshot?.current?.value ?: "未检查", label = "exit", transitionSpec = {
            (fadeIn(tween(260)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(160))
        }) { Text(it, style = Apple.display, color = if (s.snapshot == null) colors.tertiary else colors.label, maxLines = 1) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val kind = if (s.demo) "模拟网络" else networkText(network)
            Chip(if (kind == "移动数据") Glyphs.Cellular else Glyphs.Wifi, kind)
            identity?.let { Chip(null, it) }
            if (s.runtimeMode == RuntimeMode.MODULE) Chip(Glyphs.Module, "模块增强")
        }
    }
}

/** The status glyph in a soft disc; while a check runs, rings breathe out of it. */
@Composable private fun SignalOrb(icon: ImageVector, tint: Color, busy: Boolean) {
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        if (busy) {
            val wave = rememberInfiniteTransition(label = "wave")
            repeat(2) { i ->
                val t by wave.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearOutSlowInEasing),
                    initialStartOffset = StartOffset(800 * i)), label = "ring$i")
                Box(Modifier.size(52.dp).graphicsLayer { scaleX = 1f + t * 0.7f; scaleY = 1f + t * 0.7f; alpha = (1f - t) * 0.5f }
                    .border(1.5.dp, tint, CircleShape))
            }
        }
        Box(Modifier.size(52.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            AnimatedContent(icon, label = "icon", transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith fadeOut() }) {
                val spin = if (busy) rememberInfiniteTransition(label = "spin").animateFloat(0f, -360f,
                    infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "deg").value else 0f
                Icon(it, null, tint = tint, modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = spin })
            }
        }
    }
}

/** "3 分钟前", refreshed every half minute so the hero never shows a stale age. */
@Composable private fun Ticker(lastCheck: Long, busy: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastCheck) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) } }
    // Nothing to age yet: the subtitle already says so.
    if (lastCheck <= 0 && !busy) return
    val text = when {
        busy -> "检查中"
        else -> ((now - lastCheck) / 60_000).let { m -> when { m < 1 -> "刚刚"; m < 60 -> "$m 分钟前"; m < 1440 -> "${m / 60} 小时前"; else -> time(lastCheck) } }
    }
    Text(text, style = Apple.footnote.copy(fontWeight = FontWeight.Medium), color = Apple.colors.secondary,
        modifier = Modifier.clip(CircleShape).background(Apple.colors.fill).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable private fun Chip(icon: ImageVector?, text: String) {
    val colors = Apple.colors
    Row(Modifier.clip(CircleShape).background(colors.fill).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        icon?.let { Icon(it, null, tint = colors.secondary, modifier = Modifier.size(14.dp)) }
        Text(text, style = Apple.footnote.copy(fontWeight = FontWeight.Medium), color = colors.label, maxLines = 1)
    }
}

private val sinceFormat = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.CHINA).withZone(ZoneId.systemDefault())

/** Lifetime counts: did this app (and the module) actually do anything? */
@Composable private fun TallySection(s: State, c: Controller) {
    val tally by c.tally.flow.collectAsState()
    val module by c.runtime.module.collectAsState()
    val colors = Apple.colors
    val stats = module?.stats?.takeIf { s.runtimeMode == RuntimeMode.MODULE }
    Section(header = "累计", footer = (if (tally.since > 0) "自 ${sinceFormat.format(Instant.ofEpochMilli(tally.since))}起" else "从现在起") +
        "统计，只有卸载应用才会清零。") {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 18.dp)) {
            Stat("更新 IP", tally.ipUpdates, colors.yolk, Modifier.weight(1.2f), hint = tally.lastUpdate.takeIf { it > 0 }?.let { "上次 ${time(it)}" })
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.separator))
            Stat("完成检查", tally.checks, colors.label, Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.separator))
            Stat("网络切换", tally.networkChanges, colors.label, Modifier.weight(1f))
        }
        stats?.let { m ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Glyphs.Module, colors.indigo)
                Column(Modifier.weight(1f)) {
                    Text("模块：唤醒应用 ${compactCount(m.wakes)} 次", style = Apple.body, color = colors.label)
                    Text("其中拉起已结束的应用 ${compactCount(m.revived)} 次 · 因此更新 IP ${compactCount(m.ipUpdates)} 次",
                        style = Apple.footnote, color = colors.secondary)
                }
            }
        }
    }
}

@Composable private fun Stat(label: String, value: Long, tint: Color, modifier: Modifier, hint: String? = null) {
    Column(modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Apple.overline, color = Apple.colors.secondary, maxLines = 1)
        CountUp(value, Apple.display.copy(fontSize = Apple.display.fontSize * 0.86f), tint, format = ::compactCount)
        hint?.let { Text(it, style = Apple.caption, color = Apple.colors.tertiary, maxLines = 1) }
    }
}

/** In module mode, the one thing worth saying on the overview is that the module is not actually running. */
@Composable private fun ModuleDown(open: () -> Unit) {
    val colors = Apple.colors
    val source = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().springPress(source).clip(RoundedCornerShape(Apple.radius))
        .background(colors.orange.copy(alpha = if (colors.dark) 0.18f else 0.11f))
        .clickable(source, indication = null, role = Role.Button, onClick = open).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Warning, null, tint = colors.orange, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("模块没有在运行", style = Apple.headline, color = colors.label)
            Text("本次开机没连上模块，正按标准模式检查。请重启手机，或在 Magisk / KernelSU 里看模块描述。",
                style = Apple.footnote, color = colors.secondary)
        }
        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(22.dp))
    }
}
