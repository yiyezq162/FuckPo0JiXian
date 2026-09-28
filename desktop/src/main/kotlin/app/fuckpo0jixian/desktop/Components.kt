package app.fuckpo0jixian.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable fun Modifier.hoverBackground(enabled: Boolean = true, shape: RoundedCornerShape? = null, onClick: (() -> Unit)?): Modifier {
    if (onClick == null) return this
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val bg = when { !enabled -> Color.Transparent; pressed -> colors.selected; hovered -> colors.hover; else -> Color.Transparent }
    return (if (shape != null) clip(shape) else this).background(bg)
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

/** Rounded surface for a group of rows: white-on-gray on macOS, a Fluent card layer on Windows. */
@Composable fun Card(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(0.dp), content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(theme.metrics.radius)
    Column(modifier.fillMaxWidth().clip(shape).background(colors.card).border(0.5.dp, colors.cardBorder, shape).padding(padding), content = content)
}

@Composable fun Group(header: String? = null, footer: String? = null, modifier: Modifier = Modifier,
                      action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header != null || action != null) Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            header?.let { Text(it, style = Type.sectionHeader, color = colors.label, modifier = Modifier.weight(1f)) }
            action?.invoke()
        }
        Card(content = content)
        footer?.let { Text(it, style = Type.caption, color = colors.secondary, modifier = Modifier.padding(horizontal = 2.dp)) }
    }
}

@Composable fun Divider(inset: Dp = 14.dp) = Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(colors.separator))

/** Icon in a small tile (macOS System Settings) or bare (Windows Settings). */
@Composable fun RowIcon(icon: ImageVector, tint: Color) {
    if (theme.look == Look.MAC) Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(tint), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
    } else Icon(icon, null, tint = colors.label, modifier = Modifier.size(18.dp))
}

@Composable fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, iconTint: Color = colors.accent,
                           titleColor: Color = colors.label, enabled: Boolean = true, onClick: (() -> Unit)? = null,
                           trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().hoverBackground(enabled, onClick = onClick).heightIn(min = theme.metrics.rowHeight)
        .padding(horizontal = 14.dp, vertical = 8.dp).alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.let { RowIcon(it, iconTint) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Type.body, color = titleColor)
            subtitle?.let { Text(it, style = Type.caption, color = colors.secondary) }
        }
        trailing?.invoke(this)
        if (onClick != null && trailing == null) Icon(Glyph.Chevron, null, tint = colors.tertiary, modifier = Modifier.size(16.dp))
    }
}

/** Label above a full-width control; for forms in narrow panes. */
@Composable fun FieldRow(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Type.caption.copy(fontWeight = FontWeight.Medium), color = colors.secondary)
        content()
    }
}

@Composable fun ValueRow(title: String, value: String, subtitle: String? = null, icon: ImageVector? = null, iconTint: Color = colors.accent,
                         valueColor: Color = colors.secondary, mono: Boolean = false) =
    SettingRow(title, subtitle, icon, iconTint) {
        Text(value, style = if (mono) Type.numeric else Type.body, color = valueColor,
            textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
    }

@Composable fun Switch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = colors
    val mac = theme.look == Look.MAC
    val width = if (mac) 32.dp else 40.dp
    val height = if (mac) 18.dp else 20.dp
    val knob = if (mac) 16.dp else if (checked) 12.dp else 10.dp
    val travel = width - knob - (if (mac) 2.dp else 8.dp)
    val offset by animateDpAsState(if (checked) travel else 0.dp, spring(dampingRatio = 0.75f, stiffness = 700f), label = "knob")
    val track by animateColorAsState(if (checked) c.accent else if (mac) c.fill.copy(alpha = if (c.dark) 0.25f else 0.12f) else Color.Transparent, label = "track")
    Box(Modifier.size(width, height).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).background(track)
        .then(if (!mac && !checked) Modifier.border(1.dp, c.secondary, CircleShape) else Modifier)
        .clickable(enabled = enabled, role = Role.Switch, interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!checked) }
        .padding(if (mac) 1.dp else 4.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.offset(x = offset).size(knob).then(if (mac) Modifier.shadow(1.dp, CircleShape) else Modifier)
            .background(if (mac || checked) (if (!mac) c.onAccent else Color.White) else c.secondary, CircleShape))
    }
}

@Composable fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null,
                          icon: ImageVector? = null, iconTint: Color = colors.accent, enabled: Boolean = true) =
    SettingRow(title, subtitle, icon, iconTint, enabled = enabled) { Switch(checked, onChange, enabled) }

enum class ButtonKind { PRIMARY, SECONDARY, SUBTLE, DESTRUCTIVE }

