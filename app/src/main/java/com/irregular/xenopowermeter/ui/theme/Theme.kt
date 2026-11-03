package com.irregular.xenopowermeter.ui.theme

import android.app.Activity
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.irregular.xenopowermeter.AppSettings

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF009FAA),
    secondary = Color(0xFF90CAF9),
    tertiary = Color(0xFF80CBC4),
    background = Color(0xFF000000),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF009FAA),
    secondary = Color(0xFF1565C0),
    tertiary = Color(0xFF00897B),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121)
)

@Composable
fun XenoPowerMeterTheme(
    content: @Composable () -> Unit
) {
    val darkTheme = when (AppSettings.colorMode) {
        AppSettings.ColorMode.SYSTEM -> isSystemInDarkTheme()
        AppSettings.ColorMode.LIGHT -> false
        AppSettings.ColorMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.decorView.setBackgroundColor(colorScheme.background.toArgb())
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // Replace the platform stretch with a translation-only rubber band: the
    // stretch re-records the content into a RenderNode every frame, and the
    // glass surfaces' backdrop snapshots do not survive that cleanly (the pill
    // blinks when the stretch snaps back). A translation leaves the snapshot
    // No overscroll effect at all: lists and the pager stop dead at their
    // edges — no rubber band, no stretch. The platform stretch (the default)
    // re-records the content into a RenderNode every frame, and the glass
    // surfaces' backdrop snapshots do not survive that cleanly (the pill
    // blinks when the stretch snaps back); the translation-only bounce that
    // replaced it was removed later as a deliberate flatness choice — null
    // keeps both effects out while leaving BouncyOverscroll in the tree for
    // reference.
    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
