package com.naoya0117.paperless.feature.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Renders the pages of a PDF file one at a time (PdfRenderer allows only one open page). Pages are rendered
 * on demand at the width they are shown, so only visible pages take memory.
 */
class PdfPages private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {
    private val lock = Mutex()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Height / width per page, measured when first needed. */
    private val aspectRatios = arrayOfNulls<Float>(pageCount)

    /** Height relative to width of page [index] (e.g. about 1.41 for A4 portrait). */
    suspend fun aspectRatio(index: Int): Float =
        aspectRatios[index] ?: lock
            .withLock {
                withContext(Dispatchers.IO) {
                    check(!closed) { "closed" }
                    renderer.openPage(index).use { page -> page.height.toFloat() / page.width }
                }
            }.also { aspectRatios[index] = it }

    /** Page [index] as an image [width] pixels wide (at most [MAX_WIDTH]), on a white background. */
    suspend fun render(
        index: Int,
        width: Int,
    ): Bitmap =
        lock.withLock {
            withContext(Dispatchers.IO) {
                check(!closed) { "closed" }
                renderer.openPage(index).use { page ->
                    val bitmapWidth = width.coerceIn(1, MAX_WIDTH)
                    val bitmapHeight = (bitmapWidth.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                    aspectRatios[index] = page.height.toFloat() / page.width
                    createBitmap(bitmapWidth, bitmapHeight).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    override fun close() {
        // Waits for a page being rendered; the renderer must not be closed meanwhile.
        kotlinx.coroutines.runBlocking {
            lock.withLock {
                closed = true
                renderer.close()
                descriptor.close()
            }
        }
    }

    companion object {
        /** Enough for sharp text on large phones; bounds memory per page (about 8 MB at A4). */
        const val MAX_WIDTH: Int = 1440

        /**
         * Opens [file] for rendering.
         *
         * @throws java.io.IOException if it is not a PDF this device can render (e.g. damaged)
         * @throws SecurityException if it is password protected
         */
        suspend fun open(file: File): PdfPages =
            withContext(Dispatchers.IO) {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    PdfPages(descriptor, PdfRenderer(descriptor))
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    descriptor.close()
                    throw e
                }
            }
    }
}
