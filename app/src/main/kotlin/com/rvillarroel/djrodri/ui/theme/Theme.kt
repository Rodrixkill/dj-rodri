package com.rvillarroel.djrodri.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Design tokens: every color in the app comes from here, never a hex literal in a screen. */
object DjColors {
    val Night = Color(0xFF0F0A1F)
    val Stage = Color(0xFF1A1033)
    val StageRaised = Color(0xFF261A47)
    val Neon = Color(0xFFB14DFF)
    val NeonSoft = Color(0xFFB9A8FF)
    val Drop = Color(0xFFFF4D8D)
    val Whole = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFB84D)
    val Text = Color(0xFFF4F0FF)
    val TextDim = Color(0xFF9C92B8)
}

private val scheme = darkColorScheme(
    primary = DjColors.Neon,
    onPrimary = Color.White,
    secondary = DjColors.Drop,
    tertiary = DjColors.Whole,
    background = DjColors.Night,
    onBackground = DjColors.Text,
    surface = DjColors.Stage,
    onSurface = DjColors.Text,
    surfaceVariant = DjColors.StageRaised,
    onSurfaceVariant = DjColors.TextDim,
    error = DjColors.Warning,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
)

/** Always dark: it is used in dim rooms at night. */
@Composable
fun DjTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = typography, content = content)
