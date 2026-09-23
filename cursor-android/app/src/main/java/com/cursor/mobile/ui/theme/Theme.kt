package com.cursor.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CursorPurple = Color(0xFF7C6AFE)
private val CursorPurpleSoft = Color(0xFF9B8CFF)
private val CursorBg = Color(0xFF0B0B0F)
private val CursorSurface = Color(0xFF16161D)
private val CursorSurfaceVariant = Color(0xFF22222C)
private val CursorOnBg = Color(0xFFEDEDF2)
private val CursorMuted = Color(0xFFA0A0B2)
private val CursorError = Color(0xFFFF6B7A)
private val CursorSuccess = Color(0xFF4ADE80)

private val DarkColors = darkColorScheme(
    primary = CursorPurple,
    onPrimary = Color.White,
    secondary = CursorPurpleSoft,
    background = CursorBg,
    onBackground = CursorOnBg,
    surface = CursorSurface,
    onSurface = CursorOnBg,
    surfaceVariant = CursorSurfaceVariant,
    onSurfaceVariant = CursorMuted,
    error = CursorError,
    onError = Color.White,
    outline = Color(0xFF3A3A48)
)

private val LightColors = lightColorScheme(
    primary = CursorPurple,
    onPrimary = Color.White,
    secondary = CursorPurpleSoft,
    background = Color(0xFFF7F7FA),
    onBackground = Color(0xFF16161D),
    surface = Color.White,
    onSurface = Color(0xFF16161D),
    surfaceVariant = Color(0xFFEEEEF5),
    onSurfaceVariant = Color(0xFF5C5C6E),
    error = CursorError,
    onError = Color.White,
    outline = Color(0xFFD0D0DC)
)

val SuccessGreen = CursorSuccess

@Composable
fun CursorTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme || isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
