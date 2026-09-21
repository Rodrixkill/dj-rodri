package com.rvillarroel.djrodri.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design system. Screens never use a hex color, a raw dp or a font size: they ask [Dj] for a token.
 *
 * Identity: black, white and neutrals carry the interface; red is the accent, used for the one primary action on a
 * screen, the active state and the current selection. Album artwork brings every other color.
 */
@Immutable
data class DjTokens(
    val background: Color,
    val surface: Color,
    val surfaceSecondary: Color,
    val surfaceTertiary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val border: Color,
    /** Primary action, active tab, current selection. */
    val accent: Color,
    /** Pressed and secondary emphasis. */
    val accentSecondary: Color,
    /** Accent for small things that need to read brighter on dark. */
    val accentBright: Color,
    val onAccent: Color,
    val progressActive: Color,
    val progressInactive: Color,
    val danger: Color,
    val success: Color,
    /** Placeholder shade for skeletons. */
    val skeleton: Color,
    val isDark: Boolean,
)

private object Brand {
    val Red = Color(0xFFE50914)
    val RedDeep = Color(0xFFB20710)
    val RedBright = Color(0xFFFF3B45)
}

val DarkTokens = DjTokens(
    background = Color(0xFF08090A),
    surface = Color(0xFF121416),
    surfaceSecondary = Color(0xFF1A1D20),
    surfaceTertiary = Color(0xFF22262A),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA7A9AD),
    textTertiary = Color(0xFF6F7378),
    border = Color(0xFF24282C),
    accent = Brand.Red,
    accentSecondary = Brand.RedDeep,
    accentBright = Brand.RedBright,
    onAccent = Color.White,
    progressActive = Brand.Red,
    progressInactive = Color(0xFF2E3236),
    danger = Color(0xFFFF6B5E),
    success = Color(0xFF3CCB7F),
    skeleton = Color(0xFF1C1F22),
    isDark = true,
)

val LightTokens = DjTokens(
    background = Color(0xFFF7F7F7),
    surface = Color(0xFFFFFFFF),
    surfaceSecondary = Color(0xFFEEEEEE),
    surfaceTertiary = Color(0xFFE2E2E2),
    textPrimary = Color(0xFF111111),
    textSecondary = Color(0xFF666666),
    textTertiary = Color(0xFF888888),
    border = Color(0xFFE4E4E4),
    accent = Brand.Red,
    accentSecondary = Brand.RedDeep,
    accentBright = Brand.RedDeep,
    onAccent = Color.White,
    progressActive = Brand.Red,
    progressInactive = Color(0xFFDADADA),
    danger = Color(0xFFC62828),
    success = Color(0xFF1E8E4E),
    skeleton = Color(0xFFE9E9E9),
    isDark = false,
)

/** Spacing scale: nothing is placed on a value outside it. */
object Space {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp
    val huge = 48.dp

    /** Side margin of every screen. */
    val gutter = 20.dp
}

/** Small for controls, medium for cards, large for major artwork. Pills only for chips. */
object Radius {
    val sm = 6.dp
    val md = 10.dp
    val lg = 16.dp
    val xl = 24.dp
}

object IconSize {
    val sm = 18.dp
    val md = 22.dp
    val lg = 28.dp
    val xl = 36.dp
}

/** Motion is fast and quiet: nothing bounces. */
object Motion {
    const val FAST = 150
    const val BASE = 250
    const val SLOW = 400
}

/** The system sans (Roboto on this phone): tight, legible, one family for everything. */
private val Sans = FontFamily.SansSerif

object DjType {
    val display = TextStyle(fontFamily = Sans, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
    val pageTitle = TextStyle(fontFamily = Sans, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val section = TextStyle(fontFamily = Sans, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val cardTitle = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)
    val meta = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
    val button = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)

    /** BPM, key, times: tabular figures so numbers do not jitter while they change. */
    val technical = TextStyle(
        fontFamily = Sans, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum", letterSpacing = 0.3.sp,
    )
    val technicalSmall = TextStyle(fontFamily = Sans, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
}

enum class ThemeMode(val label: String) { SYSTEM("Sistema"), DARK("Oscuro"), LIGHT("Claro") }

private val LocalDjTokens = staticCompositionLocalOf { DarkTokens }

/** How screens read the design system: `Dj.colors.accent`. */
object Dj {
    val colors: DjTokens
        @Composable get() = LocalDjTokens.current
}

@Composable
fun DjTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val target = if (dark) DarkTokens else LightTokens
    // The whole app crossfades between themes instead of flashing.
    val tokens = target.copy(
        background = animate(target.background),
        surface = animate(target.surface),
        surfaceSecondary = animate(target.surfaceSecondary),
        surfaceTertiary = animate(target.surfaceTertiary),
        textPrimary = animate(target.textPrimary),
        textSecondary = animate(target.textSecondary),
        textTertiary = animate(target.textTertiary),
        border = animate(target.border),
        progressInactive = animate(target.progressInactive),
        skeleton = animate(target.skeleton),
    )
    val scheme = if (dark) {
        darkColorScheme(
            primary = tokens.accent, onPrimary = tokens.onAccent, secondary = tokens.accentSecondary,
            background = tokens.background, onBackground = tokens.textPrimary, surface = tokens.surface,
            onSurface = tokens.textPrimary, surfaceVariant = tokens.surfaceSecondary, onSurfaceVariant = tokens.textSecondary,
            surfaceContainerHigh = tokens.surfaceSecondary, surfaceContainerHighest = tokens.surfaceTertiary,
            outline = tokens.border, outlineVariant = tokens.border, error = tokens.danger,
        )
    } else {
        lightColorScheme(
            primary = tokens.accent, onPrimary = tokens.onAccent, secondary = tokens.accentSecondary,
            background = tokens.background, onBackground = tokens.textPrimary, surface = tokens.surface,
            onSurface = tokens.textPrimary, surfaceVariant = tokens.surfaceSecondary, onSurfaceVariant = tokens.textSecondary,
            surfaceContainerHigh = tokens.surface, surfaceContainerHighest = tokens.surfaceSecondary,
            outline = tokens.border, outlineVariant = tokens.border, error = tokens.danger,
        )
    }
    val typography = Typography(
        displaySmall = DjType.display,
        headlineSmall = DjType.pageTitle,
        titleLarge = DjType.section,
        titleMedium = DjType.cardTitle,
        bodyLarge = DjType.body,
        bodyMedium = DjType.meta,
        labelLarge = DjType.button,
        labelMedium = DjType.label,
    )
    // Status and navigation bar icons follow the app's theme, not the phone's: dark icons on the light theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalDjTokens provides tokens) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

@Composable
private fun animate(color: Color): Color = animateColorAsState(color, tween(Motion.SLOW), label = "theme").value
