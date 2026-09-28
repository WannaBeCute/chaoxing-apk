package com.cxrunner.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 主题模式：黑色 / 白色 / 跟随系统 */
enum class ThemeMode(val label: String) {
    BLACK("黑色"),
    WHITE("白色"),
    SYSTEM("跟随系统");

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: BLACK
    }
}

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF001018),
    primaryContainer = Color(0xFF12303F),
    onPrimaryContainer = Color(0xFFB6E5FF),
    secondary = Color(0xFF8B949E),
    onSecondary = Color(0xFF0D1117),
    background = TermBackground,
    onBackground = TermText,
    surface = TermSurface,
    onSurface = TermText,
    surfaceVariant = TermSurfaceAlt,
    onSurfaceVariant = TermDim,
    outline = TermBorder,
    error = Danger,
    onError = Color(0xFF2A0B0B),
)

private val LightScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBFF),
    onPrimaryContainer = Color(0xFF023C77),
    secondary = Color(0xFF57606A),
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceAlt,
    onSurfaceVariant = LightDim,
    outline = LightBorder,
    error = LightDanger,
    onError = Color.White,
)

@Composable
fun AppTheme(
    mode: ThemeMode = ThemeMode.BLACK,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.BLACK -> true
        ThemeMode.WHITE -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = Typography,
        content = content
    )
}