@Composable fun Button(text: String, onClick: () -> Unit, kind: ButtonKind = ButtonKind.SECONDARY, icon: ImageVector? = null,
                       enabled: Boolean = true, modifier: Modifier = Modifier, loading: Boolean = false) {
    val c = colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(theme.metrics.controlRadius)
    val (bg, fg) = when (kind) {
        ButtonKind.PRIMARY -> c.accent to c.onAccent
        ButtonKind.SECONDARY -> c.control to c.label
        ButtonKind.SUBTLE -> Color.Transparent to c.accent
        ButtonKind.DESTRUCTIVE -> c.control to c.red
    }
    val overlay = when { !enabled -> Color.Transparent; pressed -> Color.Black.copy(alpha = 0.12f); hovered -> (if (kind == ButtonKind.PRIMARY) Color.White else Color.Black).copy(alpha = if (c.dark) 0.08f else 0.05f); else -> Color.Transparent }
    Row(modifier.height(if (theme.look == Look.MAC) 28.dp else 32.dp).alpha(if (enabled) 1f else 0.4f)
        .then(if (kind != ButtonKind.SUBTLE && kind != ButtonKind.PRIMARY) Modifier.shadow(if (theme.look == Look.MAC && !c.dark) 0.5.dp else 0.dp, shape) else Modifier)
        .clip(shape).background(bg).background(overlay)
        .then(if (kind == ButtonKind.SECONDARY || kind == ButtonKind.DESTRUCTIVE) Modifier.border(0.5.dp, c.controlBorder, shape) else Modifier)
        .clickable(source, indication = null, enabled = enabled && !loading, role = Role.Button, onClick = onClick)
        .padding(horizontal = if (kind == ButtonKind.SUBTLE) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        if (loading) Spinner(fg) else icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(15.dp)) }
        Text(text, style = Type.body.copy(fontWeight = if (kind == ButtonKind.PRIMARY) FontWeight.Medium else FontWeight.Normal), color = fg, maxLines = 1)
    }
}

@Composable fun Spinner(tint: Color, size: Dp = 13.dp) {
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Icon(Glyph.Sync, null, tint = tint, modifier = Modifier.size(size).graphicsLayer { rotationZ = -angle })
}

@Composable fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val c = colors
    val shape = RoundedCornerShape(theme.metrics.controlRadius + 1.dp)
    Row(modifier.height(if (theme.look == Look.MAC) 26.dp else 32.dp).clip(shape).background(c.fill).padding(2.dp).alpha(if (enabled) 1f else 0.45f)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val inner = RoundedCornerShape(theme.metrics.controlRadius)
            Box(Modifier.weight(1f).fillMaxHeight()
                .then(if (on) Modifier.shadow(if (c.dark) 0.dp else 1.dp, inner).background(if (c.dark) c.selected.copy(alpha = 0.35f) else c.card, inner) else Modifier)
                .clip(inner).clickable(enabled = enabled, role = Role.Tab) { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(label, style = Type.body.copy(fontWeight = if (on) FontWeight.Medium else FontWeight.Normal), color = if (on) c.label else c.secondary, maxLines = 1)
                if (on && theme.look == Look.WINDOWS) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp).size(14.dp, 3.dp).clip(CircleShape).background(c.accent))
            }
        }
    }
}

@Composable fun TextField(value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false, modifier: Modifier = Modifier) {
    val c = colors
    val shape = RoundedCornerShape(theme.metrics.controlRadius)
    val source = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().clip(shape).background(c.control).border(if (focused) 1.5.dp else 0.5.dp, if (focused) c.accent else c.controlBorder, shape)
        .padding(horizontal = 10.dp, vertical = if (theme.look == Look.MAC) 6.dp else 8.dp)) {
        if (value.isEmpty()) Text(placeholder, style = Type.body, color = c.tertiary)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, singleLine = true, textStyle = Type.body.copy(color = c.label),
            cursorBrush = SolidColor(c.accent), interactionSource = source,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
    }
}

/** Small rounded label: who manages a slot, what a slot is for. */
@Composable fun Tag(text: String, tint: Color, icon: ImageVector? = null, strong: Boolean = false) {
    Row(Modifier.clip(RoundedCornerShape(5.dp)).background(if (strong) tint else colors.soft(tint)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        icon?.let { Icon(it, null, tint = if (strong) Color.White else tint, modifier = Modifier.size(11.dp)) }
        Text(text, style = Type.caption.copy(fontWeight = FontWeight.Medium), color = if (strong) Color.White else tint, maxLines = 1)
    }
}

@Composable fun Dot(tint: Color, size: Dp = 7.dp) = Box(Modifier.size(size).background(tint, CircleShape))

/** Status dot with a soft pulse while something is running. */
@Composable fun PulseDot(tint: Color, pulsing: Boolean, size: Dp = 10.dp) {
    val scale by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 2.4f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "s")
    Box(Modifier.size(size * 2.4f), contentAlignment = Alignment.Center) {
        if (pulsing) Box(Modifier.size(size * scale).alpha(((2.4f - scale) / 1.4f).coerceIn(0f, 0.5f)).background(tint, CircleShape))
        Box(Modifier.size(size).background(tint, CircleShape))
    }
}

@Composable fun Badge(label: String, tint: Color, size: Dp = 26.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(if (theme.look == Look.MAC) 7.dp else 5.dp)).background(tint), contentAlignment = Alignment.Center) {
        Text(label, style = Type.headline, color = Color.White)
    }
}

