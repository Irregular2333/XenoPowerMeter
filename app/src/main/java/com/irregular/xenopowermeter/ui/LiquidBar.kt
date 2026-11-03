package com.irregular.xenopowermeter.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Refraction along the pill's rim, the way a slab of glass bends what is
 * behind its edge.
 *
 * This is what gives the bar its thickness. Blur alone only ever reads as
 * frosted plastic: crank it up and the surface goes milky, back it off and the
 * pill looks like a flat sticker. Bending the sampled content near the rim
 * (up to [refractionAmount] of pull over the outer [refractionHeight] dp) puts
 * a visible density change exactly on the edge, so the blur is free to stay
 * light and the content behind the glass stays legible.
 *
 * Same falloff as the reference: a circular map so the bend is strongest at
 * the very rim and reaches zero smoothly at `refractionHeight`, with the
 * sampling direction taken from the SDF gradient (i.e. always along the
 * inward normal, on the sides and the caps alike).
 */
internal const val BarRefractionShader = """
uniform shader content;
uniform float2 rectSize;
uniform float cornerRadius;
uniform float refractionHeight;
uniform float refractionAmount;

float sdRoundRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - (halfSize - float2(r, r));
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = rectSize * 0.5;
    float2 p = coord - halfSize;
    float sd = sdRoundRect(p, halfSize, cornerRadius);
    float rh = refractionHeight;
    if (-sd >= rh) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - (-sd) / rh) * refractionAmount;

    float eps = 0.75;
    float2 g = float2(
        sdRoundRect(p + float2(eps, 0.0), halfSize, cornerRadius) -
            sdRoundRect(p - float2(eps, 0.0), halfSize, cornerRadius),
        sdRoundRect(p + float2(0.0, eps), halfSize, cornerRadius) -
            sdRoundRect(p - float2(0.0, eps), halfSize, cornerRadius)
    );
    float gl = length(g);
    float2 grad = gl > 0.0001 ? g / gl : float2(0.0, 0.0);
    return content.eval(coord + d * grad);
}
"""

/**
 * Press state for the floating bar: how far into the press we are, and where
 * the finger is.
 *
 * Split into two animations on purpose. The scale rides [progress] and springs
 * back to rest on release; the halo's centre stays where the finger let go and
 * simply fades. If the light travelled back to the touch-down point it would
 * look like a bug, not a light.
 */
internal class BarPressState(private val scope: CoroutineScope) {

    private companion object {
        const val DAMPING = 0.5f
        const val STIFFNESS = 300f
    }

    val progress = Animatable(0f)
    val finger = Animatable(Offset.Zero, Offset.VectorConverter)

    private val pressSpec = spring<Float>(DAMPING, STIFFNESS, 0.001f)

    fun press(position: Offset) {
        scope.launch {
            launch { finger.snapTo(position) }
            launch { progress.animateTo(1f, pressSpec) }
        }
    }

    /**
     * Snap, don't animate: a spring per move event would trail the finger by a
     * frame or two and spawn a coroutine for every touch sample. The reference
     * snaps here and only springs on the way back.
     */
    fun moveTo(position: Offset) {
        scope.launch { finger.snapTo(position) }
    }

    fun release() {
        scope.launch { progress.animateTo(0f, pressSpec) }
    }
}

/**
 * Tracks a press anywhere on the bar, including drags along it, without
 * consuming the event: the tab's own clickable still has to fire.
 */
internal fun Modifier.barPressSource(state: BarPressState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            state.press(down.position)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                state.moveTo(change.position)
            }
            state.release()
        }
    }
