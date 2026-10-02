package app.fuckpo0jixian

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fuckpo0jixian.core.*
import app.fuckpo0jixian.core.State

/** Android only reveals Wi-Fi identity to a background app holding "allow all the time" location. */
internal fun backgroundLocationGranted(context: Context) = Build.VERSION.SDK_INT >= 29 &&
    context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Re-read on every resume, since the user grants this in system settings. */
@Composable internal fun rememberBackgroundLocation(): Boolean {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return remember(lifecycle) { backgroundLocationGranted(context) }
}

internal fun purposeText(p: SlotPurpose) = when (p) { SlotPurpose.FIXED -> "固定网络"; SlotPurpose.MOBILE -> "设备移动"; SlotPurpose.RESERVED -> "保留用途" }
internal fun writerText(w: Writer) = when (w) { Writer.LOCAL -> "本机"; Writer.OTHER_DEVICE -> "其他设备"; Writer.EXTERNAL -> "外部 / 未知" }
/** Who writes this slot, as specific as the configuration allows. */
internal fun managerText(slot: ManagedSlot) = when (slot.writer) {
    Writer.LOCAL -> if (slot.shared) "本机 · 共管" else "本机"
    Writer.OTHER_DEVICE -> slot.owner.ifBlank { "其他设备" }
    Writer.EXTERNAL -> "外部 / 未知"
}
@Composable internal fun purposeColor(p: SlotPurpose) = when (p) {
    SlotPurpose.FIXED -> Apple.colors.accent; SlotPurpose.MOBILE -> Apple.colors.green; SlotPurpose.RESERVED -> Apple.colors.gray
}

/** Short identity label for the current Wi-Fi; null when there is no Wi-Fi observation. */
internal fun wifiIdentityText(s: State, wifi: WifiObservation?): String? {
    val o = wifi ?: return null
    if (!o.usable(System.currentTimeMillis(), o.networkKey)) return "无法识别"
    val match = s.layout?.identities?.filter { it.ssid == o.ssid && AuthorizedAp(o.bssid ?: "", o.security) in it.aps }.orEmpty()
    return when { match.size > 1 -> "匹配多个槽位"; match.size == 1 -> match.single().name; else -> "未绑定" }
}

@Composable internal fun CapacitySummary(s: State) {
    val snap = s.snapshot ?: return
    val colors = Apple.colors
    // One cell per slot number, so the bar reads in the same order as the slot list.
    val cells = CapacityBar.cells(snap, s.layout)
    val used = cells.filterNotNull()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("已用 ${snap.entries.size} / ${snap.capacity}", style = Apple.title.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = colors.label)
            Spacer(Modifier.weight(1f))
            Text("剩余 ${snap.remaining}", style = Apple.subhead, color = colors.secondary)
        }
        Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            cells.forEach { cell ->
                Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(cell?.let { purposeColor(it) } ?: colors.fill))
            }
        }
        Legend(used)
    }
}

@Composable private fun Legend(used: List<SlotPurpose>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(SlotPurpose.FIXED to "固定", SlotPurpose.MOBILE to "移动", SlotPurpose.RESERVED to "其他").filter { it.first in used }.forEach { (p, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(purposeColor(p)); Text(label, style = Apple.footnote, color = Apple.colors.secondary)
            }
        }
    }
}

/**
 * The whitelist as a map: one tile per quota cell in slot order, tinted by purpose, named, tappable. Empty cells are
 * outlined so the room left is visible at a glance.
 */
