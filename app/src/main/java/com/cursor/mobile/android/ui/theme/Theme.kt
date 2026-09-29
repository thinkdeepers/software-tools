package com.cursor.mobile.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

object CursorPalette {
    val NeonBlue = Color(0xFF5B8CFF)
    val NeonViolet = Color(0xFF8B5CF6)
    val NeonCyan = Color(0xFF22D3EE)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFFB7185)

    val DeepSpace = Color(0xFF05070F)
    val DarkSurface = Color(0xFF0B1120)
    val DarkCard = Color(0xFF0E1528)
    val DarkCardHigh = Color(0xFF131C33)
    val DarkBorder = Color(0xFF22304F)
    val DarkText = Color(0xFFE9EEF8)
    val DarkMuted = Color(0xFF8E98B3)

    val LightBg = Color(0xFFF1F4FA)
    val LightBorder = Color(0xFFE2E7F3)
    val LightInk = Color(0xFF0B1020)
    val LightMuted = Color(0xFF5B6478)
    val BrandBlue = Color(0xFF3B63F2)
    val BrandViolet = Color(0xFF7C3AED)
}

fun accentGradient(): Brush = Brush.linearGradient(
    colors = listOf(CursorPalette.NeonBlue, CursorPalette.NeonViolet, CursorPalette.NeonCyan),
    start = Offset(0f, 0f),
    end = Offset(600f, 0f)
)

private val DarkScheme = darkColorScheme(
    primary = CursorPalette.NeonBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF16224A),
    onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = CursorPalette.NeonViolet,
    tertiary = CursorPalette.NeonCyan,
    surface = CursorPalette.DarkSurface,
    onSurface = CursorPalette.DarkText,
    surfaceVariant = CursorPalette.DarkCard,
    onSurfaceVariant = CursorPalette.DarkMuted,
    surfaceContainerLow = CursorPalette.DarkCard,
    surfaceContainer = CursorPalette.DarkCardHigh,
    background = CursorPalette.DeepSpace,
    onBackground = CursorPalette.DarkText,
    outline = CursorPalette.DarkBorder,
    outlineVariant = Color(0xFF16203A),
    error = CursorPalette.Danger
)

private val LightScheme = lightColorScheme(
    primary = CursorPalette.BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EBFF),
    onPrimaryContainer = Color(0xFF1B2F7A),
    secondary = CursorPalette.BrandViolet,
    tertiary = Color(0xFF0E7490),
    surface = Color.White,
    onSurface = CursorPalette.LightInk,
    surfaceVariant = Color.White,
    onSurfaceVariant = CursorPalette.LightMuted,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEAF0FB),
    background = CursorPalette.LightBg,
    onBackground = CursorPalette.LightInk,
    outline = CursorPalette.LightBorder,
    outlineVariant = Color(0xFFEDF1F8),
    error = Color(0xFFE11D48)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.3).sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.5.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.5.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.3.sp)
)

val CodeFont = FontFamily.Monospace

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun CursorMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
