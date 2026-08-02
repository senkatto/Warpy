package com.warpy.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE8E8E8),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF343434),
    onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFFD6D6D6),
    onSecondary = Color(0xFF161616),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFCFCFCF),
    onTertiary = Color(0xFF141414),
    tertiaryContainer = Color(0xFF303030),
    onTertiaryContainer = Color(0xFFEAEAEA),
    background = Color(0xFF090909),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF090909),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFB8B8B8),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF181818),
    surfaceContainerHigh = Color(0xFF202020),
    surfaceContainerHighest = Color(0xFF2A2A2A),
    outline = Color(0xFF747474),
    outlineVariant = Color(0xFF383838),
    error = Color(0xFFFFDAD6),
    errorContainer = Color(0xFF3A1F1D),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun WarpyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
