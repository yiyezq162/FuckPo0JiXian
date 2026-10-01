package app.fuckpo0jixian

import android.Manifest
import android.app.Activity
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fuckpo0jixian.core.*
import app.fuckpo0jixian.core.State
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onResume() { super.onResume(); Recents.apply(this); (application as FuckPo0JiXianApp).controller.foreground() }
    /** Opened, or brought back from the background (not after a permission prompt): look for an update to offer. */
    override fun onStart() { super.onStart(); (application as FuckPo0JiXianApp).controller.updater.resumed() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The tab bar paints its own fade down to the system bar; no gray scrim under 3-button navigation.
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        Screenshots.apply(this)
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

/** Screens stay out of screenshots and the recents thumbnail unless people allow them, e.g. to ask for help. */
internal object Screenshots {
    private fun prefs(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    fun allowed(context: Context) = prefs(context).getBoolean("allow_screenshots", false)
    fun set(activity: Activity, value: Boolean) { prefs(activity).edit().putBoolean("allow_screenshots", value).apply(); apply(activity) }
    fun apply(activity: Activity) {
        if (allowed(activity)) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
    val alive by c.runtime.alive.collectAsStateWithLifecycle()
    val moduleDown = s.runtimeMode == RuntimeMode.MODULE && !s.demo && !s.paused && alive == false
    LaunchedEffect(s.runtimeMode, s.paused) { if (s.runtimeMode == RuntimeMode.MODULE) c.refreshRuntime() }
    ManualDialog(c, busy)
    UpdateSheet(c.updater)
    var apNotice by rememberSaveable { mutableStateOf<String?>(null) }
    ApDialog(s, c, busy, apNotice) { apNotice = null }
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
                                if (page == 0) BrandHeader(s.deviceName) else LargeTitle(tabs[page].title)
                                if (s.demo) Box(Modifier.padding(start = 4.dp)) { Capsule("演示数据 · 不连接平台", colors.orange) }
                            }
                        }
                        when (page) {
                            0 -> homeItems(s, busy, network, currentKey, credential, moduleDown, c, configure = { page = 2 }) { page = 1 }
                            1 -> slotItems(s, c, busy, background, edit = { editing = it }, confirmAp = { apNotice = it }) { page = 2 }
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

/** The overview's masthead: the chick mark, the name, and which device this is. */
@Composable private fun BrandHeader(device: String) {
    val c = Apple.colors
    Row(Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFFFFCB45), Color(0xFFFFA81F)))),
            contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null,
                modifier = Modifier.requiredSize(66.dp))
        }
        Column {
            Text("去他妈的鸡险", style = Apple.title.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp), color = c.label)
            Text(device.ifBlank { "本机" }, style = Apple.footnote, color = c.secondary)
        }
    }
}

/**
 * A floating capsule of four tabs over the page; the selected one sits on a pill that glides between them.
 */
@Composable private fun TabBar(page: Int, badge: Int, select: (Int) -> Unit) {
    val c = Apple.colors
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0f), c.background)))
        .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.widthIn(max = 520.dp).fillMaxWidth().height(64.dp)
            .shadow(if (c.dark) 0.dp else 18.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(CircleShape).background(if (c.dark) c.segment.copy(alpha = 0.92f) else c.card)
            .then(if (c.dark) Modifier.border(0.5.dp, c.separator, CircleShape) else Modifier).padding(6.dp)) {
            val width = maxWidth / tabs.size
            val offset by animateDpAsState(width * page, spring(dampingRatio = 0.78f, stiffness = 500f), label = "pill")
            Box(Modifier.offset(x = offset).width(width).fillMaxHeight().clip(CircleShape)
                .background(c.ink.copy(alpha = if (c.dark) 0.12f else 0.07f)))
            Row(Modifier.fillMaxSize().selectableGroup()) {
                tabs.forEachIndexed { i, tab ->
                    val tint by animateColorAsState(if (page == i) c.label else c.gray, label = "tab")
                    val source = remember { MutableInteractionSource() }
                    Column(Modifier.weight(1f).fillMaxHeight().testTag("tab-$i").springPress(source)
                        .selectable(page == i, source, indication = null, role = Role.Tab) { select(i) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box {
                            Icon(tab.icon, null, tint = tint, modifier = Modifier.size(23.dp))
                            if (badge == i) Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-1).dp).size(8.dp).background(c.red, CircleShape))
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(tab.title, style = Apple.caption.copy(fontWeight = if (page == i) FontWeight.SemiBold else FontWeight.Medium), color = tint)
                    }
                }
            }
        }
    }
}
