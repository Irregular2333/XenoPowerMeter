package com.irregular.xenopowermeter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Chart line colors - same in both themes
val VoltageColor = Color(0xFFFF6B6B)
val CurrentColor = Color(0xFF00D4FF)
val PowerColor = Color(0xFFFFCC00)
val AvgPowerColor = Color(0xFFFB8C00)

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
private val LightVersionColor = Color(0xFF999999)
private val LightLinkColor = Color(0xFF009FAA)

// Dark theme colors
private val DarkBarColor = Color(0xFF1A3A3C)
private val DarkButtonTextColor = Color(0xFF7CC5C9)
private val DarkConnectButtonColor = Color(0xFF2A5A5C)
private val DarkCardColor = Color(0xFF1A2E30)
private val DarkLabelColor = Color(0xFFAAAAAA)
private val DarkDividerColor = Color(0xFF3A3A3A)
private val DarkChartBg = Color(0xFF0A0A12)
private val DarkGridColor = Color(0xFF1A1A2E)
private val DarkAxisLabelColor = Color(0xFF888888)
private val DarkVersionColor = Color(0xFF666666)
private val DarkLinkColor = Color(0xFF4DD0E1)

object AppColors {
    @Composable
    fun barColor() = if (isSystemInDarkTheme()) DarkBarColor else LightBarColor

    @Composable
    fun buttonTextColor() = if (isSystemInDarkTheme()) DarkButtonTextColor else LightButtonTextColor

    @Composable
    fun connectButtonColor() = if (isSystemInDarkTheme()) DarkConnectButtonColor else LightConnectButtonColor

    @Composable
    fun cardColor() = if (isSystemInDarkTheme()) DarkCardColor else LightCardColor

    @Composable
    fun labelColor() = if (isSystemInDarkTheme()) DarkLabelColor else LightLabelColor

    @Composable
    fun dividerColor() = if (isSystemInDarkTheme()) DarkDividerColor else LightDividerColor

    @Composable
    fun chartBg() = if (isSystemInDarkTheme()) DarkChartBg else LightChartBg

    @Composable
    fun gridColor() = if (isSystemInDarkTheme()) DarkGridColor else LightGridColor

    @Composable
    fun axisLabelColor() = if (isSystemInDarkTheme()) DarkAxisLabelColor else LightAxisLabelColor

    @Composable
    fun versionColor() = if (isSystemInDarkTheme()) DarkVersionColor else LightVersionColor

    @Composable
    fun linkColor() = if (isSystemInDarkTheme()) DarkLinkColor else LightLinkColor
}
