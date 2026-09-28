package app.fuckpo0jixian

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.fuckpo0jixian.core.Updates

/**
 * Offered when people pick 模块增强 without a current module: a calm card that rises from the bottom, says plainly
 * that it is for rooted phones only, and installs the matching module through the root manager on 安装.
 */
@Composable fun ModuleSheet(c: Controller, update: Boolean, onDismiss: () -> Unit, onInstall: () -> Unit) {
    val colors = Apple.colors
    val context = LocalContext.current
    val state by c.moduleInstaller.state.collectAsState()
    val busy = state is ModuleInstallState.Downloading
    val done = state is ModuleInstallState.Opened || state is ModuleInstallState.Saved
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    fun close() { if (busy) c.moduleInstaller.cancel(); onDismiss() }
    Dialog(::close, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(visible, enter = slideInVertically(spring<IntOffset>(dampingRatio = 0.86f, stiffness = 420f)) { it / 2 } + fadeIn()) {
                Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(colors.card)
                    .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 16.dp).testTag("module-sheet"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(colors.accent.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center) {
                        Icon(Glyphs.Module, null, tint = colors.accent, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(if (update) "更新增强模块" else "安装增强模块", style = Apple.title, color = colors.label, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(if (update) "已安装的模块比应用旧，建议更新到配套版本。\n旧模块仍可继续使用。"
                        else "模块仅适用于已 root 的设备（Magisk / KernelSU）。\n如果你不知道这是什么，点「取消」即可。",
                        style = Apple.subhead, color = colors.secondary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.fill.copy(alpha = colors.fill.alpha * 0.6f))
                        .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Point(Glyphs.Sync, colors.green, "网络或路由器出口一变，就在后台唤起检查")
                        Point(Icons.Rounded.Lock, colors.indigo, "不保存 Token，也不访问 Po0")
                        Point(Glyphs.Download, colors.orange, "下载与本版本配套的模块，交给管理器安装，重启后生效")
                    }
                    Spacer(Modifier.height(16.dp))
                    when (val s = state) {
                        is ModuleInstallState.Downloading -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(progress = { s.fraction ?: 0f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
                                color = colors.accent, trackColor = colors.fill, drawStopIndicator = {})
                            Text(s.fraction?.let { "正在下载并校验 ${(it * 100).toInt()}%" } ?: "正在查找模块…", style = Apple.footnote, color = colors.secondary)
                        }
                        is ModuleInstallState.Opened -> Outcome(colors.green, "已在 ${s.manager} 中打开，确认安装后重启手机。")
                        is ModuleInstallState.Saved -> Outcome(colors.green, "已保存到「${s.location}」。" +
                            (s.manager?.let { "已打开 $it，" } ?: "请打开 Magisk / KernelSU，") + "在「模块」中选择从本地安装 ${s.file}，然后重启。")
                        is ModuleInstallState.Failed -> Outcome(colors.orange, s.message, icon = null)
                        ModuleInstallState.Idle -> {}
                    }
                    if (state !is ModuleInstallState.Idle) Spacer(Modifier.height(16.dp))
                    when {
                        done -> PrimaryButton("完成", onDismiss)
                        state is ModuleInstallState.Failed -> PrimaryButton("重试", onInstall)
                        else -> PrimaryButton(if (busy) "正在下载" else if (update) "更新" else "安装", onInstall, enabled = !busy, loading = busy)
                    }
                    if (!done) {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).pressable(::close, true),
                            contentAlignment = Alignment.Center) { Text("取消", style = Apple.body, color = colors.accent) }
                    }
                    if (state is ModuleInstallState.Failed) Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(14.dp)).pressable({
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updates.PAGE))) }
                    }, true), contentAlignment = Alignment.Center) { Text("前往 GitHub 下载", style = Apple.footnote, color = colors.secondary) }
                }
            }
        }
    }
}

@Composable private fun Point(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Text(text, style = Apple.footnote, color = Apple.colors.label, modifier = Modifier.weight(1f))
    }
}

@Composable private fun Outcome(tint: Color, text: String, icon: ImageVector? = Icons.Rounded.CheckCircle) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(18.dp)) }
        Text(text, style = Apple.footnote, color = if (icon == null) tint else Apple.colors.label, modifier = Modifier.weight(1f))
    }
}
