package com.cursor.mobile.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IosBlue = Color(0xFF007AFF)
private val CursorInk = Color(0xFF0A0A0B)

private val LightScheme = lightColorScheme(
    primary = IosBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F1FF),
    secondaryContainer = Color(0xFFF2F2F7),
    surface = Color.White,
    surfaceContainerLow = Color(0xFFF7F7FA),
    background = Color(0xFFF2F2F7),
    onBackground = CursorInk
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1C2A44),
    secondaryContainer = Color(0xFF1C1C1E),
    surface = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF1C1C1E),
    background = Color.Black,
    onBackground = Color(0xFFF5F5F7)
)

@Composable
fun CursorMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
