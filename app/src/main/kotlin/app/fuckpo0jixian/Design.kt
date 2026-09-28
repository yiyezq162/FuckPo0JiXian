package app.fuckpo0jixian

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** iOS-style semantic colors; every screen reads these instead of raw Material roles. */
@Immutable data class AppleColors(
    val accent: Color, val green: Color, val orange: Color, val red: Color, val indigo: Color, val gray: Color,
    val background: Color, val card: Color, val pressed: Color, val label: Color, val secondary: Color,
    val tertiary: Color, val separator: Color, val fill: Color, val segment: Color, val bar: Color,
)
private val Light = AppleColors(
    accent = Color(0xFF007AFF), green = Color(0xFF34C759), orange = Color(0xFFFF9500), red = Color(0xFFFF3B30),
    indigo = Color(0xFF5856D6), gray = Color(0xFF8E8E93), background = Color(0xFFF2F2F7), card = Color.White,
    pressed = Color(0xFFD1D1D6), label = Color.Black, secondary = Color(0x993C3C43), tertiary = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43), fill = Color(0x1F787880), segment = Color.White, bar = Color(0xFFF9F9F9))
private val Dark = AppleColors(
    accent = Color(0xFF0A84FF), green = Color(0xFF30D158), orange = Color(0xFFFF9F0A), red = Color(0xFFFF453A),
    indigo = Color(0xFF5E5CE6), gray = Color(0xFF8E8E93), background = Color.Black, card = Color(0xFF1C1C1E),
    pressed = Color(0xFF3A3A3C), label = Color.White, secondary = Color(0x99EBEBF5), tertiary = Color(0x4DEBEBF5),
    separator = Color(0xA6545458), fill = Color(0x3D767680), segment = Color(0xFF636366), bar = Color(0xFF161618))
private val LocalApple = staticCompositionLocalOf { Light }

object Apple {
    val colors: AppleColors @Composable @ReadOnlyComposable get() = LocalApple.current
    val largeTitle = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val subhead = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable fun FuckPo0JiXianTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) Dark else Light
    val scheme = (if (c === Dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = Color.White, background = c.background, surface = c.card,
        onBackground = c.label, onSurface = c.label, onSurfaceVariant = c.secondary, error = c.red, outline = c.separator)
    CompositionLocalProvider(LocalApple provides c) {
        MaterialTheme(colorScheme = scheme) { ProvideTextStyle(Apple.body.copy(color = c.label), content) }
    }
}

/** Glyphs missing from material-icons-core, drawn from Material path data. */
object Glyphs {
    private fun glyph(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
    val Wifi = glyph("wifi", "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z")
    val Cellular = glyph("cellular", "M17,4h3v16h-3zM5,14h3v6H5zM11,9h3v11h-3z")
    val Pause = glyph("pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z")
    val History = glyph("history", "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z")
    val Shield = glyph("shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM10,17l-4,-4 1.41,-1.41L10,14.17l6.59,-6.59L18,9l-8,8z")
    val Key = glyph("key", "M12.65,10C11.83,7.67 9.61,6 7,6c-3.31,0 -6,2.69 -6,6s2.69,6 6,6c2.61,0 4.83,-1.67 5.65,-4H17v4h4v-4h2v-4H12.65zM7,14c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z")
    val Sync = glyph("sync", "M12,4V1L8,5l4,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C19.54,15.03 20,13.57 20,12c0,-4.42 -3.58,-8 -8,-8zm0,14c-3.31,0 -6,-2.69 -6,-6 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3l4,-4 -4,-4v3z")
    val Bolt = glyph("bolt", "M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66 0.19,-0.34 0.05,-0.08 0.07,-0.12C8.48,10.94 10.42,7.54 13,3h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51l-0.07,0.15C12.96,17.55 11,21 11,21z")
    val Download = glyph("download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    val Globe = glyph("globe", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm-1,17.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79L9,15v1c0,1.1 0.9,2 2,2v1.93zm6.9,-2.54c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1H8v-2h2c0.55,0 1,-0.45 1,-1V7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41 0,2.08 -0.8,3.97 -2.1,5.39z")
}

@Composable fun LargeTitle(text: String) {
    Text(text, style = Apple.largeTitle, color = Apple.colors.label, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp))
}