@Composable fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val c = colors
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.fill)) {
        if (fraction != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(c.accent))
        else {
            val x by rememberInfiniteTransition(label = "busy").animateFloat(-0.3f, 1f, infiniteRepeatable(tween(1100)), label = "x")
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(Modifier.offset(x = maxWidth * x).fillMaxWidth(0.3f).fillMaxHeight().clip(CircleShape).background(c.accent))
            }
        }
    }
}

/**
 * Stepper with − / +; holding repeats. The value is committed once the taps stop, so a long run of steps saves
 * (and reschedules) only once.
 */
@Composable fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit, label: (Int) -> String = { "$it" }) {
    val c = colors
    var draft by remember(value) { mutableIntStateOf(value) }
    val commit by rememberUpdatedState(onChange)
    LaunchedEffect(draft) { if (draft != value) { delay(700); commit(draft) } }
    @Composable fun Step(text: String, step: Int) {
        val enabled = draft + step in range
        Box(Modifier.size(28.dp, 24.dp).alpha(if (enabled) 1f else 0.3f).pointerInput(step) {
            detectTapGestures(onPress = {
                coroutineScope {
                    val repeat = launch {
                        draft = (draft + step).coerceIn(range); delay(420)
                        while (true) { draft = (draft + step).coerceIn(range); delay(70) }
                    }
                    tryAwaitRelease(); repeat.cancel()
                }
            })
        }, contentAlignment = Alignment.Center) { Text(text, style = Type.title.copy(fontWeight = FontWeight.Normal), color = c.label) }
    }
    val shape = RoundedCornerShape(theme.metrics.controlRadius)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label(draft), style = Type.body, color = c.secondary)
        Row(Modifier.clip(shape).background(c.control).border(0.5.dp, c.controlBorder, shape), verticalAlignment = Alignment.CenterVertically) {
            Step("−", -1); Box(Modifier.size(0.5.dp, 14.dp).background(c.separator)); Step("+", 1)
        }
    }
}

/** A row that opens to show more; used to fold long lists and diagnostics. */
@Composable fun Expander(title: String, subtitle: String? = null, icon: ImageVector? = null, iconTint: Color = colors.gray,
                         initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val rotation by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        SettingRow(title, subtitle, icon, iconTint, onClick = { open = !open }) {
            Icon(Glyph.Chevron, null, tint = colors.tertiary, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation })
        }
        if (open) Column(content = content)
    }
}

class DialogAction(val text: String, val preferred: Boolean = false, val destructive: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/** macOS alert (centred, stacked title) or Windows ContentDialog (left title, button strip). */
@Composable fun Alert(title: String, onDismiss: () -> Unit, actions: List<DialogAction>, message: String? = null,
                      content: (@Composable ColumnScope.() -> Unit)? = null) {
    val c = colors
    val mac = theme.look == Look.MAC
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(if (mac) 12.dp else 8.dp)
        Column(Modifier.width(if (mac) 300.dp else 420.dp).shadow(24.dp, shape).clip(shape).background(if (mac) c.card else c.window)
            .border(0.5.dp, c.cardBorder, shape)) {
            Column(Modifier.padding(if (mac) 20.dp else 24.dp), horizontalAlignment = if (mac) Alignment.CenterHorizontally else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = if (mac) Type.headline else Type.title, color = c.label, textAlign = if (mac) TextAlign.Center else TextAlign.Start)
                message?.let { Text(it, style = Type.body, color = c.secondary, textAlign = if (mac) TextAlign.Center else TextAlign.Start) }
                content?.invoke(this)
            }
            // Primary first: on top in a macOS alert, leftmost in a Windows dialog.
            val ordered = actions.sortedBy { !it.preferred }
            if (mac) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ordered.forEach { a -> Button(a.text, a.onClick, if (a.preferred) ButtonKind.PRIMARY else if (a.destructive) ButtonKind.DESTRUCTIVE else ButtonKind.SECONDARY,
                    enabled = a.enabled, modifier = Modifier.fillMaxWidth()) }
            } else Row(Modifier.fillMaxWidth().background(c.card).padding(24.dp, 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ordered.forEach { a -> Button(a.text, a.onClick, if (a.preferred) ButtonKind.PRIMARY else if (a.destructive) ButtonKind.DESTRUCTIVE else ButtonKind.SECONDARY,
                    enabled = a.enabled, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** Short-lived message at the bottom of the content area. */
@Composable fun Toast(text: String, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(text) { if (text.isBlank()) return@LaunchedEffect; shown = text; visible = true; delay(2_800); visible = false }
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        val shape = RoundedCornerShape(if (theme.look == Look.MAC) 18.dp else 6.dp)
        Text(shown, style = Type.body, color = colors.label, modifier = Modifier.padding(bottom = 18.dp).shadow(14.dp, shape).clip(shape)
            .background(colors.card).border(0.5.dp, colors.cardBorder, shape).padding(horizontal = 16.dp, vertical = 9.dp))
    }
}
