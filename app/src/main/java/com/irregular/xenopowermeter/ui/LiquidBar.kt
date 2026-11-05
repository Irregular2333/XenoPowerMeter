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
