package com.luxiaoshi.jianbo.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    secondary = Color(0xFF5B5F97),
    background = Color(0xFFF7F7FB),
    surface = Color.White,
    onSurface = Color(0xFF355C8A),
    onSurfaceVariant = Color(0xFF607895),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFC7C2FF),
    secondary = Color(0xFFC4C3E9),
)

@Composable
fun JianboTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context).copy(
            onSurface = Color(0xFF355C8A),
            onSurfaceVariant = Color(0xFF607895),
        )
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
