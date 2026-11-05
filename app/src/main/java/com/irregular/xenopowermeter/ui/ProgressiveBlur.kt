package com.irregular.xenopowermeter.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported

/**
 * Progressive (per-pixel radius) backdrop blur, the way iOS and MeiloX
 * (Glasmio 0.1.1) build a scroll edge.
 *
 * The recipe has three parts, and all three are needed. Every earlier attempt
 * in this file shipped one or two of them and looked broken:
 *
 * 1. The material is a slab that only EXISTS while the page is scrolled: its
 *    alpha tracks the scroll offset from 0 to 1 over [TopEdgeFadeDistance].
 *    At rest there is no band at all, so nothing ever sits on top of the first
 *    row looking washed out.
 * 2. Inside the slab the blur radius is evaluated per pixel and falls to zero
 *    exactly at the slab's bottom edge, so the material's lower boundary is
 *    invisible: the last blurred pixel and the first sharp one are the same
 *    pixel. No hard line, no "one row blurred, the next sharp".
 * 3. The content itself is faded towards the top edge by [scrollEdgeContentFade]
 *    before it is recorded into the backdrop. That is what kills the ghosting
 *    and the flicker: the slab is an OPAQUE copy of already-faded content, so
 *    nothing sharp hides underneath it and a one frame lag cannot show as a
 *    double image.
 *
 * Contrast with the two rejected designs: a radius ramp alone over a short
 * band reads as a hard cut (the ramp needs ~1.5 rows, our rows are 64dp); an
 * alpha dissolve alone composites a semi-transparent blurred copy over live
 * sharp content, which flickers while scrolling and washes out text at rest.
 *
 * Two separable Gaussian passes with a per-pixel radius, ported from
 * AndroidX's progressive blur (and Glasmio's copy of it).
 * Source: https://android.googlesource.com/platform/frameworks/support/+/7e1430f6c57df22b6ceeaa66ff4e18b53a67edd9
 */

/**
 * Height the slab is allowed to extend past the header it dissolves into.
 *
 * The content fade has to end here as well, not at the header: the slab's
 * bottom edge is the only place where both the blur and the fade are back to
 * "no effect", which is what makes the boundary invisible.
 */
internal val TopBarSkirt: Dp = 34.dp

/**
 * Depth of the top material below the status bar: the height a navigation bar
 * would occupy. Also the distance the content needs to fade over, because a
 * shorter fade reads as a step no matter what the blur does.
 */
internal val TopBarMaterialHeight: Dp = 56.dp

/** Scroll distance over which the top material fades in. */
private val TopEdgeFadeDistance = 56.dp

/** Fraction of the slab that stays at full radius, measured from its top. */
private const val FullRadiusFraction = 0.42f

private fun progressiveBlurPassShader(isVertical: Boolean): String {
    val pairedOffset =
        if (isVertical) "vec2(0.0, i + weightH / weight)"
        else "vec2(i + weightH / weight, 0.0)"
    val oddOffset = if (isVertical) "vec2(0.0, r)" else "vec2(r, 0.0)"
    val boundsCheck =
        if (isVertical) {
            "return step(bounds.y, sampleCoord.y) * (1.0 - step(bounds.w, sampleCoord.y));"
        } else {
            "return step(bounds.x, sampleCoord.x) * (1.0 - step(bounds.z, sampleCoord.x));"
        }
    return """
        uniform shader content;
        uniform float blurRadius;
        uniform float4 crop;
        uniform float unbounded;
        uniform float startIntensity;
        uniform float endIntensity;
        uniform float2 startPoint;
        uniform float2 endPoint;
        uniform float easing;
        const float maxRadius = 150.0;

        float inBoundsOnMovedAxis(vec2 sampleCoord, float4 bounds) {
            $boundsCheck
        }

        vec4 blur(vec2 coord, float radius) {
            float r = floor(radius);
            if (r < 1.0) { return content.eval(coord); }

            float sigma = max(radius / 2.0, 1.0);
            float weightSum = 1.0;
            vec4 result = content.eval(coord);
            float inverseSigmaSquared = 1.0 / (sigma * sigma);
            float weightRatio = exp(-0.5 * inverseSigmaSquared);
            float weightRatioStep = exp(-inverseSigmaSquared);
            float currentWeight = 1.0;

            for (float i = 1.0; i < maxRadius; i += 2.0) {
                if (i >= r) { break; }

                currentWeight *= weightRatio;
                weightRatio *= weightRatioStep;
                float weightL = currentWeight;
                currentWeight *= weightRatio;
                weightRatio *= weightRatioStep;
                float weightH = currentWeight;
                float weight = weightL + weightH;
                vec2 offset = $pairedOffset;

                vec2 newCoord1 = coord - offset;
                float mask1 = inBoundsOnMovedAxis(newCoord1, crop);
                weightSum += weight * max(mask1, unbounded);
                if (mask1 > 0.0) {
                    result += weight * content.eval(newCoord1);
                }

                vec2 newCoord2 = coord + offset;
                float mask2 = inBoundsOnMovedAxis(newCoord2, crop);
                weightSum += weight * max(mask2, unbounded);
                if (mask2 > 0.0) {
                    result += weight * content.eval(newCoord2);
                }
            }

            float oddMask = mod(r, 2.0) * (1.0 - step(maxRadius, r));
            float oddWeight = currentWeight * weightRatio * oddMask;
            vec2 tailOffset = $oddOffset;

            vec2 oddCoord1 = coord - tailOffset;
            float oddBounds1 = inBoundsOnMovedAxis(oddCoord1, crop);
            weightSum += oddWeight * max(oddBounds1, unbounded);
            if (oddBounds1 > 0.0) {
                result += oddWeight * content.eval(oddCoord1);
            }

            vec2 oddCoord2 = coord + tailOffset;
            float oddBounds2 = inBoundsOnMovedAxis(oddCoord2, crop);
            weightSum += oddWeight * max(oddBounds2, unbounded);
            if (oddBounds2 > 0.0) {
                result += oddWeight * content.eval(oddCoord2);
            }

            return result / weightSum;
        }

        half4 main(float2 coord) {
            float2 pa = coord - startPoint;
            float2 ba = endPoint - startPoint;
            float fraction = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
            // easing 1 = smoothstep on the ramp: near-zero slope at both ends,
            // so a bottom band melts content in instead of cutting it.
            float t = mix(fraction, fraction * fraction * (3.0 - 2.0 * fraction), easing);
            float intensity = mix(startIntensity, endIntensity, t);
            return half4(blur(coord, blurRadius * intensity));
        }
    """.trimIndent()
}