@Composable private fun SlotMap(s: State, snap: Snapshot, enabled: Boolean, edit: (Int) -> Unit) {
    val colors = Apple.colors
    val cells = CapacityBar.cells(snap, s.layout)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.withIndex().chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (n, purpose) ->
                    val slot = s.layout?.slots?.find { it.number == n }
                    val tint = purpose?.let { purposeColor(it) }
                    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Column(Modifier.weight(1f).height(66.dp).springPress(source, enabled).clip(RoundedCornerShape(14.dp))
                        .background(tint?.copy(alpha = if (colors.dark) 0.2f else 0.12f) ?: Color.Transparent)
                        .then(if (tint == null) Modifier.border(1.dp, colors.separator, RoundedCornerShape(14.dp)) else Modifier)
                        .clickable(source, indication = null, enabled = enabled && n < snap.capacity, role = Role.Button) { edit(n) }
                        .padding(horizontal = 9.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text("${n + 1}", style = Apple.headline.copy(fontFeatureSettings = "tnum"), color = tint ?: colors.tertiary)
                        Text(slot?.name?.takeIf { it.isNotBlank() } ?: if (purpose == null) "空" else "未命名",
                            style = Apple.caption, color = if (tint == null) colors.tertiary else colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

internal fun LazyListScope.slotItems(s: State, c: Controller, busy: Boolean, background: Boolean, edit: (Int) -> Unit,
                                     confirmAp: (String) -> Unit, configure: () -> Unit) {
    val snap = s.snapshot
    item(key = "wl-summary") {
        val wifi by c.wifiObservation.collectAsState()
        val colors = Apple.colors
        if (snap == null) Section(footer = "请先在「设置」中连接 Po0 并检查连接。") {
            ListRow("尚未获取白名单", titleColor = Apple.colors.secondary)
            ActionRow("前往设置", onClick = configure)
        } else Reveal(0) {
            Panel {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("名额", style = Apple.overline, color = colors.secondary)
                        Text("已用 ${snap.entries.size} / ${snap.capacity}", style = Apple.display.copy(fontSize = Apple.display.fontSize * 0.82f), color = colors.label)
                    }
                    Text("剩余 ${snap.remaining}", style = Apple.subhead.copy(fontWeight = FontWeight.Medium),
                        color = if (snap.remaining == 0) colors.orange else colors.secondary)
                }
                Spacer(Modifier.height(16.dp))
                SlotMap(s, snap, !busy, edit)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Legend(CapacityBar.cells(snap, s.layout).filterNotNull()) }
                    Row(Modifier.clip(CircleShape).background(colors.fill).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Glyphs.Wifi, null, tint = colors.secondary, modifier = Modifier.size(13.dp))
                        Text(if (s.demo) "模拟网络" else wifiIdentityText(s, wifi) ?: "未使用 Wi-Fi", style = Apple.footnote.copy(fontWeight = FontWeight.Medium),
                            color = colors.label, maxLines = 1)
                    }
                }
            }
        }
    }
    // Only notices this device can act on: one bound slot to join. The rest are left for the slot editor.
    val apNotices = s.layout?.let { l -> l.notices.filter { it.startsWith(ApNotice.CONFIRM) }
        .mapNotNull { n -> ApNotice.parse(n)?.let { ap -> ApNotice.slot(l, n)?.let { slot -> Triple(n, ap, slot) } } } }.orEmpty()
    val notices = listOfNotNull(
        "旧配置已迁移，固定槽需要重新绑定 Wi-Fi".takeIf { s.layout?.notices?.contains("MIGRATED_WIFI_POLICY_OFF") == true },
        s.globalBlock?.let(::statusText),
        "有待确认的写入，下次检查会先核对".takeIf { s.layout?.pending != null },
        "后台无法识别 Wi-Fi：请在固定槽中开启「Wi-Fi 识别」".takeIf { !s.demo && !background &&
            s.layout?.slots?.any { it.purpose == SlotPurpose.FIXED && it.writer == Writer.LOCAL && it.bound } == true })
    if (notices.isNotEmpty() || apNotices.isNotEmpty()) item(key = "wl-notices") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            apNotices.forEach { (notice, ap, slot) ->
                Callout("「${ap.ssid}」有新的接入点", "加入${slotLabel(slot)}前需要你确认", Apple.colors.orange, Icons.Rounded.Warning,
                    onClick = if (busy) null else ({ confirmAp(notice) }))
            }
            notices.forEach { Callout(it, null, Apple.colors.orange, Icons.Rounded.Warning) }
        }
    }
    if (snap != null) item(key = "wl-slots") {
        Reveal(1) {
            Column {
                Text("槽位", style = Apple.footnote.copy(fontWeight = FontWeight.SemiBold), color = Apple.colors.secondary,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    (0 until snap.capacity).forEach { number ->
                        val slot = s.layout?.slots?.find { it.number == number } ?: ManagedSlot(number)
                        SlotCard(slot, snap.entries.find { it.slot == number }?.cidr?.value, enabled = !busy) { edit(number) }
                    }
                }
                Text("修改只保存在本机，开启自动同步后才会写入 Po0。", style = Apple.footnote, color = Apple.colors.secondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            }
        }
    }
    val unassigned = snap?.entries.orEmpty().filter { it.slot == null }
    if (unassigned.isNotEmpty()) item(key = "wl-unassigned") {
        Section(header = "未分配槽号", footer = "这些记录同样占用名额，本机不会改动它们。") {
            unassigned.forEach { ListRow(it.cidr.value, value = "受保护") }
        }
    }
}

