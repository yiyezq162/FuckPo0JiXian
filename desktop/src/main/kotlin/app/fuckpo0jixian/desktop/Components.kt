package app.fuckpo0jixian.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Same grouped-list look as the Android app. */
class Palette(val background: Color, val card: Color, val label: Color, val secondary: Color, val tertiary: Color,
              val separator: Color, val fill: Color, val accent: Color, val green: Color, val orange: Color, val red: Color, val gray: Color)
val lightPalette = Palette(Color(0xFFF2F2F7), Color.White, Color(0xFF000000), Color(0x993C3C43), Color(0x4D3C3C43),
    Color(0x333C3C43), Color(0x1F787880), Color(0xFF007AFF), Color(0xFF34C759), Color(0xFFFF9500), Color(0xFFFF3B30), Color(0xFF8E8E93))
val darkPalette = Palette(Color(0xFF000000), Color(0xFF1C1C1E), Color.White, Color(0x99EBEBF5), Color(0x4DEBEBF5),
    Color(0x54545458), Color(0x3D787880), Color(0xFF0A84FF), Color(0xFF30D158), Color(0xFFFF9F0A), Color(0xFFFF453A), Color(0xFF8E8E93))
val LocalPalette = staticCompositionLocalOf { lightPalette }
val colors: Palette @Composable get() = LocalPalette.current

object Type {
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp)
    val footnote = TextStyle(fontSize = 12.sp)
}

@Composable fun Section(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        header?.let { Text(it, style = Type.footnote, color = colors.secondary, modifier = Modifier.padding(start = 14.dp)) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.card), content = content)
        footer?.let { Text(it, style = Type.footnote, color = colors.secondary, modifier = Modifier.padding(horizontal = 14.dp)) }
    }
}

@Composable fun Hairline() = Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(0.5.dp).background(colors.separator))

@Composable fun ListRow(title: String, value: String? = null, subtitle: String? = null, titleColor: Color = colors.label,
                    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
                    enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
        .heightIn(min = 42.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.body, color = if (enabled) titleColor else colors.tertiary)
            subtitle?.let { Text(it, style = Type.footnote, color = colors.secondary) }
        }
        value?.let { Text(it, style = Type.body, color = colors.secondary, textAlign = TextAlign.End, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 230.dp)) }
        trailing?.invoke()
        if (onClick != null && trailing == null && value != null) Text("›", style = Type.headline, color = colors.tertiary)
    }
}

@Composable fun Action(text: String, color: Color = colors.accent, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 42.dp).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart) { Text(text, style = Type.body, color = if (enabled) color else colors.tertiary) }
}

@Composable fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) =
    ListRow(title, subtitle = subtitle, enabled = enabled, trailing = {
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = colors.green,
            checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent))
    })

@Composable fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).clip(RoundedCornerShape(8.dp)).background(colors.fill).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(6.dp)).background(if (on) colors.card else Color.Transparent)
                .clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(label, style = Type.footnote.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = colors.label)
            }
        }
    }
}

@Composable fun Field(value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.fill).padding(horizontal = 10.dp, vertical = 9.dp)) {
        if (value.isEmpty()) Text(placeholder, style = Type.body, color = colors.tertiary)
        BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, textStyle = Type.body.copy(color = colors.label),
            cursorBrush = SolidColor(colors.accent), visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
    }
}

@Composable fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, tinted: Boolean = false, modifier: Modifier = Modifier) {
    val bg = when { !enabled -> colors.fill; tinted -> colors.accent.copy(alpha = 0.14f); else -> colors.accent }
    Box(modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, style = Type.headline, color = when { !enabled -> colors.tertiary; tinted -> colors.accent; else -> Color.White })
    }
}

@Composable fun Badge(label: String, tint: Color) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(tint), contentAlignment = Alignment.Center) {
        Text(label, style = Type.headline, color = Color.White)
    }
}

@Composable fun Dot(tint: Color) = Box(Modifier.size(7.dp).background(tint, CircleShape))

@Composable fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, style = Type.footnote, color = if (selected) Color.White else colors.accent,
        modifier = Modifier.clip(CircleShape).background(if (selected) colors.accent else colors.accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp))
}

class DialogAction(val text: String, val preferred: Boolean = false, val destructive: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

@Composable fun Alert(title: String, onDismiss: () -> Unit, actions: List<DialogAction>, message: String? = null,
                      content: (@Composable ColumnScope.() -> Unit)? = null) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(320.dp).clip(RoundedCornerShape(14.dp)).background(colors.card)) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = Type.headline, color = colors.label, textAlign = TextAlign.Center)
                message?.let { Text(it, style = Type.footnote, color = colors.label, textAlign = TextAlign.Center) }
                content?.invoke(this)
            }
            actions.forEach { a ->
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                Box(Modifier.fillMaxWidth().clickable(enabled = a.enabled, onClick = a.onClick).height(42.dp), contentAlignment = Alignment.Center) {
                    Text(a.text, style = Type.body.copy(fontWeight = if (a.preferred) FontWeight.SemiBold else FontWeight.Normal),
                        color = when { !a.enabled -> colors.tertiary; a.destructive -> colors.red; else -> colors.accent })
                }
            }
        }
    }
}
