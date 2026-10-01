package app.fuckpo0jixian

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fuckpo0jixian.core.*
import app.fuckpo0jixian.core.State
import kotlinx.coroutines.launch

/**
 * Records: how the last check stands, the exit as this phone and as Po0 see it (side by side, since writes need them
 * equal), where this phone has been, and what happened, as a timeline. Export and clearing close the page.
 */
internal fun LazyListScope.recordItems(s: State, history: List<FamiliarNetwork>, c: Controller) {
    item(key = "rec-status") { Reveal(0) { StatusStrip(s) } }
    item(key = "rec-exit") { Reveal(1) { ExitComparison(s, c) } }
    item(key = "rec-history") { Reveal(2) { Networks(history) } }
    item(key = "rec-events") { Reveal(3) { Timeline(s, c) } }
    item(key = "rec-actions") { RecordActions(c) }
}

@Composable private fun StatusStrip(s: State) {
    val colors = Apple.colors
    val tint = when {
        s.authBlocked || s.globalBlock != null -> colors.red
        s.failures > 0 || s.status in setOf("EGRESS_UNVERIFIED", "SLOT_CONFLICT", "PENDING_REVIEW") -> colors.orange
        s.lastCheck > 0 -> colors.green
        else -> colors.gray
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot(tint)
                Text(statusText(s.status), style = Apple.headline, color = colors.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Figure("上次检查", time(s.lastCheck), Modifier.weight(1f))
                Figure("连续失败", "${s.failures} 次", Modifier.weight(1f), if (s.failures > 0) colors.orange else colors.label)
                Figure("下次可请求", if (s.nextAllowed > System.currentTimeMillis()) time(s.nextAllowed) else "随时", Modifier.weight(1f))
            }
        }
        if (s.authBlocked) Callout("Token 无效", "请在设置中更换 Token", colors.red, Icons.Rounded.Warning)
    }
}

@Composable private fun Figure(label: String, value: String, modifier: Modifier, tint: Color = Apple.colors.label) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Apple.overline, color = Apple.colors.secondary, maxLines = 1)
        Text(value, style = Apple.subhead.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = tint, maxLines = 1)
    }
}

/** The two views of the exit, joined by whether they agree: a write needs both to name the same /24. */
@Composable private fun ExitComparison(s: State, c: Controller) {
    val colors = Apple.colors
    val d = s.domesticExit
    val current = s.snapshot?.current
    val (verdict, tint) = when {
        d == null || current == null -> "等待核对" to colors.gray
        d.cidr == current -> "一致" to colors.green
        else -> "不一致" to colors.orange
    }
    Panel(header = "出口核对", footer = "国内出口经 STUN 查询（走 Wi-Fi / 移动数据本身，不经过 VPN），不通时改用 ip.3322.net；与 Po0 识别一致才会写入。") {
        Reading("国内出口", d?.ipv4 ?: "未查询",
            d?.let { "${if (it.source == ProbeSource.STUN) "STUN" else "ip.3322.net"} · ${time(it.time)}" } ?: statusText(s.probeStatus))
        // The joint: a short rail with the verdict on it.
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(start = 5.dp).width(2.dp).height(28.dp).clip(CircleShape).background(tint.copy(alpha = 0.5f)))
            Pill(verdict, tint, if (tint == colors.green) Icons.Rounded.Check else null)
        }
        Reading("Po0 识别", current?.value ?: "未检查", "Po0 看到的出口 /24")
        Spacer(Modifier.height(18.dp))
        PrimaryButton("查询国内出口", c::checkDomestic, enabled = !s.demo && !s.paused, tinted = true)
    }
}

@Composable private fun Reading(label: String, value: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = Apple.overline, color = Apple.colors.secondary)
        Text(value, style = Apple.display.copy(fontSize = Apple.display.fontSize * 0.72f), color = Apple.colors.label, maxLines = 1)
        Text(detail, style = Apple.footnote, color = Apple.colors.secondary, maxLines = 1)
    }
}

