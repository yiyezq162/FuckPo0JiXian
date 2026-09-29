package app.fuckpo0jixian.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fuckpo0jixian.core.UpdateState
import app.fuckpo0jixian.core.Updates

/**
 * Offered when the window comes into view and a newer release exists: what changed, then 立即更新 or 跳过此版本.
 * Turning it down in any way skips that version for good; 设置 → 版本 can still install it.
 */
@Composable fun UpdateDialog(c: DesktopController) {
    val offered by c.updater.offer.collectAsState()
    val update = offered ?: return
    val state by c.updater.state.collectAsState()
    val downloading = state as? UpdateState.Downloading
    val failed = state as? UpdateState.Failed
    val installing = state is UpdateState.Installing
    val waiting = downloading == null && failed == null && !installing
    fun dismiss() = if (waiting) c.updater.skip(update) else c.updater.closeOffer()
    val mac = theme.look == Look.MAC
    androidx.compose.ui.window.Dialog(onDismissRequest = ::dismiss) {
        val shape = RoundedCornerShape(if (mac) 14.dp else 8.dp)
        Column(Modifier.width(if (mac) 400.dp else 440.dp).shadow(28.dp, shape).clip(shape).background(if (mac) colors.card else colors.window)
            .border(0.5.dp, colors.cardBorder, shape)) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val icon = remember { androidx.compose.ui.graphics.painter.BitmapPainter(
                        androidx.compose.ui.res.useResource("icon.png") { androidx.compose.ui.res.loadImageBitmap(it) }) }
                    Image(icon, null, Modifier.size(58.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("发现新版本", style = Type.title, color = colors.label)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(c.version, style = Type.caption, color = colors.secondary)
                            Text("→", style = Type.caption, color = colors.tertiary)
                            Text(update.version, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), color = colors.accent,
                                modifier = Modifier.clip(CircleShape).background(colors.soft(colors.accent)).padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
                Updates.summary(update.release.notes).takeIf { it.isNotBlank() }?.let { notes ->
                    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(if (mac) 10.dp else 6.dp)).background(colors.fill)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        notes.lines().forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Box(Modifier.padding(top = 6.dp).size(5.dp).clip(CircleShape).background(colors.accent))
                                Text(line, style = Type.body, color = colors.label, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                when {
                    downloading != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProgressBar(downloading.fraction)
                        Text(downloading.fraction?.let { "正在下载并校验 ${(it * 100).toInt()}%" } ?: "正在下载…", style = Type.caption, color = colors.secondary)
                    }
                    installing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spinner(colors.secondary); Text("即将退出并安装，完成后自动重新打开", style = Type.caption, color = colors.secondary)
                    }
                    failed != null -> Text(failed.message, style = Type.caption, color = colors.orange)
                    else -> Text("跳过后不再提醒这个版本，仍可在「设置 → 版本」中更新。", style = Type.caption, color = colors.tertiary)
                }
            }
            val primary = when {
                downloading != null -> DialogAction("正在下载", preferred = true, enabled = false) {}
                installing -> DialogAction("正在安装", preferred = true, enabled = false) {}
                failed != null -> DialogAction("重试", preferred = true) { c.installUpdate(update) }
                else -> DialogAction("立即更新", preferred = true) { c.installUpdate(update) }
            }
            val secondary = when {
                downloading != null -> DialogAction("在后台继续") { c.updater.closeOffer() }
                installing -> null
                failed != null -> DialogAction("关闭") { c.updater.closeOffer() }
                else -> DialogAction("跳过此版本") { c.updater.skip(update) }
            }
            // macOS: buttons at the trailing edge, default rightmost. Windows: an even strip, primary first.
            if (mac) Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                secondary?.let { Button(it.text, it.onClick) }
                Button(primary.text, primary.onClick, ButtonKind.PRIMARY, enabled = primary.enabled, loading = !primary.enabled)
            } else Row(Modifier.fillMaxWidth().background(colors.card).padding(24.dp, 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(primary.text, primary.onClick, ButtonKind.PRIMARY, enabled = primary.enabled, loading = !primary.enabled, modifier = Modifier.weight(1f))
                secondary?.let { Button(it.text, it.onClick, modifier = Modifier.weight(1f)) }
            }
        }
    }
}
