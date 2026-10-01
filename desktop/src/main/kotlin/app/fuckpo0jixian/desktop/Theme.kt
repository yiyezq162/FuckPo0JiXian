package app.fuckpo0jixian.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit

/** Two native looks: macOS (System Settings / Linear) and Windows 11 (Fluent, Settings / PowerToys). */
enum class Look { MAC, WINDOWS }

@Immutable class Palette(
    val window: Color, val sidebar: Color, val sidebarBorder: Color, val card: Color, val cardBorder: Color,
    val label: Color, val secondary: Color, val tertiary: Color, val separator: Color,
    val fill: Color, val hover: Color, val selected: Color, val control: Color, val controlBorder: Color,
    val accent: Color, val onAccent: Color, val green: Color, val orange: Color, val red: Color, val indigo: Color, val gray: Color,
    val dark: Boolean,
) {
    /** Tinted background for a status or tag of [tint]. */
    fun soft(tint: Color) = tint.copy(alpha = if (dark) 0.22f else 0.12f)
    /** The chick icon's yolk: the brand note, used for the one number that matters most (IP updates). */
    val yolk: Color get() = if (dark) Color(0xFFFFC23D) else Color(0xFFE39D00)
}

@Immutable class Typography(val family: FontFamily, val pageTitle: TextStyle, val title: TextStyle, val headline: TextStyle,
                            val body: TextStyle, val caption: TextStyle, val mono: TextStyle, val sectionHeader: TextStyle) {
    /** Addresses and counts: the system font with tabular figures, so columns of IPs line up. */
    val numeric: TextStyle get() = body.copy(fontFeatureSettings = "tnum")
}

@Immutable class Metrics(val radius: Dp, val controlRadius: Dp, val rowHeight: Dp, val sidebarWidth: Dp, val titleBarInset: Dp)

@Immutable class DesktopTheme(val look: Look, val colors: Palette, val type: Typography, val metrics: Metrics)

private fun macPalette(dark: Boolean, accent: Color) = if (!dark) Palette(
    window = Color(0xFFF6F6F6), sidebar = Color(0xFFECECEC), sidebarBorder = Color(0x14000000),
    card = Color.White, cardBorder = Color(0x0F000000), label = Color(0xFF1D1D1F), secondary = Color(0x8C000000),
    tertiary = Color(0x42000000), separator = Color(0x14000000), fill = Color(0x0D000000), hover = Color(0x0A000000),
    selected = Color(0x14000000), control = Color.White, controlBorder = Color(0x1F000000),
    accent = accent, onAccent = Color.White, green = Color(0xFF28CD41), orange = Color(0xFFFF9500), red = Color(0xFFFF3B30),
    indigo = Color(0xFF5856D6), gray = Color(0xFF8E8E93), dark = false)
else Palette(
    window = Color(0xFF1E1E1F), sidebar = Color(0xFF28282A), sidebarBorder = Color(0x33000000),
    card = Color(0xFF2A2A2C), cardBorder = Color(0x14FFFFFF), label = Color(0xFFF5F5F7), secondary = Color(0x99FFFFFF),
    tertiary = Color(0x47FFFFFF), separator = Color(0x14FFFFFF), fill = Color(0x14FFFFFF), hover = Color(0x0FFFFFFF),
    selected = Color(0x1FFFFFFF), control = Color(0xFF3A3A3C), controlBorder = Color(0x1AFFFFFF),
    accent = accent, onAccent = Color.White, green = Color(0xFF32D74B), orange = Color(0xFFFF9F0A), red = Color(0xFFFF453A),
    indigo = Color(0xFF5E5CE6), gray = Color(0xFF98989D), dark = true)

private fun windowsPalette(dark: Boolean, accent: Color) = if (!dark) Palette(
    window = Color(0xFFF3F3F3), sidebar = Color(0xFFF3F3F3), sidebarBorder = Color.Transparent,
    card = Color(0xFFFBFBFB), cardBorder = Color(0x0F000000), label = Color(0xE4000000), secondary = Color(0x9E000000),
    tertiary = Color(0x5C000000), separator = Color(0x0F000000), fill = Color(0x06000000), hover = Color(0x09000000),
    selected = Color(0x0F000000), control = Color(0xB3FFFFFF), controlBorder = Color(0x24000000),
    accent = accent, onAccent = Color.White, green = Color(0xFF0F7B0F), orange = Color(0xFF9D5D00), red = Color(0xFFC42B1C),
    indigo = Color(0xFF6B69D6), gray = Color(0xFF8A8A8A), dark = false)
