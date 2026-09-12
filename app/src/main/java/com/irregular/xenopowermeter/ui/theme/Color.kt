package com.irregular.xenopowermeter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.irregular.xenopowermeter.AppSettings

private val isDark: Boolean @Composable
    get() = when (AppSettings.colorMode) {
        AppSettings.ColorMode.SYSTEM -> isSystemInDarkTheme()
        AppSettings.ColorMode.LIGHT -> false
        AppSettings.ColorMode.DARK -> true
    }

// Light theme chart colors
private val LightVoltageColor = Color(0xFFFF6B6B)
private val LightCurrentColor = Color(0xFF00D4FF)
private val LightPowerColor = Color(0xFFFFCC00)
private val LightAvgPowerColor = Color(0xFFFB8C00)

// Dark theme chart colors (more muted/gray)
private val DarkVoltageColor = Color(0xFFD4837A)
private val DarkCurrentColor = Color(0xFF7AB4D4)
private val DarkPowerColor = Color(0xFFD4C07A)
private val DarkAvgPowerColor = Color(0xFFD49A7A)

// Light theme colors
private val LightBarColor = Color(0xFFD0EEF0)
private val LightButtonTextColor = Color(0xFF3A7A7E)
private val LightConnectButtonColor = Color(0xFF9CD8DB)
private val LightCardColor = Color(0xFFE6F5F6)
private val LightLabelColor = Color(0xFF888888)
private val LightDividerColor = Color(0xFFE0E0E0)
private val LightChartBg = Color(0xFF0D0D1A)
private val LightGridColor = Color(0xFF2A2A3E)
private val LightAxisLabelColor = Color(0xFFBBBBBB)
private val LightLinkColor = Color(0xFF009FAA)
private val LightSettingsButtonColor = Color(0xFF2A8A90)
private val LightSettingsButtonTextColor = Color(0xFF010F10)

// Dark theme colors
private val DarkBarColor = Color(0xFF1A3A3C)
private val DarkButtonTextColor = Color(0xFF7CC5C9)
private val DarkConnectButtonColor = Color(0xFF2A5A5C)
private val DarkCardColor = Color(0xFF1A2E30)
private val DarkLabelColor = Color(0xFFAAAAAA)
private val DarkDividerColor = Color(0xFF3A3A3A)
private val DarkChartBg = Color(0xFF1E1E1E)
private val DarkGridColor = Color(0xFF1A1A2E)
private val DarkAxisLabelColor = Color(0xFF888888)
private val DarkLinkColor = Color(0xFF4DD0E1)
private val DarkSettingsButtonColor = Color(0xFF2A6A6E)
private val DarkSettingsButtonTextColor = Color(0xFFE0F5F7)

object AppColors {
    @Composable
    fun voltageColor() = if (isDark) DarkVoltageColor else LightVoltageColor

    @Composable
    fun currentColor() = if (isDark) DarkCurrentColor else LightCurrentColor

    @Composable
    fun powerColor() = if (isDark) DarkPowerColor else LightPowerColor

    @Composable
    fun avgPowerColor() = if (isDark) DarkAvgPowerColor else LightAvgPowerColor

    @Composable
    fun barColor() = if (isDark) DarkBarColor else LightBarColor

    @Composable
    fun buttonTextColor() = if (isDark) DarkButtonTextColor else LightButtonTextColor

    @Composable
    fun connectButtonColor() = if (isDark) DarkConnectButtonColor else LightConnectButtonColor

    @Composable
    fun cardColor() = if (isDark) DarkCardColor else LightCardColor

    @Composable
    fun labelColor() = if (isDark) DarkLabelColor else LightLabelColor

    @Composable
    fun dividerColor() = if (isDark) DarkDividerColor else LightDividerColor

    @Composable
    fun chartBg() = if (isDark) DarkChartBg else LightChartBg

    @Composable
    fun gridColor() = if (isDark) DarkGridColor else LightGridColor

    @Composable
    fun axisLabelColor() = if (isDark) DarkAxisLabelColor else LightAxisLabelColor

    @Composable
    fun linkColor() = if (isDark) DarkLinkColor else LightLinkColor

    @Composable
    fun settingsButtonColor() = if (isDark) DarkSettingsButtonColor else LightSettingsButtonColor

    @Composable
    fun settingsButtonTextColor() = if (isDark) DarkSettingsButtonTextColor else LightSettingsButtonTextColor
}