private val healthy = setOf("SLOT_CURRENT", "SLOT_UPDATED", "RECOVERED_VERIFIED", "AUTHORIZED_LOCAL", "PEER_UPDATED")
private val attention = setOf("SLOT_CONFLICT", "SLOT_VERIFY_FAILED", "IDENTITY_AMBIGUOUS", "WIFI_SECURITY_CHANGED", "EXTERNAL_CHANGE",
    "COVERED_OTHER_SLOT", "PENDING_REVIEW", "SHARED_RECENT", "PENDING_CONFIG_CHANGED", "LEGACY_UNINITIALIZED", "REBIND_REQUIRED")

/** One slot: its number in its purpose color, name and address, who writes it, and how it stands. */
@Composable private fun SlotCard(slot: ManagedSlot, remote: String?, enabled: Boolean, onClick: () -> Unit) {
    val colors = Apple.colors
    val tint = when (slot.status) { in healthy -> colors.green; in attention -> colors.orange; else -> colors.gray }
    val purpose = purposeColor(slot.purpose)
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().testTag("slot-${slot.number + 1}").springPress(source, enabled).clip(RoundedCornerShape(Apple.radius))
        .background(colors.card).clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(purpose.copy(alpha = if (colors.dark) 0.22f else 0.13f)),
            contentAlignment = Alignment.Center) {
            Text("${slot.number + 1}", style = Apple.title.copy(fontWeight = FontWeight.Bold), color = purpose)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(slot.name.ifBlank { "槽 ${slot.number + 1}" }, style = Apple.headline, color = if (enabled) colors.label else colors.tertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(remote ?: "空", style = Apple.subhead.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
                    color = if (remote == null) colors.tertiary else colors.label)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${purposeText(slot.purpose)} · ${managerText(slot)}", style = Apple.footnote, color = colors.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(tint); Text(statusText(slot.status), style = Apple.footnote, color = if (tint == colors.orange) colors.orange else colors.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = colors.tertiary, modifier = Modifier.size(22.dp))
    }
}