else Palette(
    window = Color(0xFF202020), sidebar = Color(0xFF202020), sidebarBorder = Color.Transparent,
    card = Color(0xFF2B2B2B), cardBorder = Color(0x19000000), label = Color(0xFFFFFFFF), secondary = Color(0xC5FFFFFF),
    tertiary = Color(0x5DFFFFFF), separator = Color(0x15FFFFFF), fill = Color(0x0BFFFFFF), hover = Color(0x0FFFFFFF),
    selected = Color(0x0FFFFFFF), control = Color(0x0FFFFFFF), controlBorder = Color(0x17FFFFFF),
    accent = accent, onAccent = Color.Black, green = Color(0xFF6CCB5F), orange = Color(0xFFFFB454), red = Color(0xFFFF99A4),
    indigo = Color(0xFF8E8CD8), gray = Color(0xFF9D9D9D), dark = true)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun typography(look: Look): Typography {
    val family = if (look == Look.WINDOWS) FontFamily(
        // Latin in Segoe UI Variable; Chinese falls back to Microsoft YaHei UI through the system font fallback.
        SystemFont("Segoe UI Variable Text", FontWeight.Normal), SystemFont("Segoe UI Variable Text", FontWeight.Medium),
        SystemFont("Segoe UI Variable Text", FontWeight.SemiBold), SystemFont("Segoe UI Variable Display", FontWeight.Bold))
        else FontFamily.Default // SF Pro with PingFang SC
    fun t(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = size + 5) =
        TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)
    return if (look == Look.WINDOWS) Typography(family, pageTitle = t(28, FontWeight.SemiBold, 36), title = t(20, FontWeight.SemiBold, 28),
        headline = t(14, FontWeight.SemiBold, 20), body = t(14, line = 20), caption = t(12, line = 16),
        mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp), sectionHeader = t(14, FontWeight.SemiBold, 20))
    else Typography(family, pageTitle = t(22, FontWeight.Bold, 28), title = t(17, FontWeight.SemiBold, 22),
        headline = t(13, FontWeight.SemiBold, 18), body = t(13, line = 18), caption = t(11, line = 14),
        mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp), sectionHeader = t(13, FontWeight.SemiBold, 18))
}

fun desktopTheme(look: Look, dark: Boolean, accent: Color? = null): DesktopTheme {
    val tint = accent ?: if (look == Look.WINDOWS) (if (dark) Color(0xFF4CC2FF) else Color(0xFF005FB8)) else (if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF))
    return DesktopTheme(look, if (look == Look.MAC) macPalette(dark, tint) else windowsPalette(dark, tint), typography(look),
        if (look == Look.MAC) Metrics(radius = 10.dp, controlRadius = 6.dp, rowHeight = 40.dp, sidebarWidth = 208.dp, titleBarInset = 38.dp)
        else Metrics(radius = 7.dp, controlRadius = 4.dp, rowHeight = 48.dp, sidebarWidth = 224.dp, titleBarInset = 0.dp))
}

val LocalTheme = staticCompositionLocalOf { desktopTheme(Look.MAC, false) }
val theme: DesktopTheme @Composable @ReadOnlyComposable get() = LocalTheme.current
val colors: Palette @Composable @ReadOnlyComposable get() = LocalTheme.current.colors
val Type: Typography @Composable @ReadOnlyComposable get() = LocalTheme.current.type

/** System appearance: dark mode and accent colour, read from the OS and refreshed while the app runs. */
object SystemAppearance {
    val look = if (os == Os.WINDOWS) Look.WINDOWS else Look.MAC

    private fun run(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else p.inputStream.bufferedReader().readText().trim().takeIf { p.exitValue() == 0 }
    }.getOrNull()

    /** Windows: "apps use light theme" = 0 means dark. Other systems use Compose's own detection. */
    fun windowsDark(): Boolean? = if (os != Os.WINDOWS) null else runCatching {
        com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "AppsUseLightTheme") == 0
    }.getOrNull()

    /** The accent colour people picked in System Settings / Windows personalization. */
    fun accent(dark: Boolean): Color? = when (os) {
        Os.WINDOWS -> runCatching {
            // AccentPalette holds 8 ABGR-ish RGBA shades; index 1 (light 2) suits dark mode, 4/5 (dark 1/2) suit light mode.
            val palette = com.sun.jna.platform.win32.Advapi32Util.registryGetBinaryValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Accent", "AccentPalette")
            val i = if (dark) 1 else 4
            Color(palette[i * 4].toInt() and 0xFF, palette[i * 4 + 1].toInt() and 0xFF, palette[i * 4 + 2].toInt() and 0xFF)
        }.getOrNull()
        Os.MAC -> when (run("/usr/bin/defaults", "read", "-g", "AppleAccentColor")?.toIntOrNull()) {
            -1 -> Color(0xFF8C8C8C); 0 -> Color(0xFFFF3B30); 1 -> Color(0xFFFF9500); 2 -> Color(0xFFFFCC00)
            3 -> Color(0xFF28CD41); 5 -> Color(0xFFAF52DE); 6 -> Color(0xFFFF2D55)
            else -> null // blue (the default, or "multicolour")
        }
        Os.OTHER -> null
    }
}
