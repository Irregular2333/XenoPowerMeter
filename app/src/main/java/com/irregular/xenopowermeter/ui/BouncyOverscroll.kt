package com.irregular.xenopowermeter.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Rubber-band overscroll that *translates* the scroll viewport instead of
 * letting the platform stretch it.
 *
 * Why not the default: Compose's stretch effect re-records the whole content
 * into a RenderNode and applies an Android RenderNode stretch each frame while
 * the effect runs. Our glass surfaces (the floating pill, and the progressive
 * blur bands) read the page through a `LayerBackdrop` snapshot. A stretched
 * snapshot does not survive that round trip cleanly, so on the frame the
 * stretch snaps back the backdrop visibly blinks for a frame or two. A pure
 * translation keeps every pixel where it was — the snapshot stays valid, so
 * the glass never loses its base.
 *
 * The damping curve (x - x² + x³/3), the spring period and the takeover rules
 * mirror the Miuix overscroll used by the reference app, so the bounce feels
 * the same: loose at the start, firm near the limit, critically damped return.
 */
class BouncyOverscrollEffect : OverscrollEffect {

    private companion object {
        const val THRESHOLD = 1f
        // Critically damped spring; period 0.4s → stiffness = (2π / 0.4)² ≈ 247.
        const val STIFFNESS = 247f
        const val DAMPING = 1f
    }

    // Plain floats invalidated through placement: the offset changes every
    // frame during a bounce but never affects composition or measurement.
    private var placedX = 0f
    private var placedY = 0f

    var offsetX = 0f
        private set(value) {
            if (field == value) return
            field = value
            val rounded = round(value)
            if (rounded != placedX) {
                placedX = rounded
                invalidatePlacement?.invoke()
            }
        }

    var offsetY = 0f
        private set(value) {
            if (field == value) return
            field = value
            val rounded = round(value)
            if (rounded != placedY) {
                placedY = rounded
                invalidatePlacement?.invoke()
            }
        }

    private var rawX = 0f
    private var rawY = 0f

    private var rangeX = 0f
    private var rangeY = 0f

    private var settleJob: Job? = null

    private var invalidatePlacement: (() -> Unit)? = null

    override val isInProgress: Boolean
        get() = abs(offsetX) > THRESHOLD || abs(offsetY) > THRESHOLD

    override val node: DelegatableNode = BouncyOverscrollNode(this)

    internal fun updateRange(width: Float, height: Float) {
        rangeX = width
        rangeY = height
    }

    internal fun bindInvalidatePlacement(block: () -> Unit) {
        invalidatePlacement = block
    }

    // ---- damping -----------------------------------------------------------

    private fun damped(accum: Float, range: Float): Float {
        if (range <= 0f) return 0f
        val x = min(abs(accum) / range, 1f)
        // x - x² + x³/3: slope 1 at the start, flattening to 0 at the limit.
        val factor = x - x * x + x * x * x / 3f
        return sign(accum) * factor * range
    }

    /** Inverse of [damped]; used when a drag takes over a running spring. */
    private fun touchDistance(distance: Float, range: Float): Float {
        if (range <= 0f) return 0f
        val maxOffset = range / 3f
        val d = abs(distance).coerceAtMost(maxOffset)
        if (d <= 0f) return 0f
        val base = range - 3f * d
        val part = range.toDouble().pow(2.0 / 3.0) *
            sign(base) * abs(base).toDouble().pow(1.0 / 3.0)
        return (range - part).toFloat()
    }

    private fun dragX(delta: Float) {
        if (delta == 0f || rangeX <= 0f) return
        rawX = (rawX + delta).coerceIn(-rangeX, rangeX)
        offsetX = damped(rawX, rangeX)
    }

    private fun dragY(delta: Float) {
        if (delta == 0f || rangeY <= 0f) return
        rawY = (rawY + delta).coerceIn(-rangeY, rangeY)
        offsetY = damped(rawY, rangeY)
    }

    private fun resetX() {
        offsetX = 0f
        rawX = 0f
    }

    private fun resetY() {
        offsetY = 0f
        rawY = 0f
    }

    // ---- scroll ------------------------------------------------------------

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        // Programmatic scrolls (bring-into-view, fling rest, etc.) must never
        // push the rubber band around.
        if (source != NestedScrollSource.UserInput) return performScroll(delta)

        // Taking over a running spring: re-derive the "finger travel" that
        // would have produced the current displacement, otherwise the first
        // drag delta would be measured against a stale origin and the band
        // would jump.
        if (settleJob?.isActive == true) {
            if (abs(offsetX) > THRESHOLD) {
                rawX = sign(offsetX) * touchDistance(offsetX, rangeX)
            }
            if (abs(offsetY) > THRESHOLD) {
                rawY = sign(offsetY) * touchDistance(offsetY, rangeY)
            }
        }
        settleJob?.cancel()
        settleJob = null