@Composable private fun NavButton(text: String, enabled: Boolean = true, bold: Boolean = false, onClick: () -> Unit) {
    Text(text, style = Apple.body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = if (enabled) Apple.colors.accent else Apple.colors.tertiary,
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable internal fun SlotEditor(s: State, original: ManagedSlot, c: Controller, busy: Boolean, close: () -> Unit) {
    var name by remember { mutableStateOf(original.name) }
    var purpose by remember { mutableStateOf(original.purpose) }
    var writer by remember { mutableStateOf(original.writer) }
    var automatic by remember { mutableStateOf(original.automatic) }
    var unknown by remember { mutableStateOf(original.allowUnknownWifi) }
    var hold by remember { mutableStateOf(original.temporaryHold) }
    var owner by remember { mutableStateOf(original.owner) }
    var shared by remember { mutableStateOf(original.shared) }
    var acknowledge by remember { mutableStateOf(false) }
    var bind by remember { mutableStateOf<Boolean?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var bindNext by remember { mutableStateOf(false) }
    val wifi by c.wifiObservation.collectAsState()
    val context = LocalContext.current
    val background = rememberBackgroundLocation()
    val foregroundGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    // One tap walks the whole chain: location switch → Wi-Fi permission → "allow all the time". A denial that
    // returns instantly means the system will not ask again, so open the app's permission page instead.
    var askedAt by remember { mutableLongStateOf(0L) }
    fun openAppSettings() = context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    fun instant() = SystemClock.elapsedRealtime() - askedAt < 500
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        c.foreground(); c.refreshWifi(); if (!granted && instant()) openAppSettings()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        c.foreground(); c.refreshWifi()
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fine && Build.VERSION.SDK_INT >= 31 && !backgroundLocationGranted(context)) {
            askedAt = SystemClock.elapsedRealtime(); backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (!fine && instant()) openAppSettings()
    }
    val locationOn = context.getSystemService(LocationManager::class.java).isLocationEnabled
    fun enableWifiAccess() {
        askedAt = SystemClock.elapsedRealtime()
        when {
            !locationOn -> context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            !foregroundGranted -> permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            else -> backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    val networks = s.layout?.networksOf(original).orEmpty()
    val remote = s.snapshot?.entries?.find { it.slot == original.number }?.cidr
    val colors = Apple.colors
    val scroll = rememberScrollState()
    BackHandler(onBack = close)
    LaunchedEffect(Unit) { c.refreshWifi() }
    LaunchedEffect(error) { if (error != null) scroll.animateScrollTo(0) }
    // After authorizing a fixed slot, stay here and bring the Wi-Fi binding step into view.
    val bindable = original.purpose == SlotPurpose.FIXED && original.writer == Writer.LOCAL && original.authorized
    LaunchedEffect(bindNext, bindable) {
        if (bindNext && bindable) { c.refreshWifi(); withFrameNanos { }; scroll.animateScrollTo(scroll.maxValue) }
    }
    val candidate = original.copy(name = name, purpose = purpose, writer = writer, automatic = automatic,
        allowUnknownWifi = unknown, temporaryHold = hold, owner = owner, shared = shared)
    fun save() {
        val validation = runCatching { LayoutRules.saveSlot(s, candidate, acknowledge, System.currentTimeMillis()) }
        if (validation.isSuccess) {
            c.configureSlot(candidate, acknowledge)
            val saved = validation.getOrNull()?.layout?.slots?.find { it.number == original.number }
            error = null
            if (saved != null && saved.purpose == SlotPurpose.FIXED && saved.writer == Writer.LOCAL && saved.authorized && !saved.bound) {
                bindNext = true; acknowledge = false
            } else close()
        }
        else error = statusText((validation.exceptionOrNull() as? ApiFailure)?.code ?: validation.exceptionOrNull()?.message ?: "CONFIG_INVALID")
    }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxWidth().background(colors.bar).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) { NavButton(if (bindNext) "完成" else "取消", onClick = close) }
                Text("槽 ${original.number + 1}", style = Apple.headline, color = colors.label, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd)) { NavButton("保存", enabled = !busy, bold = true, onClick = ::save) }
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(scroll).imePadding().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                // Which slot this is, before any field: its number in its purpose color and what Po0 holds there now.
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val tint = purposeColor(purpose)
                    val bg by androidx.compose.animation.animateColorAsState(tint.copy(alpha = if (colors.dark) 0.22f else 0.13f), label = "tile")
                    Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(bg), contentAlignment = Alignment.Center) {
                        Text("${original.number + 1}", style = Apple.largeTitle.copy(fontSize = Apple.largeTitle.fontSize * 0.85f), color = tint)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        // As saved: the field below is where the name changes.
                        Text(original.name.ifBlank { "槽 ${original.number + 1}" }, style = Apple.title.copy(fontWeight = FontWeight.Bold), color = colors.label,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(remote?.value ?: "空槽", style = Apple.subhead.copy(fontFeatureSettings = "tnum"), color = colors.secondary)
                    }
                }
                error?.let { message ->
                    Section { ListRow(message, titleColor = colors.red,
                        leading = { Icon(Icons.Rounded.Warning, null, tint = colors.red, modifier = Modifier.size(22.dp)) }) }
                }
                // Ticks the countdown of the co-managed quiet period.
                val now by produceState(System.currentTimeMillis(), original.changedAt) {
                    while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(20_000) }
                }
                changeNotice(original, now, ::time)?.let { (title, detail) ->
                    Section { ListRow(title, subtitle = detail, leading = {
                        Icon(Icons.Rounded.Warning, null, tint = colors.orange, modifier = Modifier.size(22.dp)) }) }
                }
                Section(header = "名称", footer = "平台编号 ${original.number} · 当前记录 ${remote?.value ?: "空"}") {
                    Box(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        if (name.isEmpty()) Text("例如：家、公司、这台手机", style = Apple.body, color = colors.tertiary)
                        BasicTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth().testTag("slot-name"), singleLine = true,
                            textStyle = Apple.body.copy(color = colors.label), cursorBrush = SolidColor(colors.accent))
                    }
                }
                Section(header = "用途", footer = when (purpose) {
                    SlotPurpose.FIXED -> "绑定家庭、公司等固定 Wi-Fi，出口变化时更新此槽。"
                    SlotPurpose.MOBILE -> "跟随这台手机的移动网络出口。"
                    SlotPurpose.RESERVED -> "留作他用，不会自动更新。"
                }) { SegmentedControl(SlotPurpose.entries.map(::purposeText), purpose.ordinal, { purpose = SlotPurpose.entries[it] }) }
                Section(header = "管理者", footer = when (writer) {
                    Writer.LOCAL -> "由这台设备写入此槽。"
                    Writer.OTHER_DEVICE -> "由另一台设备管理，本机只读，变化会标注出来。"
                    Writer.EXTERNAL -> "来源不明，本机不会改动。"
                }) {
                    SegmentedControl(Writer.entries.map(::writerText), writer.ordinal, { writer = Writer.entries[it] })
                    if (writer == Writer.OTHER_DEVICE) OwnerField(owner) { owner = it }
                }
                if (writer == Writer.LOCAL && purpose != SlotPurpose.RESERVED) {
                    Section(header = "自动更新", footer = if (purpose == SlotPurpose.MOBILE)
                        "每台手机只能有一个自动移动槽，默认只在移动数据下更新。多台手机请各自使用独立槽位。" else null) {
                        ToggleRow("自动更新此槽", automatic, { automatic = it })
                        if (purpose == SlotPurpose.FIXED)
                            ToggleRow("与其他设备共管", shared, { shared = it }, subtitle = "同一网络的电脑或手机也可更新此槽")
                        if (purpose == SlotPurpose.MOBILE) {
                            ToggleRow("未知 Wi-Fi 也可更新", unknown, { unknown = it })
                            ToggleRow("锁定当前 IP", hold, { hold = it }, subtitle = "网络变化时不替换这一格")
                        }
                    }
                    // Show the saved authorization; the switch only grants a new one (and re-bases on this save).
                    // Any edit that adds automatic power (e.g. unknown Wi-Fi) must bring the switch back, or saving deadlocks.
                    val authorizedNow = original.authorized && purpose == original.purpose && writer == original.writer &&
                        !LayoutRules.needsAuthorization(original, candidate)
                    if (authorizedNow && !acknowledge) Section(footer = "授权基线：${original.baseline?.value ?: "空槽"}。" +
                        if (original.shared) "共管设备的写入会被接受；刚被改成其他值时本机会先等待。" else "此槽被其他设备改动时会自动取消授权。") {
                        ListRow("授权本机管理", trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("已授权", style = Apple.body, color = colors.green)
                                Icon(Icons.Rounded.Check, null, tint = colors.green, modifier = Modifier.size(20.dp))
                            }
                        })
                    } else Section(footer = (if (original.authorized) "新增了自动更新权限，需再次确认。" else "") +
                        "当前记录（${remote?.value ?: "空"}）将作为授权基线。" +
                        if (shared && purpose == SlotPurpose.FIXED) "同一网络的其他设备也可写入此槽。" else "请确认没有其他设备在写入此槽。") {
                        ToggleRow("授权本机管理", acknowledge, { acknowledge = it })
                    }
                }
                if (purpose == SlotPurpose.FIXED && writer == Writer.LOCAL && !bindable) {
                    Section(header = "Wi-Fi 绑定", footer = "打开「授权本机管理」并保存后，即可在这里绑定当前 Wi-Fi。") {
                        ListRow("保存后可绑定", titleColor = colors.secondary)
                    }
                }
                if (bindable) {
                    val usable = wifi?.usable(System.currentTimeMillis(), if (s.demo) "demo" else c.currentNetworkKey.value ?: "") == true
                    val access = when {
                        s.demo -> null
                        Build.VERSION.SDK_INT < 31 -> "仅手动更新"
                        !locationOn -> "定位服务已关闭"
                        !foregroundGranted -> "未开启"
                        !background -> "仅前台"
                        else -> "已开启"
                    }
                    // Several networks may share one exit (an office with two Wi-Fi names): all of them update this slot.
                    val here = networks.find { usable && it.ssid == wifi?.ssid }
                    val knownAp = here != null && here.aps.any { it.bssid.equals(wifi?.bssid, true) && it.security == wifi?.security }
                    val elsewhere = s.layout?.let { l -> l.identities.find { usable && it.ssid == wifi?.ssid && it.id !in original.identityIds }?.let { l.slotOf(it.id) } }
                    Section(header = "Wi-Fi 绑定", footer = "可绑定多个出口相同的 Wi-Fi，连上其中任何一个都会更新此槽。只读取名称和接入点，不获取位置；后台更新需要位置权限「始终允许」。") {
                        if (bindNext && networks.isEmpty()) ListRow("已保存。连接到要绑定的 Wi-Fi，然后点「绑定当前 Wi-Fi」。", titleColor = colors.green)
                        if (networks.isEmpty()) ListRow("已绑定", value = "未绑定")
                        networks.forEach { n ->
                            ListRow(n.ssid, Modifier.testTag("network-${n.ssid}"), subtitle = "${n.aps.size} 个接入点" + if (n == here) " · 当前连接" else "",
                                chevron = expanded != n.id) { expanded = if (expanded == n.id) null else n.id }
                            if (expanded == n.id) {
                                n.aps.forEach { ListRow(it.bssid, value = it.security.name) }
                                ActionRow("移除「${n.ssid}」", color = colors.red, enabled = !busy) { c.unbindWifi(original.number, n.id); expanded = null }
                            }
                        }
                        ListRow("当前 Wi-Fi", value = if (usable) wifi?.ssid else "无法读取")
                        access?.let { value ->
                            ListRow("Wi-Fi 识别", value = value)
                            if (Build.VERSION.SDK_INT >= 31 && value != "已开启") ActionRow("一键开启 Wi-Fi 识别", onClick = ::enableWifiAccess)
                        }
                        when {
                            knownAp -> ListRow("当前 Wi-Fi 已绑定此槽", titleColor = colors.secondary)
                            here != null -> ActionRow("添加当前接入点", enabled = !busy) { bind = true }
                            elsewhere != null -> ListRow("当前 Wi-Fi 已绑定${slotLabel(elsewhere)}", titleColor = colors.secondary)
                            else -> ActionRow(if (networks.isEmpty()) "绑定当前 Wi-Fi" else "添加当前 Wi-Fi",
                                enabled = usable && !busy && networks.size < LayoutRules.MAX_NETWORKS) { bind = false }
                        }
                    }
                    Section(footer = if (s.paused) "需先在概览中恢复检查。" else "现场核对出口后更新一次，不会保存 Wi-Fi 授权。") {
                        ActionRow("手动更新一次", enabled = !busy && !s.paused) { c.previewManual(original.number); close() }
                    }
                }
                Text("保存只修改本机配置，开启自动同步后才会写入 Po0。", style = Apple.footnote, color = colors.secondary,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
        }
    }
    bind?.let { add ->
        val observed = remember { wifi }
        val elsewhere = s.snapshot?.entries?.find { it.cidr == s.snapshot?.current && it.slot != original.number }
        IosAlert(if (add) "添加此接入点？" else "绑定此 Wi-Fi？", { bind = null },
            message = "${observed?.ssid ?: "未知网络"}\n${observed?.bssid ?: "未知接入点"} · ${observed?.security ?: "未知"}",
            content = {
                Spacer(Modifier.height(4.dp))
                Text((if (!add && networks.isNotEmpty()) "与已绑定的 ${networks.size} 个网络一起更新槽 ${original.number + 1}，请确认它们出口相同。"
                    else "此网络的出口变化时，将自动更新槽 ${original.number + 1}。") + "可防止误连，但无法防范刻意伪造的同名热点。",
                    style = Apple.footnote, color = colors.secondary, textAlign = TextAlign.Center)
                elsewhere?.let { e -> Text("当前出口位于${e.slot?.let { "槽 ${it + 1}" } ?: "未分配槽号的记录"}，绑定不会移动已有记录。",
                    style = Apple.footnote, color = colors.orange, textAlign = TextAlign.Center) }
            },
            actions = listOf(AlertAction("取消") { bind = null },
                AlertAction("授权并绑定", preferred = true, enabled = observed != null && !busy) {
                    observed?.let { c.bindWifi(original.number, name, it) }; bind = null; close()
                }))
    }
}

