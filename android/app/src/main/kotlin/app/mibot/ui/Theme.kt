package app.mibot.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** The app's colours. One set for dark, one for light; which one is used follows the phone's setting. */
@Immutable
class Palette(
    val dark: Boolean,
    val bg: Color,
    /** Cards, bubbles from a bot, the composer. */
    val surface: Color,
    /** Something raised on a card: a pressed row, a chip. */
    val surfaceHigh: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** The ink for main buttons, and the body of a "black" bot. */
    val primary: Color,
    val onPrimary: Color,
    val bubbleUser: Color,
    val danger: Color,
    val positive: Color,
    val toggleOff: Color,
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF1C1C1E),
    surface = Color(0xFF2A2A2D),
    surfaceHigh = Color(0xFF38383C),
    border = Color(0x24FFFFFF),
    text = Color(0xFFF5F5F5),
    textSecondary = Color(0xFFA3A3A9),
    textTertiary = Color(0xFF77777D),
    primary = Color(0xFFF2F2F2),
    onPrimary = Color(0xFF16171A),
    bubbleUser = Color(0xFF3D3D42),
    danger = Color(0xFFFB4557),
    positive = Color(0xFF14C16F),
    toggleOff = Color(0xFF55555A),
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF6F6F4),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEEEEEC),
    border = Color(0x1F000000),
    text = Color(0xFF1C1C1C),
    textSecondary = Color(0xFF6B6B70),
    textTertiary = Color(0xFF9A9AA0),
    primary = Color(0xFF1C1C1C),
    onPrimary = Color(0xFFFFFFFF),
    bubbleUser = Color(0xFFE6E6E3),
    danger = Color(0xFFE72E3B),
    positive = Color(0xFF10AB61),
    toggleOff = Color(0xFFC9C9C6),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** `Mi.colors.text`, `Mi.type.body`: the look, from anywhere in the app. */
object Mi {
    val colors: Palette
        @Composable @ReadOnlyComposable get() = LocalPalette.current
    val type = Type
}

object Type {
    val display = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val heading = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 23.sp)
    val bodyStrong = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
    val caption = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp)
}

/** A bot's own colours: its body, its side of the conversation, and its buttons. */
@Immutable
class BotColors(val fill: Color, val bubble: Color, val onBubble: Color, val action: Color, val onAction: Color)

private class Named(val fill: Long, val bubble: Long, val action: Long)

private val NAMED = mapOf(
    "brown" to Named(0xFFA06B39, 0xFF86592E, 0xFF9D6937),
    "red" to Named(0xFFFB2B3E, 0xFFC82532, 0xFFE72E3B),
    "orange" to Named(0xFFFD690F, 0xFFD2550A, 0xFFEE620D),
    "yellow" to Named(0xFFF59312, 0xFFC2730B, 0xFFDA830E),
    "green" to Named(0xFF14C16F, 0xFF0D9655, 0xFF10AB61),
    "cyan" to Named(0xFF13B7A2, 0xFF0D9381, 0xFF10A693),
    "blue" to Named(0xFF1E86FC, 0xFF126FD4, 0xFF1680F3),
    "violet" to Named(0xFF8F59F9, 0xFF7442D1, 0xFF8351E4),
    "magenta" to Named(0xFFF93498, 0xFFC82779, 0xFFE52F8B),
    "gray" to Named(0xFF959595, 0xFF6E6E6E, 0xFF7A7A7A),
)

/** The colours a bot can have, in the order the picker shows them. "black" follows the theme: ink on light, light on dark. */
val BOT_COLOR_NAMES = listOf("black") + NAMED.keys

@Composable
@ReadOnlyComposable
fun botColors(name: String): BotColors {
    val p = Mi.colors
    val c = NAMED[name] ?: return BotColors(p.primary, p.bubbleUser, p.text, p.primary, p.onPrimary)
    return BotColors(Color(c.fill), Color(c.bubble), Color.White, Color(c.action), Color.White)
}

/** The colour of a bot's body, for drawing outside Compose. */
fun botFill(name: String, dark: Boolean): Color = NAMED[name]?.let { Color(it.fill) } ?: (if (dark) DarkPalette else LightPalette).primary

/** True for a colour dark enough that light things drawn on it stand out. */
val Color.isDark get() = luminance() < 0.35f

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
fun MiBotTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    // The clock and icons at the top of the phone: dark on the light theme, light on the dark one.
    val view = LocalView.current
    SideEffect {
        val window = view.context.activity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !p.dark
            isAppearanceLightNavigationBars = !p.dark
        }
    }
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh, onSurfaceVariant = p.textSecondary, outline = p.border, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh, onSurfaceVariant = p.textSecondary, outline = p.border, error = p.danger,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalPalette provides p, LocalContentColor provides p.text, content = content)
    }
}
