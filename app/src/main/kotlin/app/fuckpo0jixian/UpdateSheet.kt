package app.fuckpo0jixian

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.fuckpo0jixian.core.UpdateState
import app.fuckpo0jixian.core.Updates

/**
 * Offered when the app comes back to the foreground and a newer release exists: what changed, then 立即更新 or
 * 跳过此版本. Turning it down in any way skips that version for good; the settings page can still install it.
 */
@Composable fun UpdateSheet(updater: AppUpdater) {
    val offered by updater.offer.collectAsState()
    val update = offered ?: return
    val state by updater.state.collectAsState()
    val colors = Apple.colors
    // Android's own confirmation takes over from here.
    LaunchedEffect(state) { if (state is UpdateState.Installing) updater.closeOffer() }
    val downloading = state as? UpdateState.Downloading
    val failed = state as? UpdateState.Failed
    val waiting = downloading == null && failed == null
    fun dismiss() = if (waiting) updater.skip(update) else updater.closeOffer()
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    Dialog(::dismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(visible, enter = slideInVertically(spring<IntOffset>(dampingRatio = 0.86f, stiffness = 420f)) { it / 2 } + fadeIn()) {
                Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(colors.card)
                    .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 16.dp).testTag("update-sheet"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(62.dp).clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(colors.accent, colors.indigo))), contentAlignment = Alignment.Center) {
                        Icon(Glyphs.Download, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("发现新版本", style = Apple.title, color = colors.label)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(updater.version, style = Apple.footnote, color = colors.secondary)
                        Text("→", style = Apple.footnote, color = colors.tertiary)
                        Text(update.version, style = Apple.footnote.copy(fontWeight = FontWeight.SemiBold), color = colors.accent,
                            modifier = Modifier.clip(CircleShape).background(colors.accent.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp))
                    }
                    Updates.summary(update.release.notes).takeIf { it.isNotBlank() }?.let { notes ->
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(14.dp))
                            .background(colors.fill.copy(alpha = colors.fill.alpha * 0.6f)).verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            notes.lines().forEach { line ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(colors.accent))
                                    Text(line, style = Apple.footnote, color = colors.label, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    downloading?.let { d ->
                        LinearProgressIndicator(progress = { d.fraction ?: 0f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
                            color = colors.accent, trackColor = colors.fill, drawStopIndicator = {})
                        Spacer(Modifier.height(6.dp))
                        Text(d.fraction?.let { "正在下载并校验 ${(it * 100).toInt()}%" } ?: "正在下载…", style = Apple.footnote, color = colors.secondary)
                        Spacer(Modifier.height(14.dp))
                    }
                    failed?.let { f ->
                        Text(f.message, style = Apple.footnote, color = colors.orange, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                    }
                    when {
                        downloading != null -> PrimaryButton("正在下载", {}, enabled = false, loading = true)
                        failed != null -> PrimaryButton("重试", { updater.install(update) })
                        else -> PrimaryButton("立即更新", { updater.install(update) })
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).pressable(::dismiss, true),
                        contentAlignment = Alignment.Center) {
                        Text(when { downloading != null -> "在后台继续"; failed != null -> "关闭"; else -> "跳过此版本" },
                            style = Apple.body, color = colors.accent)
                    }
                    if (waiting) Text("跳过后不再提醒这个版本，仍可在「设置 → 关于」中更新。", style = Apple.caption.copy(fontWeight = FontWeight.Normal),
                        color = colors.tertiary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