        // A drag that reverses an existing bounce should first eat the bounce
        // (and only then start moving the content), otherwise the list jumps.
        var scrollX = delta.x
        var extraX = 0f
        if (abs(offsetX) > THRESHOLD && delta.x != 0f && sign(delta.x) != sign(rawX)) {
            val canConsume = if (abs(rawX) <= abs(delta.x)) -rawX else delta.x
            if (abs(rawX) <= abs(delta.x)) {
                resetX()
                scrollX = delta.x - canConsume
                extraX = canConsume
            } else {
                dragX(canConsume)
                scrollX = 0f
                extraX = delta.x
            }
        }

        var scrollY = delta.y
        var extraY = 0f
        if (abs(offsetY) > THRESHOLD && delta.y != 0f && sign(delta.y) != sign(rawY)) {
            val canConsume = if (abs(rawY) <= abs(delta.y)) -rawY else delta.y
            if (abs(rawY) <= abs(delta.y)) {
                resetY()
                scrollY = delta.y - canConsume
                extraY = canConsume
            } else {
                dragY(canConsume)
                scrollY = 0f
                extraY = delta.y
            }
        }

        val consumed = performScroll(Offset(scrollX, scrollY))

        // Whatever the content could not take becomes the bounce.
        val leftoverX = scrollX - consumed.x
        val leftoverY = scrollY - consumed.y
        if (leftoverX != 0f) dragX(leftoverX)
        if (leftoverY != 0f) dragY(leftoverY)

        return Offset(
            extraX + consumed.x + leftoverX,
            extraY + consumed.y + leftoverY,
        )
    }

    // ---- fling -------------------------------------------------------------

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        val consumed = performFling(velocity)
        val leftoverX = velocity.x - consumed.x
        val leftoverY = velocity.y - consumed.y

        val needsSettle = abs(offsetX) > THRESHOLD || abs(offsetY) > THRESHOLD ||
            abs(leftoverX) > THRESHOLD || abs(leftoverY) > THRESHOLD
        if (!needsSettle) return

        // supervisorScope: a drag may cancel the settle job while the fling is
        // still suspended here; that must not cancel the scrollable's coroutine.
        supervisorScope {
            val job = launch {
                launch {
                    animate(
                        initialValue = offsetX,
                        targetValue = 0f,
                        initialVelocity = leftoverX,
                        animationSpec = spring(
                            dampingRatio = DAMPING,
                            stiffness = STIFFNESS,
                            visibilityThreshold = 0.5f,
                        ),
                    ) { value, _ -> offsetX = value }
                    resetX()
                }
                launch {
                    // Starting from 0 with a velocity is enough to make a fling
                    // that dies exactly at the edge still kick the band out.
                    animate(
                        initialValue = offsetY,
                        targetValue = 0f,
                        initialVelocity = leftoverY,
                        animationSpec = spring(
                            dampingRatio = DAMPING,
                            stiffness = STIFFNESS,
                            visibilityThreshold = 0.5f,
                        ),
                    ) { value, _ -> offsetY = value }
                    resetY()
                }
            }
            settleJob = job
            job.join()
        }
        settleJob = null
    }
}

/**
 * Provides [BouncyOverscrollEffect] to every scrollable below, the same way the
 * reference app swaps the platform stretch for its own rubber band at theme
 * level.
 */
val BouncyOverscrollFactory: OverscrollFactory = object : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = BouncyOverscrollEffect()

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = 1
}

private class BouncyOverscrollNode(
    private val effect: BouncyOverscrollEffect,
) : Modifier.Node(),
    LayoutModifierNode,
    CompositionLocalConsumerModifierNode {

    private var lastWidth = -1
    private var lastHeight = -1

    override fun onAttach() {
        super.onAttach()
        effect.bindInvalidatePlacement { invalidatePlacement() }
        updateRange()
    }

    override fun onDetach() {
        effect.bindInvalidatePlacement {}
        super.onDetach()
    }

    private fun updateRange() {
        val size = currentValueOf(LocalWindowInfo).containerSize
        if (size.width != lastWidth || size.height != lastHeight) {
            lastWidth = size.width
            lastHeight = size.height
            effect.updateRange(size.width.toFloat(), size.height.toFloat())
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        updateRange()
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            val x = round(effect.offsetX)
            val y = round(effect.offsetY)
            // Plain placement rather than placeWithLayer: the viewport is
            // already clipped by the scroll container, so an extra offscreen
            // layer buys nothing and costs a full-screen composite every frame
            // of the bounce. Offsets are whole pixels, so no sub-pixel
            // translation is lost.
            placeable.place(x.toInt(), y.toInt())
        }
    }
}
