package com.irregular.xenopowermeter.ui

import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import com.irregular.xenopowermeter.BuildConfig

private const val TAG = "TabletLayout"

/**
 * True when the landscape "tablet" layout should activate: the window is
 * rotated AND the device itself is tablet-class. A phone rotated sideways
 * keeps the portrait layout — the landscape design was drawn for tablets
 * and under-uses / overflows a phone's dimensions.
 *
 * Device class is decided by PHYSICAL size, not dp: dp values scale with
 * the user's display-density override (this project's own test phone runs
 * 640dpi hardware at a 614dpi override), so any dp threshold — including
 * the platform's sw600dp — can drift when the user changes display size.
 * Instead the display's real pixel size is divided by its hardware-reported
 * xdpi/ydpi, giving the physical length of the screen's shorter edge in
 * inches. Phones land around 2.5–3.1", tablet-class panels (pads, foldables
 * opened) at 4.5" and up, so the 4" threshold sits in empty space between
 * the two populations and is stable across rotation, density overrides and
 * window sizes alike.
 */
@Composable
fun isTabletLandscape(): Boolean {
    val view = LocalView.current
    val config = LocalConfiguration.current
    val isTablet = remember(view) { isTabletDevice(view) }
    return isTablet && config.screenWidthDp > config.screenHeightDp
}

/** Tablet-class device: the screen's shorter physical edge is ≥ 4 inches. */
private fun isTabletDevice(view: View): Boolean {
    val display = view.display
    if (display != null) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val dpi = (metrics.xdpi + metrics.ydpi) / 2f
        if (dpi > 50f && metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            val shortestEdgeInches =
                minOf(metrics.widthPixels, metrics.heightPixels) / dpi
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "${metrics.widthPixels}x${metrics.heightPixels} @ " +
                        "%.0fdpi = %.2f in -> tablet=${shortestEdgeInches >= 4.0f}"
                        .format(dpi, shortestEdgeInches)
                )
            }
            return shortestEdgeInches >= 4.0f
        }
    }
    // Physical metrics unavailable: fall back to the platform's own
    // tablet threshold. Same intent, just less robust to density overrides.
    return view.resources.configuration.smallestScreenWidthDp >= 600
}
