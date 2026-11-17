package com.naoya0117.paperless.feature.documents

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** How far the content is zoomed in (1 = not zoomed) and moved (in pixels, never past its edges). */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The size of the view (set by [zoomable] once laid out). */
    internal var size = IntSize.Zero

    /** Zooms by [zoom] around [centroid] (it stays where it is) and moves by [pan], within [size]. */
    fun transform(
        zoom: Float,
        pan: Offset,
        centroid: Offset,
        size: IntSize = this.size,
    ) {
        val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
        val factor = newScale / scale
        val moved = centroid - (centroid - offset) * factor + pan
        offset = moved.within(newScale, size)
        scale = newScale
    }

    /**
     * Moves the zoomed view by [delta] (a one-finger drag, in the view's pixels) as far as its edges allow.
     *
     * @return the vertical part that is left at the top or bottom edge, in the content's own pixels (unzoomed), for
     *   the content to scroll by: dragging on past the edge of a page moves on through the pages
     */
    fun pan(delta: Offset): Float {
        val moved = (offset + delta).within(scale, size)
        val left = delta.y - (moved.y - offset.y)
        offset = moved
        return left / scale
    }

    /** The content covers the view at every scale: no empty space beside it. */
    private fun Offset.within(
        scale: Float,
        size: IntSize,
    ) = Offset(x.coerceIn(size.width * (1 - scale), 0f), y.coerceIn(size.height * (1 - scale), 0f))

    /** A double tap: zooms in around [point] (in the view's pixels), or back out if zoomed. */
    suspend fun toggle(point: Offset) = animateTo(if (scale > 1f) 1f else DOUBLE_TAP_SCALE, point)

    /** Zooms to [target] around [centroid] in a short animation. */
    suspend fun animateTo(
        target: Float,
        centroid: Offset,
    ) {
        animate(scale, target, animationSpec = tween(ZOOM_MILLIS)) { value, _ ->
            transform(value / scale, Offset.Zero, centroid)
        }
        settle()
    }

    /** At the end of a gesture: barely zoomed counts as not zoomed (not during it, or slow pinches never start). */
    fun settle() {
        if (scale <= 1f + SNAP) reset()
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    companion object {
        const val MAX_SCALE = 4f

        /** How far a double tap zooms in. */
        const val DOUBLE_TAP_SCALE = 2.5f

        private const val ZOOM_MILLIS = 250

        /** Close enough to 1 to count as not zoomed. */
        private const val SNAP = 0.02f
    }
}

/**
 * Pinch to zoom and two-finger pan. A single finger is left to the content (e.g. scrolling a list) unless zoomed
 * in: then it moves the zoomed view in any direction, and beyond its top or bottom edge scrolls [scrollable] (the
 * content), with a fling when let go.
 *
 * Double taps are left to the content (with its single taps), which calls [ZoomState.toggle].
 */
fun Modifier.zoomable(
    state: ZoomState,
    scrollable: ScrollableState? = null,
): Modifier =
    clipToBounds()
        .onSizeChanged { state.size = it }
        .pointerInput(state, scrollable) {
            val decay = splineBasedDecay<Offset>(this)
            var fling: Job? = null
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A finger on the screen stops a fling.
                    fling?.cancel()
                    val velocity = VelocityTracker()
                    velocity.addPosition(down.uptimeMillis, down.position)
                    var slop = Offset.Zero
                    var panning = false
                    try {
                        do {
                            // Before the content, so that a pinch (or a pan) does not also scroll it.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                state.transform(
                                    event.calculateZoom(),
                                    event.calculatePan(),
                                    event.calculateCentroid(),
                                    size,
                                )
                                event.changes.forEach { it.consume() }
                                panning = false
                                slop = Offset.Zero
                            } else if (pressed.size == 1 && state.scale > 1f) {
                                val change = pressed.single()
                                velocity.addPosition(change.uptimeMillis, change.position)
                                val delta = change.positionChange()
                                if (!panning) {
                                    // Not on a tap (a double tap zooms out).
                                    slop += delta
                                    panning = slop.getDistance() > viewConfiguration.touchSlop
                                }
                                if (panning) {
                                    val left = state.pan(delta)
                                    scrollable?.dispatchRawDelta(-left)
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        state.settle()
                    }
                    if (panning) {
                        val (x, y) = velocity.calculateVelocity()
                        fling =
                            launch {
                                var last = Offset.Zero
                                Animatable(Offset.Zero, Offset.VectorConverter).animateDecay(Offset(x, y), decay) {
                                    val left = state.pan(value - last)
                                    scrollable?.dispatchRawDelta(-left)
                                    last = value
                                }
                            }
                    }
                }
            }
        }.graphicsLayer {
            scaleX = state.scale
            scaleY = state.scale
            translationX = state.offset.x
            translationY = state.offset.y
            transformOrigin = TransformOrigin(0f, 0f)
        }
