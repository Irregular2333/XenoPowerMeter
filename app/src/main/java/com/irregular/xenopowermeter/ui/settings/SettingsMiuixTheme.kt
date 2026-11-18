package com.irregular.xenopowermeter.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.irregular.xenopowermeter.ui.theme.AppColors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Bridges the app's teal palette into Miuix's color scheme so the miuix
 * components on the settings page (dropdowns, switches) read the same colors
 * as the rest of the UI.
 *
 * Only the fields the miuix settings components actually consume are
 * overridden; everything else keeps the library defaults:
 * - Row title: onBackground -> AppColors.labelColor()
 * - Row value / arrow: onSurfaceVariantActions -> AppColors.linkColor()
 * - Disabled text: disabledOnSecondaryVariant -> label at 45%
 * - Dropdown item text: onSurfaceContainer -> labelColor()
 * - Dropdown container: surfaceContainer -> cardColor() (popup matches cards)
 * - Selected item highlight / check icon: primary -> linkColor()
 */
@Composable
fun MiuixSettingsTheme(content: @Composable () -> Unit) {
    val label = AppColors.labelColor()
    val link = AppColors.linkColor()
    val card = AppColors.cardColor()
    val colors = if (AppColors.isDarkTheme()) {
        darkColorScheme(
            primary = link,
            onBackground = label,
            onSurface = label,
            onSurfaceContainer = label,
            onSurfaceVariantSummary = label.copy(alpha = 0.7f),
            onSurfaceVariantActions = link,
            disabledOnSecondaryVariant = label.copy(alpha = 0.45f),
            surfaceContainer = card,
        )
    } else {
        lightColorScheme(
            primary = link,
            onBackground = label,
            onSurface = label,
            onSurfaceContainer = label,
            onSurfaceVariantSummary = label.copy(alpha = 0.7f),
            onSurfaceVariantActions = link,
            disabledOnSecondaryVariant = label.copy(alpha = 0.45f),
            surfaceContainer = card,
        )
    }
    // Row titles read headline1 (17sp) and row values read body2 (14sp).
    // The app's hand-built rows are 15sp, so bring both in line to keep the
    // settings page's type scale uniform.
    val textStyles = defaultTextStyles(
        headline1 = TextStyle(fontSize = 15.sp),
        body2 = TextStyle(fontSize = 15.sp)
    )

    MiuixTheme(
        colors = colors,
        textStyles = textStyles,
        content = content
    )
}
