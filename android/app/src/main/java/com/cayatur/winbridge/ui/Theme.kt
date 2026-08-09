package com.cayatur.winbridge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Accent = Color(0xFF6E56CF)

private val DarkColors = darkColorScheme(
    primary = Accent,
    secondary = Color(0xFF7C8CF8),
    background = Color(0xFF121216),
    surface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFF26262E),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    secondary = Color(0xFF4F5BD5),
    background = Color(0xFFF7F6FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEBF4),
)

@Composable
fun WinBridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current

    // Material You where the platform offers it; the fixed palette otherwise.
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
