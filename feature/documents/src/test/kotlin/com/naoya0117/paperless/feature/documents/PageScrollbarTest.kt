package com.naoya0117.paperless.feature.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageScrollbarTest {
    private fun position(
        first: Int,
        scrolled: Int = 0,
        atStart: Boolean = false,
        atEnd: Boolean = false,
    ) = scrollPosition(
        count = 10,
        first = first,
        scrolled = scrolled,
        firstSize = 1000,
        pageSize = 1000f,
        viewport = 2000,
        atStart = atStart,
        atEnd = atEnd,
    )

    @Test
    fun `the position goes from the top to the bottom through the pages`() {
        assertEquals(0f, position(0, atStart = true)!!.fraction)
        // 8 pages to scroll through (2 of the 10 are in view): halfway at page 4.
        assertEquals(0.5f, position(4)!!.fraction, 0.001f)
        assertEquals(0.5f + 0.5f / 8, position(4, scrolled = 500)!!.fraction, 0.001f)
        // The estimate may be off at the end, which counts as the bottom.
        assertEquals(1f, position(7, atEnd = true)!!.fraction)
        assertEquals(0.2f, position(4)!!.visible, 0.001f)
    }

    @Test
    fun `no scrollbar when every page is in view`() {
        assertNull(position(0, atStart = true, atEnd = true))
        assertNull(scrollPosition(0, 0, 0, 0, 0f, 2000, atStart = true, atEnd = false))
    }

    @Test
    fun `dragging the thumb goes to the page at that fraction`() {
        assertEquals(0 to 0f, scrollTarget(0f, 10, 2f))
        assertEquals(4, scrollTarget(0.5f, 10, 2f).first)
        val (page, into) = scrollTarget(0.5f + 0.5f / 8, 10, 2f)
        assertEquals(4, page)
        assertEquals(0.5f, into, 0.001f)
        assertEquals(8, scrollTarget(1f, 10, 2f).first)
        // Fewer pages than fit: the first.
        assertEquals(0, scrollTarget(1f, 1, 2f).first)
    }

    @Test
    fun `the page shown is the one in the middle, the first at the top and the last at the end`() {
        val pages = listOf(Triple(2, -300, 700), Triple(3, 712, 1712), Triple(4, 1724, 2724))

        assertEquals(3, currentPage(pages, middle = 1000, atStart = false, atEnd = false))
        // Between two pages: the nearer one.
        assertEquals(2, currentPage(pages, middle = 704, atStart = false, atEnd = false))
        assertEquals(2, currentPage(pages, middle = 1000, atStart = true, atEnd = false))
        assertEquals(4, currentPage(pages, middle = 1000, atStart = false, atEnd = true))
        assertNull(currentPage(emptyList(), middle = 1000, atStart = true, atEnd = true))
    }
}