private fun slotLabel(slot: ManagedSlot) = "槽 ${slot.number + 1}" + slot.name.takeIf { it.isNotBlank() }?.let { "「$it」" }.orEmpty()

/** Confirms a same-name access point recorded earlier; works away from that Wi-Fi since the notice holds the AP. */
@Composable internal fun ApDialog(s: State, c: Controller, busy: Boolean, notice: String?, close: () -> Unit) {
    val layout = s.layout ?: return
    val n = notice?.takeIf { it in layout.notices } ?: return
    val ap = ApNotice.parse(n) ?: return
    val slot = ApNotice.slot(layout, n) ?: return
    IosAlert("加入${slotLabel(slot)}？", close,
        message = "${ap.ssid}\n${ap.bssid} · ${ap.security}",
        content = {
            Spacer(Modifier.height(4.dp))
            Text("同名 Wi-Fi 的另一台路由器或另一个频段。加入后连到它时也会更新此槽；若不认识，请忽略。",
                style = Apple.footnote, color = Apple.colors.secondary, textAlign = TextAlign.Center)
        },
        actions = listOf(
            AlertAction("加入", preferred = true, enabled = !busy && slot.authorized) { c.acceptAp(n); close() },
            AlertAction("忽略", destructive = true) { c.ignoreAp(n); close() },
            AlertAction("取消", onClick = close)))
}