private val HorizontalProgressiveBlurShader = progressiveBlurPassShader(isVertical = false)
private val VerticalProgressiveBlurShader = progressiveBlurPassShader(isVertical = true)

/**
 * Blur whose radius is mixed from [startIntensity] to [endIntensity] along the
 * line [startPoint]..[endPoint], both given as fractions of the slab's height.
 *
 * The top edge uses the defaults: full radius down to 42% and a smooth ramp to
 * zero at the bottom. The bottom edge flips them so the ramp starts at the
 * slab's top edge instead.
 */
private fun BackdropEffectScope.progressiveBackdropBlur(
    maxRadius: Float,
    startYFraction: Float = FullRadiusFraction,
    endYFraction: Float = 1f,
    startIntensity: Float = 1f,
    endIntensity: Float = 0f,
    smooth: Boolean = false
) {
    if (maxRadius <= 0f) return
    if (!isRuntimeShaderSupported()) {
        // API 31/32: no AGSL. A plain blur still reads as frosted material,
        // it just loses the soft boundary.
        blur(maxRadius)
        return
    }

    val resolvedRadius = maxRadius.coerceAtMost(150f)

    fun applyPass(key: String, shader: String) {
        runtimeShaderEffect(key, shader, "content") {
            setFloatUniform("blurRadius", resolvedRadius)
            setFloatUniform("crop", 0f, 0f, size.width, size.height)
            setFloatUniform("unbounded", 0f)
            setFloatUniform("startIntensity", startIntensity)
            setFloatUniform("endIntensity", endIntensity)
            setFloatUniform("startPoint", 0f, size.height * startYFraction)
            setFloatUniform("endPoint", 0f, size.height * endYFraction)
            setFloatUniform("easing", if (smooth) 1f else 0f)
        }
    }

    applyPass("ProgressiveBlurHorizontal", HorizontalProgressiveBlurShader)
    applyPass("ProgressiveBlurVertical", VerticalProgressiveBlurShader)
}

/**
 * The blur slab. [progress] is read during draw, not composition, so scrolling
 * never recomposes the page.
 */
private fun Modifier.progressiveBlurBackdrop(
    backdrop: Backdrop,
    progress: () -> Float,
    maxRadius: Dp = 10.dp,
    startYFraction: Float = FullRadiusFraction,
    endYFraction: Float = 1f,
    startIntensity: Float = 1f,
    endIntensity: Float = 0f,
    smooth: Boolean = false
): Modifier = graphicsLayer {
    alpha = progress().coerceIn(0f, 1f)
}.drawPlainBackdrop(
    backdrop = backdrop,
    shape = { RectangleShape },
    effects = {
        progressiveBackdropBlur(
            maxRadius = maxRadius.toPx(),
            startYFraction = startYFraction,
            endYFraction = endYFraction,
            startIntensity = startIntensity,
            endIntensity = endIntensity,
            smooth = smooth
        )
    }
)

/**
 * Fade the foreground towards [headerTop] before it is recorded into the
 * backdrop: at full reveal the content is invisible there and back to 100% at
 * [headerHeight].
 *
 * The fade and the blur have to cover the same span. If the content is still
 * opaque where the blur is strongest, the page shows a row of perfectly
 * opaque, heavily smeared text right above a row of sharp text — one more
 * version of "one row blurred, the next one sharp", just washed out instead of
 * cut off.
 *
 * Applied to the content, never to the page background, so the recorded layer
 * stays opaque and the slab above it can be opaque too.
 */
