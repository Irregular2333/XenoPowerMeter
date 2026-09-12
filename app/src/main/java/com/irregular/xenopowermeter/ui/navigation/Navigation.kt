package com.irregular.xenopowermeter.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val icon: ImageVector) {
    data object Main : Screen(Icons.Default.Home)
    data object Settings : Screen(Icons.Default.Settings)
    data object About : Screen(Icons.Default.Info)
}

val bottomNavItems = listOf(Screen.Main, Screen.Settings, Screen.About)