@Composable internal fun ManualDialog(c: Controller, busy: Boolean) {
    val permit by c.manualPreview.collectAsState()
    permit?.let { p ->
        LaunchedEffect(p) { kotlinx.coroutines.delay((p.expires - System.currentTimeMillis()).coerceAtLeast(0)); c.cancelManual() }
        IosAlert("更新槽 ${p.slot + 1}", c::cancelManual,
            message = "${p.before.entries.find { it.slot == p.slot }?.cidr?.value ?: "空"}  →  ${p.before.current.value}",
            content = {
                Spacer(Modifier.height(4.dp))
                Text("3 分钟内有效，执行前会再次核对。请确保没有其他设备同时修改此槽。",
                    style = Apple.footnote, color = Apple.colors.secondary, textAlign = TextAlign.Center)
                if (busy) Text("正在等待…", style = Apple.footnote, color = Apple.colors.accent)
            },
            actions = listOf(AlertAction("取消", onClick = c::cancelManual),
                AlertAction("确认更新", preferred = true, enabled = !busy, onClick = c::confirmManual)))
    }
}

/** Name of the device that manages the slot, with the three common choices one tap away. */
@Composable private fun OwnerField(owner: String, onChange: (String) -> Unit) {
    val colors = Apple.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.fill).padding(horizontal = 10.dp, vertical = 9.dp)) {
            if (owner.isEmpty()) Text("设备名称，例如 Mac、办公室电脑", style = Apple.subhead, color = colors.tertiary)
            BasicTextField(owner, { onChange(it.take(24)) }, Modifier.fillMaxWidth().testTag("slot-owner"), singleLine = true,
                textStyle = Apple.subhead.copy(color = colors.label), cursorBrush = SolidColor(colors.accent))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Mac", "Windows", "另一台手机").forEach { choice ->
                Text(choice, style = Apple.footnote, color = if (owner == choice) Color.White else colors.accent,
                    modifier = Modifier.clip(CircleShape).background(if (owner == choice) colors.accent else colors.accent.copy(alpha = 0.12f))
                        .clickable(role = Role.Button) { onChange(choice) }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
