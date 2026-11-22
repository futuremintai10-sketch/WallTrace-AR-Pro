package com.walltrace.ar.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object WtColors {
    val Background = Color(0xFF121416)
    val Surface = Color(0xFF1C1F22)
    val SurfaceHigh = Color(0xFF262A2E)
    val Overlay = Color(0xE01C1F22)
    val Teal = Color(0xFF2EC4B6)
    val TealDim = Color(0xFF1E8C82)
    val OnTeal = Color(0xFF00201D)
    val Text = Color(0xFFE6E8EA)
    val TextMuted = Color(0xFF9AA3AB)
    val Warning = Color(0xFFF2B84B)
    val Error = Color(0xFFEF6B6B)
    val Outline = Color(0xFF3A3F44)
}

private val colorScheme = darkColorScheme(
    primary = WtColors.Teal,
    onPrimary = WtColors.OnTeal,
    primaryContainer = WtColors.TealDim,
    onPrimaryContainer = Color.White,
    secondary = WtColors.Teal,
    onSecondary = WtColors.OnTeal,
    background = WtColors.Background,
    onBackground = WtColors.Text,
    surface = WtColors.Surface,
    onSurface = WtColors.Text,
    surfaceVariant = WtColors.SurfaceHigh,
    onSurfaceVariant = WtColors.TextMuted,
    surfaceContainer = WtColors.Surface,
    surfaceContainerHigh = WtColors.SurfaceHigh,
    surfaceContainerHighest = WtColors.SurfaceHigh,
    outline = WtColors.Outline,
    outlineVariant = WtColors.Outline,
    error = WtColors.Error
)

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun WallTraceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes, content = content)
}
