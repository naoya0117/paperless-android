package com.naoya0117.paperless.feature.documents

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
import androidx.compose.ui.unit.IntSize

/** How far the content is zoomed in (1 = not zoomed) and moved (in pixels, never past its edges). */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** Zooms by [zoom] around [centroid] (it stays where it is) and moves by [pan], within [size]. */
    fun transform(
        zoom: Float,
        pan: Offset,
        centroid: Offset,
        size: IntSize,
    ) {
        val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
        val factor = newScale / scale
        val moved = centroid - (centroid - offset) * factor + pan
        // The content covers the view at every scale: no empty space beside it.
        offset =
            Offset(
                moved.x.coerceIn(size.width * (1 - newScale), 0f),
                moved.y.coerceIn(size.height * (1 - newScale), 0f),
            )
        scale = newScale
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

        /** Close enough to 1 to count as not zoomed. */
        private const val SNAP = 0.02f
    }
}

/**
 * Pinch to zoom and two-finger pan; a single finger is left to the content (e.g. scrolling a list, which
 * then scrolls the zoomed content).
 */
fun Modifier.zoomable(state: ZoomState): Modifier =
    clipToBounds()
        .pointerInput(state) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                try {
                    do {
                        // Before the content, so that a pinch does not also scroll it.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            state.transform(
                                event.calculateZoom(),
                                event.calculatePan(),
                                event.calculateCentroid(),
                                size,
                            )
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                } finally {
                    state.settle()
                }
            }
        }.graphicsLayer {
            scaleX = state.scale
            scaleY = state.scale
            translationX = state.offset.x
            translationY = state.offset.y
            transformOrigin = TransformOrigin(0f, 0f)
        }
