package com.irregular.xenopowermeter.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.irregular.xenopowermeter.R

sealed class Screen(val icon: ImageVector, @StringRes val labelRes: Int) {
    data object Main : Screen(Icons.Default.Home, R.string.nav_home)
    data object Settings : Screen(Icons.Default.Settings, R.string.nav_settings)
    data object About : Screen(Icons.Default.Info, R.string.nav_about)
}

val bottomNavItems = listOf(Screen.Main, Screen.Settings, Screen.About)
