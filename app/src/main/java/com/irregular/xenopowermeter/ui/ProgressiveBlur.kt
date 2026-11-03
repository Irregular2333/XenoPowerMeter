package com.irregular.xenopowermeter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported
import androidx.compose.runtime.remember

/**
 * Progressive blur over the floating bar's footprint, built from the two
 * pieces the library documents for this (backdrop → tutorials → progressive
 * blur): a plain backdrop with a [blur] effect, finished by an alpha mask in a
 * runtime shader.
 *
 * One masked blur alone only *dilutes* the blurred copy, so near the sharp end
 * the page below still shows through at full sharpness and the text reads as
 * faded rather than out of focus. Stacking two masks with different radii
 * fixes that: the wide one is opaque over most of the band, so what shows
 * through under the narrow one is already soft — the band goes sharp → soft →
 * very soft instead of crisp-at-30%-opacity.
 */

/**
 * Alpha mask, [fadeStart]..[fadeEnd] of the band's height. 0 at the band's top
 * edge (page still sharp) and 1 well before its bottom, so the deepest zone is
 * fully blurred and nothing stays half-transparent there.
 *
 * `smoothstep` rather than a linear ramp: the two ends flatten out, which is
 * what keeps the band's top edge from drawing a line.
 *
 * The tint is the tutorial's, kept for callers that want to wash the band
 * toward a colour — at `tintIntensity = 0` the result is blurred content alone.
 */
private const val ALPHA_MASK_SHADER = """
uniform shader content;

uniform float2 size;
layout(color) uniform half4 tint;
uniform float tintIntensity;
uniform float fadeStart;
uniform float fadeEnd;

half4 main(float2 coord) {
    float t = clamp(coord.y / max(size.y, 1.0), 0.0, 1.0);
    float mask = smoothstep(fadeStart, fadeEnd, t);
    return mix(content.eval(coord) * mask, tint * mask, tintIntensity);
}
"""

/**
 * Same mask, flipped vertically: 0 at the band's bottom edge (page still
 * sharp) and 1 at the top, for bands anchored above a top edge.
 */
private const val ALPHA_MASK_SHADER_REVERSED = """
uniform shader content;

uniform float2 size;
layout(color) uniform half4 tint;
uniform float tintIntensity;
uniform float fadeStart;
uniform float fadeEnd;

half4 main(float2 coord) {
    float t = clamp(coord.y / max(size.y, 1.0), 0.0, 1.0);
    float mask = 1.0 - smoothstep(fadeStart, fadeEnd, t);
    return mix(content.eval(coord) * mask, tint * mask, tintIntensity);
}
"""

/**
 * Narrow pass: 40% of the final radius, faded in over the band's top third.
 * Deliberately close to the wide pass — cross-fading two very different radii
 * leaves a visible double image, two similar ones read as one soft ramp.
 */
private const val NEAR_RADIUS = 0.40f
private const val NEAR_FADE_START = 0.00f
private const val NEAR_FADE_END = 0.40f

/** Wide pass: the full radius, taking over from the middle down. */
private const val FAR_FADE_START = 0.34f
private const val FAR_FADE_END = 0.95f

/**
 * Blur band anchored to the bottom of its parent: sharp at the top edge (line
 * it up with the pill's top) and fully blurred by the bottom.
 */
@Composable
fun ProgressiveBottomBlur(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    bandHeight: Dp,
    maxBlur: Dp = 16.dp
) {
    ProgressiveBlurBand(backdrop, modifier, bandHeight, maxBlur, fromTop = false)
}

/**
 * Blur band anchored to the top of its parent: sharp at the bottom edge and
 * fully blurred at the top, so content dissolves as it scrolls up under it.
 */
@Composable
fun ProgressiveTopBlur(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    bandHeight: Dp,
    maxBlur: Dp = 16.dp
) {
    ProgressiveBlurBand(backdrop, modifier, bandHeight, maxBlur, fromTop = true)
}

@Composable
private fun ProgressiveBlurBand(
    backdrop: Backdrop,
    modifier: Modifier,
    bandHeight: Dp,
    maxBlur: Dp,
    fromTop: Boolean
) {
    if (bandHeight <= 0.dp || maxBlur <= 0.dp) return
    if (!isRenderEffectSupported()) return

    if (!isRuntimeShaderSupported()) {
        LegacyProgressiveBlur(backdrop, modifier, bandHeight, maxBlur, fromTop)
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(bandHeight)
    ) {
        MaskedBlurLayer(
            backdrop = backdrop,
            modifier = Modifier.matchParentSize(),
            radius = maxBlur * NEAR_RADIUS,
            fadeStart = NEAR_FADE_START,
            fadeEnd = NEAR_FADE_END,
            reversed = fromTop
        )
        MaskedBlurLayer(
            backdrop = backdrop,
            modifier = Modifier.matchParentSize(),
            radius = maxBlur,
            fadeStart = FAR_FADE_START,
            fadeEnd = FAR_FADE_END,
            reversed = fromTop
        )
    }
}

@Composable
private fun MaskedBlurLayer(
    backdrop: Backdrop,
    modifier: Modifier,
    radius: Dp,
    fadeStart: Float,
    fadeEnd: Float,
    reversed: Boolean
) {
    // The effect lambda is a plain object to the modifier: rebuild it whenever
    // an input changes or the node keeps drawing with the old radius.
    val effects: com.kyant.backdrop.BackdropEffectScope.() -> Unit =
        remember(radius, fadeStart, fadeEnd, reversed) {
            {
                // The mask works in the band's own pixel space, so the layer
                // must not be padded — padding would shift `coord`.
                padding = 0f
                blur(radius.toPx())
                padding = 0f

                runtimeShaderEffect(
                    key = "ProgressiveBlurMask",
                    shaderString = if (reversed) ALPHA_MASK_SHADER_REVERSED else ALPHA_MASK_SHADER,
                    uniformShaderName = "content"
                ) {
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("tint", Color.Transparent)
                    setFloatUniform("tintIntensity", 0f)
                    setFloatUniform("fadeStart", fadeStart)
                    setFloatUniform("fadeEnd", fadeEnd)
                }
            }
        }

    Box(
        modifier = modifier.drawPlainBackdrop(
            backdrop = backdrop,
            shape = { RectangleShape },
            effects = effects
        )
    )
}

/**
 * API 31–32: no runtime shaders, so the mask is applied to the finished band
 * instead — one RenderEffect blur, wiped out toward the top by a vertical
 * alpha ramp drawn with `DstIn`.
 */
@Composable
private fun LegacyProgressiveBlur(
    backdrop: Backdrop,
    modifier: Modifier,
    bandHeight: Dp,
    maxBlur: Dp,
    fromTop: Boolean
) {
    val fade = remember(fromTop) {
        if (fromTop) {
            // Mirrored for a top band: opaque at the top edge, wiped toward
            // the bottom where the page stays sharp.
            Brush.verticalGradient(
                1.0f to Color.Transparent,
                0.45f to Color.White.copy(alpha = 0.35f),
                0.15f to Color.White.copy(alpha = 0.85f),
                0.0f to Color.White
            )
        } else {
            Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.55f to Color.White.copy(alpha = 0.35f),
                0.85f to Color.White.copy(alpha = 0.85f),
                1.0f to Color.White
            )
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(bandHeight)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(fade, blendMode = BlendMode.DstIn)
            }
            .drawPlainBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    padding = 0f
                    // Mirror instead of clamp: clamping would smear the band's
                    // top row upward into a flat, sharp-edged shelf.
                    blur(maxBlur.toPx(), TileMode.Mirror)
                    padding = 0f
                }
            )
    )
}