fun Modifier.scrollEdgeContentFade(
    progress: () -> Float,
    headerHeight: Dp,
    headerTop: Dp = 0.dp
): Modifier = graphicsLayer {
    // DstIn needs the content in its own buffer to mask against.
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithContent {
    drawContent()
    val reveal = progress().coerceIn(0f, 1f)
    if (reveal <= 0f) return@drawWithContent
    val header = headerHeight.toPx().coerceAtLeast(1f)
    val top = headerTop.toPx().coerceIn(0f, header - 1f)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 1f - reveal), Color.White),
            startY = top,
            endY = header
        ),
        blendMode = BlendMode.DstIn
    )
}

/**
 * Bottom mirror of [scrollEdgeContentFade]: dissolve the content itself as it
 * slides under the floating bar, instead of letting the variable-radius pass
 * smear glyphs into streaks. Same trick, same geometry, opposite direction —
 * fully opaque at the band's top edge, fully gone at the screen bottom.
 *
 * Unlike the top fade there is no scroll gate on purpose: the bottom band's
 * blur runs permanently (content can rest inside it on first entry, since the
 * list is taller than the screen), so its dissolve must run permanently too —
 * gating it would show sharp-but-smeared rows until the first scroll.
 *
 * Must sit at the same chain position as the top fade (after the column's
 * opaque background, before the scroll): the offscreen buffer then contains
 * only the content, so rows dissolve into the page background rather than
 * washing the background itself out into a fog rectangle.
 */
fun Modifier.bottomEdgeContentFade(
    bandHeight: Dp
): Modifier = graphicsLayer {
    // DstIn needs the content in its own buffer to mask against.
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithContent {
    drawContent()
    val band = bandHeight.toPx().coerceAtLeast(1f)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
            startY = size.height - band,
            endY = size.height
        ),
        blendMode = BlendMode.DstIn
    )
}

/**
 * Scroll progress of the top material: 0 at rest, 1 once the page has moved
 * [TopEdgeFadeDistance].
 */
@Composable
fun rememberTopEdgeProgress(
    state: ScrollState,
    distance: Dp = TopEdgeFadeDistance
): State<Float> {
    val distancePx = with(LocalDensity.current) { distance.toPx().coerceAtLeast(1f) }
    return remember(state, distancePx) {
        derivedStateOf { (state.value / distancePx).coerceIn(0f, 1f) }
    }
}

/** Same progress for pages backed by a lazy staggered grid. */
@Composable
fun rememberTopEdgeProgress(
    state: LazyStaggeredGridState,
    distance: Dp = TopEdgeFadeDistance
): State<Float> {
    val distancePx = with(LocalDensity.current) { distance.toPx().coerceAtLeast(1f) }
    return remember(state, distancePx) {
        derivedStateOf {
            if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / distancePx).coerceIn(0f, 1f)
        }
    }
}

/**
 * Material over a page's top edge. Covers [bandHeight] plus the skirt that the
 * radius ramp needs, and only exists once the page scrolls.
 */
@Composable
fun ProgressiveTopBlur(
    backdrop: Backdrop,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    bandHeight: Dp,
    maxBlur: Dp = 10.dp
) {
    if (bandHeight <= 0.dp || maxBlur <= 0.dp) return
    if (!isRenderEffectSupported()) return

    Box(
        modifier
            .fillMaxWidth()
            .height(bandHeight + TopBarSkirt)
            .progressiveBlurBackdrop(
                backdrop = backdrop,
                progress = progress,
                maxRadius = maxBlur,
                startYFraction = 0f,
                endYFraction = 1f
            )
    )
}

/**
 * Material over the floating bar's footprint, anchored to the bottom: the
 * radius ramps from zero at its top edge to full strength at the screen
 * bottom, so content melts into the bar instead of hitting a fog wall. The
 * ramp spans the WHOLE band (not a fraction of it): the band is flush with
 * the pill, so a short ramp slices the first overlapped glyph in half —
 * sharp above the line, wax-drip streaks below. A full-height ramp keeps the
 * radius near zero for the first rows under the line and spreads the defocus
 * over the pill's own height, and adds no color of its own. The ramp is
 * smoothstepped: near-zero slope at both ends.
 */
@Composable
fun ProgressiveBottomBlur(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    bandHeight: Dp,
    maxBlur: Dp = 10.dp
) {
    if (bandHeight <= 0.dp || maxBlur <= 0.dp) return
    if (!isRenderEffectSupported()) return

    Box(
        modifier
            .fillMaxWidth()
            .height(bandHeight)
            .progressiveBlurBackdrop(
                backdrop = backdrop,
                progress = { 1f },
                maxRadius = maxBlur,
                startYFraction = 0f,
                endYFraction = 1f,
                startIntensity = 0f,
                endIntensity = 1f,
                smooth = true
            )
    )
}
