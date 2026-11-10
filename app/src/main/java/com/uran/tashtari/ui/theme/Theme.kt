package com.uran.tashtari.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val UranColors = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF04121A),
    secondary = Color(0xFF334155),
    onSecondary = Color.White,
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF131C2E),
    onSurface = Color(0xFFE2E8F0),
    error = Color(0xFFEF4444)
)

@Composable
fun UranTashtariTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UranColors,
        typography = Typography,
        content = content
    )
}