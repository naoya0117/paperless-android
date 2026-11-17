package com.naoya0117.paperless.feature.documents

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomStateTest {
    private val size = IntSize(1000, 2000)

    private fun zoomed(scale: Float) =
        ZoomState().apply {
            this.size = this@ZoomStateTest.size
            transform(scale, Offset.Zero, Offset(500f, 1000f))
        }

    @Test
    fun `a one-finger drag moves the zoomed view within its edges`() {
        val zoom = zoomed(2f)
        assertEquals(Offset(-500f, -1000f), zoom.offset)

        assertEquals(0f, zoom.pan(Offset(-100f, 200f)))
        assertEquals(Offset(-600f, -800f), zoom.offset)

        // Sideways it stops at the edge (there is nothing to scroll sideways).
        assertEquals(0f, zoom.pan(Offset(5000f, 0f)))
        assertEquals(0f, zoom.offset.x)
    }

    @Test
    fun `dragging on past the top or bottom edge is left for the pages to scroll, unzoomed`() {
        val zoom = zoomed(2f)

        // 1000 px to the top edge; the other 400 view pixels are 200 content pixels.
        assertEquals(400f / 2, zoom.pan(Offset(0f, 1400f)))
        assertEquals(0f, zoom.offset.y)

        // Up: 2000 px to the bottom edge (2000 * (1 - 2)), then 100 left.
        assertEquals(-100f / 2, zoom.pan(Offset(0f, -2100f)))
        assertEquals(-2000f, zoom.offset.y)
    }

    @Test
    fun `not zoomed, a drag is all left for the pages`() {
        val zoom = ZoomState().apply { this.size = this@ZoomStateTest.size }

        assertEquals(300f, zoom.pan(Offset(50f, 300f)))
        assertEquals(Offset.Zero, zoom.offset)
    }
}
