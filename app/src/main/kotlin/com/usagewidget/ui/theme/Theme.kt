package com.usagewidget.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = Color(0xFF062033),
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = CursorText,
    secondary = secondaryDark,
    tertiary = tertiaryDark,
    background = CursorChrome,
    onBackground = CursorText,
    surface = CursorSurface,
    onSurface = CursorText,
    surfaceVariant = CursorSurfaceHigh,
    onSurfaceVariant = CursorMuted,
    outline = CursorOutline,
    error = CursorBarWarn
)

@Composable
fun UsageWidgetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
