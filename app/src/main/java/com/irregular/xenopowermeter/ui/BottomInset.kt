package com.irregular.xenopowermeter.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single source of truth for the bottom inset the floating bar is anchored to.
 *
 * Do not use [androidx.compose.foundation.layout.WindowInsets.safeContent] here:
 * it unions `safeGestures`, whose `mandatorySystemGestures` region is the whole
 * swipe-up area rather than the visible navigation bar. On gesture navigation
 * that is roughly twice the bar's height (32dp vs 16dp on the test device), so
 * the pill floated 36dp above the screen edge with a visible gap underneath.
 *
 * `navigationBars` is what Material's own bottom bars use. `displayCutout` is
 * unioned in so a bottom cutout still pushes the bar clear of it.
 *
 * Every bottom-anchored measurement (bar placement, the space reserved under
 * page 0, the last-row rest space on both scrollable pages) must read this one
 * value — mixing it with `safeContent` makes the bar and the content padding
 * drift apart by the gesture strip's height.
 */
internal val BottomBarInset: WindowInsets
    @Composable get() = WindowInsets.navigationBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Bottom)

/**
 * Height of the floating pill itself.
 *
 * Every derived measurement (the pill's corner radius, the sliding highlight,
 * [BottomBarTopEdge]) reads this, so growing the bar is a one-line change.
 */
internal val BottomBarHeight: Dp = 60.dp

/** Gap between the pill and the gesture inset below it (portrait bottom bar). */
internal val BottomBarLift: Dp = 1.dp

/**
 * Top edge of the pill, measured up from the bottom of the screen. Pages that
 * need to line something up with the bar (reserved scroll space) derive it
 * from here instead of hard-coding a dp value, so a device with a different
 * gesture inset still matches.
 */
internal val BottomBarTopEdge: Dp
    @Composable get() = BottomBarInset.asPaddingValues().calculateBottomPadding() +
        BottomBarLift + BottomBarHeight

/**
 * Landscape counterpart of the pill: the same slab of glass, stood on its end
 * and hung off the window's start edge. Everything below is the vertical
 * layout's measurement with the axes swapped, so the rail and the pill can
 * never drift apart either.
 */
internal val RailInset: WindowInsets
    @Composable get() = WindowInsets.navigationBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Start)

/** Width of the rail: the pill's height, standing up. */
internal val RailThickness: Dp = BottomBarHeight

/** Length of the rail: the pill's width, standing up. */
internal val RailLength: Dp = 260.dp

/**
 * Gap between the rail and the window's start edge. Kept independent from
 * [BottomBarLift]: the portrait pill sits nearly flush (1dp), but the rail
 * needs the old 6dp so the pages' start-side rhythm — edge, rail, content —
 * still reads in that order ([RailPageGutter] matches this value).
 */
internal val RailLift: Dp = 6.dp

/**
 * Distance from the window's start edge to the rail's far edge — what a page
 * has to clear so nothing parks under the glass. Read in the layout direction
 * so the rail and the content inset mirror together.
 */
internal val RailEndEdge: Dp
    @Composable get() = RailInset.asPaddingValues()
        .calculateStartPadding(LocalLayoutDirection.current) + RailLift + RailThickness

/**
 * Air between the rail and the page content.
 *
 * Every page starts its content at [RailPageStart] instead of each doing its
 * own arithmetic — that is precisely how the three screens ended up with three
 * different left margins (74/90/90dp) after the rail landed. Matches the rail's
 * own lift off the window edge ([RailLift]), so the two gaps read as the same
 * rhythm: edge — rail — content.
 */
internal val RailPageGutter: Dp = RailLift

/**
 * Where page content begins in landscape, measured from the window's start
 * edge. Single source of truth for Main / Settings / About: rail, then gutter.
 */
internal val RailPageStart: Dp
    @Composable get() = RailEndEdge + RailPageGutter

/** Trailing gutter shared by the pages in both orientations. */
internal val PageEndGutter: Dp = 16.dp