/** Where this phone has been over the week, each exit with a bar of how many days it was seen. */
@Composable private fun Networks(history: List<FamiliarNetwork>) {
    var all by rememberSaveable { mutableStateOf(false) }
    val colors = Apple.colors
    Panel(Modifier.animateContentSize(), header = "常用网络", footer = "近 7 天的使用统计，只作参考。", padding = PaddingValues(vertical = 6.dp)) {
        if (history.isEmpty()) Text("暂无记录", style = Apple.body, color = colors.secondary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        history.take(if (all) 20 else 3).forEach { n ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(n.cidr.value, style = Apple.body.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"), color = colors.label,
                        modifier = Modifier.weight(1f))
                    if (n.common) Pill("常用", colors.green)
                }
                val share by animateFloatAsState((n.days / 7f).coerceIn(0.04f, 1f), tween(700), label = "days")
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(colors.fill)) {
                    Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(CircleShape).background(if (n.common) colors.green else colors.accent))
                }
                Text("${n.days} 天 · ${n.visits} 次 · 最近 ${time(n.lastSeen)}", style = Apple.footnote, color = colors.secondary)
            }
        }
        if (history.size > 3) ActionRow(if (all) "收起" else "显示全部 ${minOf(history.size, 20)} 个") { all = !all }
    }
}

/** Events as a timeline, newest first; five at first, the rest on request. */
@Composable private fun Timeline(s: State, c: Controller) {
    var all by rememberSaveable { mutableStateOf(false) }
    val colors = Apple.colors
    val events = s.events.filter { it.time >= System.currentTimeMillis() - c.policy.retentionMs }.takeLast(30).reversed()
    Panel(Modifier.animateContentSize(), header = "事件", padding = PaddingValues(top = 14.dp, bottom = 4.dp)) {
        if (events.isEmpty()) Text("暂无事件", style = Apple.body, color = colors.secondary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp).padding(bottom = 10.dp))
        val shown = events.take(if (all) 30 else 5)
        shown.forEachIndexed { i, e ->
            val tint = eventTint(e.code)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(10.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
                    if (i < shown.lastIndex) Box(Modifier.width(1.5.dp).weight(1f).background(colors.separator))
                }
                Row(Modifier.weight(1f).padding(bottom = 16.dp), verticalAlignment = Alignment.Top) {
                    Text(statusText(e.code), style = Apple.body, color = colors.label, modifier = Modifier.weight(1f))
                    Text(time(e.time), style = Apple.footnote.copy(fontFeatureSettings = "tnum"), color = colors.secondary)
                }
            }
        }
        if (events.size > 5) ActionRow(if (all) "收起" else "显示全部 ${events.size} 条") { all = !all }
    }
}

@Composable private fun eventTint(code: String): Color {
    val colors = Apple.colors
    return when {
        code in setOf("SLOT_CURRENT", "SLOT_UPDATED", "RECOVERED_VERIFIED", "PRESENT_CURRENT_CHECK", "LOCAL_UNCHANGED", "PEER_UPDATED") -> colors.green
        code.startsWith("HTTP_") || code.contains("FAILED") || code.contains("ERROR") ||
            code in setOf("SLOT_CONFLICT", "EGRESS_UNVERIFIED", "COVERED_OTHER_SLOT", "OBSERVED_MISSING", "PENDING_REVIEW") -> colors.orange
        else -> colors.gray
    }
}

/** Last on the page: the detailed log for troubleshooting (addresses cut to /16), and clearing the history. */
@Composable private fun RecordActions(c: Controller) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    fun deliver(share: Boolean) {
        open = false; working = true
        scope.launch {
            try {
                val text = c.debugText()
                if (share) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "去他妈的鸡险 · 调试信息"), "导出调试信息"))
                else {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FuckPo0JiXian", text))
                    c.feedback.value = "已复制调试信息"
                }
            } finally { working = false }
        }
    }
    Section(footer = "调试信息包含详细的运行记录，IP 只保留前两段，不含 Token。清空记录会一并清空日志，不影响设置、白名单和累计次数。") {
        ActionRow("导出调试信息", loading = working) { open = true }
        ActionRow("清空记录", color = Apple.colors.red) { confirm = true }
    }
    if (open) IosAlert("导出调试信息", { open = false },
        message = "包含设置、每次检查与网络变化、模块记录等运行细节。IP 只保留前两段，Wi-Fi 名称以标记代替，不含 Token。",
        actions = listOf(AlertAction("分享", preferred = true) { deliver(true) }, AlertAction("复制") { deliver(false) },
            AlertAction("取消") { open = false }))
    if (confirm) IosAlert("清空记录？", { confirm = false }, message = "清空网络历史和事件，同时会清空掉日志信息。不影响设置与白名单。", actions = listOf(
        AlertAction("取消") { confirm = false },
        AlertAction("清空", destructive = true) { c.clearHistory(); confirm = false }))
}