/** Inset grouped section: rounded card whose rows are separated by inset hairlines. */
@Composable fun Section(header: String? = null, footer: String? = null, inset: Dp = 16.dp, modifier: Modifier = Modifier,
                        content: @Composable () -> Unit) {
    val c = Apple.colors
    Column(modifier.fillMaxWidth()) {
        header?.let { Text(it, style = Apple.footnote, color = c.secondary, modifier = Modifier.padding(start = 16.dp, bottom = 7.dp)) }
        SubcomposeLayout(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card)) { constraints ->
            val loose = constraints.copy(minHeight = 0)
            val rows = subcompose("rows", content).map { it.measure(loose) }.filter { it.height > 0 }
            val hairline = maxOf(1, (0.5.dp).roundToPx())
            val start = inset.roundToPx()
            val lines = subcompose("lines") { repeat(maxOf(0, rows.size - 1)) { Box(Modifier.background(c.separator)) } }
                .map { it.measure(Constraints.fixed((constraints.maxWidth - start).coerceAtLeast(0), hairline)) }
            layout(constraints.maxWidth, rows.sumOf { it.height }) {
                var y = 0
                rows.forEachIndexed { i, row ->
                    row.place(0, y); y += row.height
                    lines.getOrNull(i)?.place(start, y - hairline)
                }
            }
        }
        footer?.let { Text(it, style = Apple.footnote, color = c.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp)) }
    }
}

@Composable internal fun Modifier.pressable(onClick: (() -> Unit)?, enabled: Boolean, role: Role = Role.Button): Modifier {
    if (onClick == null) return this
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return background(if (pressed) Apple.colors.pressed else Color.Transparent)
        .clickable(source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

@Composable fun IconTile(icon: ImageVector, tint: Color) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(tint), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable fun NumberBadge(label: String, tint: Color) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(tint), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, style = Apple.headline)
    }
}

@Composable fun ListRow(
    title: String, modifier: Modifier = Modifier, value: String? = null, subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = Apple.colors.label, chevron: Boolean = false, enabled: Boolean = true, onClick: (() -> Unit)? = null,
) {
    val c = Apple.colors
    Row(modifier.fillMaxWidth().pressable(onClick, enabled).heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Apple.body, color = if (enabled) titleColor else c.tertiary)
            subtitle?.let { Text(it, style = Apple.footnote, color = c.secondary) }
        }
        value?.let { Text(it, style = Apple.body, color = c.secondary, textAlign = TextAlign.End, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) }
        trailing?.invoke()
        if (chevron) Icon(Icons.Rounded.KeyboardArrowRight, null, tint = c.tertiary, modifier = Modifier.size(22.dp))
    }
}

/** Tappable text row, like iOS settings actions. */
@Composable fun ActionRow(text: String, modifier: Modifier = Modifier, color: Color = Apple.colors.accent, enabled: Boolean = true,
                          loading: Boolean = false, onClick: () -> Unit) {
    val c = Apple.colors
    Row(modifier.fillMaxWidth().pressable(onClick, enabled && !loading).heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Apple.body, color = if (enabled) color else c.tertiary, modifier = Modifier.weight(1f))
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = c.secondary, strokeWidth = 2.dp)
    }
}

@Composable fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true,
                          leading: (@Composable () -> Unit)? = null) {
    ListRow(title, subtitle = subtitle, leading = leading, trailing = { IosSwitch(checked, onChange, title, enabled) })
}

/** Selectable row with a trailing checkmark, the iOS pattern for single choice. */
@Composable fun CheckRow(title: String, selected: Boolean, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Apple.colors
    Row(Modifier.fillMaxWidth().then(run {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        Modifier.background(if (pressed) c.pressed else Color.Transparent)
            .selectable(selected, source, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }).heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Apple.body, color = if (enabled) c.label else c.tertiary)
            subtitle?.let { Text(it, style = Apple.footnote, color = c.secondary) }
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(22.dp))
    }
}

@Composable fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit, description: String, enabled: Boolean = true) {
    val c = Apple.colors
    val offset by animateDpAsState(if (checked) 20.dp else 0.dp, spring(dampingRatio = 0.7f, stiffness = 600f), label = "thumb")
    val track by animateColorAsState(if (checked) c.green else c.fill, label = "track")
    Box(Modifier.size(51.dp, 31.dp).clip(CircleShape).background(track)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = description }
        .alpha(if (enabled) 1f else 0.45f).padding(2.dp)) {
        Box(Modifier.offset(x = offset).size(27.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
    }
}

@Composable fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    val c = Apple.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(9.dp)).background(c.fill)
        .padding(2.dp).selectableGroup()) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(Modifier.weight(1f).height(32.dp)
                .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(7.dp)).background(c.segment, RoundedCornerShape(7.dp)) else Modifier)
                .selectable(on, enabled = enabled, role = Role.Tab) { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(label, style = Apple.footnote.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (enabled) c.label else c.tertiary, maxLines = 1)
            }
        }
    }
}

