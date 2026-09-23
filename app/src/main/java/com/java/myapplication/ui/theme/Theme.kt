package com.java.myapplication.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = GrassGreen,
    onPrimary = SoftIvoryCard,
    primaryContainer = GrassGreenLight,
    onPrimaryContainer = InkGray,
    secondary = MistBlue,
    onSecondary = InkGray,
    secondaryContainer = MistBlueLight,
    onSecondaryContainer = InkGray,
    tertiary = SunOrange,
    onTertiary = SoftIvoryCard,
    error = CoralRed,
    onError = SoftIvoryCard,
    background = SoftIvory,
    onBackground = InkGray,
    surface = SoftIvory,
    onSurface = InkGray,
    surfaceVariant = SoftIvoryCard,
    onSurfaceVariant = InkGraySecondary,
    outline = InkGrayHint
)

private val DarkColorScheme = darkColorScheme(
    primary = NightGreen,
    onPrimary = NightBackground,
    primaryContainer = NightCard,
    onPrimaryContainer = NightText,
    secondary = NightBlue,
    onSecondary = NightBackground,
    secondaryContainer = NightCard,
    onSecondaryContainer = NightText,
    tertiary = NightOrange,
    onTertiary = NightBackground,
    error = NightRed,
    onError = NightBackground,
    background = NightBackground,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightCard,
    onSurfaceVariant = NightTextSecondary,
    outline = NightTextHint
)

/**
 * 护眼主题。
 *
 * @param darkTheme 是否使用深色配色
 * @param autoNight 是否允许随系统深色状态自动切换。
 *        为 false 时强制使用浅色配色（设置页「自动夜间模式」关闭时的行为）。
 */
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    autoNight: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (autoNight && darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}