@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                              loading: Boolean = false, tinted: Boolean = false) {
    val c = Apple.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) 0.7f else 1f, label = "press")
    val bg = when { !enabled -> c.fill; tinted -> c.accent.copy(alpha = if (c.background == Color.Black) 0.24f else 0.12f); else -> c.accent }
    val fg = when { !enabled -> c.tertiary; tinted -> c.accent; else -> Color.White }
    Row(modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(bg.copy(alpha = bg.alpha * alpha))
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (loading) { CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
        Text(text, style = Apple.headline, color = fg)
    }
}

@Composable fun Capsule(text: String, tint: Color) {
    Text(text, style = Apple.footnote.copy(fontWeight = FontWeight.SemiBold), color = tint,
        modifier = Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable fun Dot(tint: Color) { Box(Modifier.size(8.dp).background(tint, CircleShape)) }

class AlertAction(val text: String, val destructive: Boolean = false, val preferred: Boolean = false,
                  val enabled: Boolean = true, val onClick: () -> Unit)

/** iOS alert: centered title, short message, hairline-separated actions. */
@Composable fun IosAlert(title: String, onDismiss: () -> Unit, actions: List<AlertAction>, message: String? = null,
                         content: (@Composable ColumnScope.() -> Unit)? = null) {
    val c = Apple.colors
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 300.dp).fillMaxWidth(0.78f).clip(RoundedCornerShape(14.dp)).background(c.card)) {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = Apple.headline, color = c.label, textAlign = TextAlign.Center)
                message?.let { Text(it, style = Apple.footnote, color = c.label, textAlign = TextAlign.Center) }
                content?.invoke(this)
            }
            HorizontalDivider(thickness = 0.5.dp, color = c.separator)
            @Composable fun Action(a: AlertAction, modifier: Modifier) {
                Box(modifier.heightIn(min = 46.dp).pressable(a.onClick, a.enabled), contentAlignment = Alignment.Center) {
                    Text(a.text, style = Apple.body.copy(fontWeight = if (a.preferred) FontWeight.SemiBold else FontWeight.Normal),
                        color = when { !a.enabled -> c.tertiary; a.destructive -> c.red; else -> c.accent },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp), textAlign = TextAlign.Center)
                }
            }
            if (actions.size == 2) Row(Modifier.height(IntrinsicSize.Min)) {
                Action(actions[0], Modifier.weight(1f))
                VerticalDivider(thickness = 0.5.dp, color = c.separator)
                Action(actions[1], Modifier.weight(1f))
            } else actions.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = c.separator)
                Action(a, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Floating HUD for transient feedback; the underlying flow keeps its last value. */
@Composable fun Toast(message: String, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message.isBlank()) return@LaunchedEffect
        shown = message; visible = true
        kotlinx.coroutines.delay(3_500); visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        val c = Apple.colors
        Text(shown, style = Apple.subhead, color = c.label, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp).shadow(12.dp, RoundedCornerShape(22.dp))
                .background(c.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 11.dp))
    }
}

/**
 * iOS-style − / + stepper. Holding a button repeats; the value is committed once the taps stop, so a long run of
 * steps saves (and reschedules) only once.
 */
@Composable fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit, label: (Int) -> String = { "$it" }) {
    val c = Apple.colors
    var draft by remember(value) { mutableIntStateOf(value) }
    val commit by rememberUpdatedState(onChange)
    LaunchedEffect(draft) { if (draft != value) { delay(700); commit(draft) } }
    @Composable fun Button(text: String, step: Int) {
        val enabled = draft + step in range
        Box(Modifier.size(44.dp, 32.dp).alpha(if (enabled) 1f else 0.35f)
            .semantics { contentDescription = if (step > 0) "增加" else "减少" }
            .pointerInput(step) {
                detectTapGestures(onPress = {
                    coroutineScope {
                        val repeat = launch {
                            draft = (draft + step).coerceIn(range)
                            delay(420)
                            while (true) { draft = (draft + step).coerceIn(range); delay(70) }
                        }
                        tryAwaitRelease(); repeat.cancel()
                    }
                })
            }, contentAlignment = Alignment.Center) {
            Text(text, style = Apple.title, color = c.label)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label(draft), style = Apple.body, color = c.secondary)
        Row(Modifier.clip(RoundedCornerShape(9.dp)).background(c.fill), verticalAlignment = Alignment.CenterVertically) {
            Button("−", -1)
            Box(Modifier.size(0.5.dp, 18.dp).background(c.separator))
            Button("+", 1)
        }
    }
}
