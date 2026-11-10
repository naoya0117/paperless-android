package com.naoya0117.paperless.feature.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.sqrt

/**
 * Renders the pages of a PDF file one at a time (PdfRenderer allows only one open page). Pages are rendered
 * on demand at the width they are shown, so only visible pages take memory.
 */
class PdfPages private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {
    private val lock = Mutex()

    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Height / width per page, known once the page was rendered. */
    private val aspectRatios = arrayOfNulls<Float>(pageCount)

    /** Height relative to width of page [index] (about 1.41 for A4 portrait), or `null` until rendered. */
    fun knownAspectRatio(index: Int): Float? = aspectRatios[index]

    /**
     * Page [index] as an image [width] pixels wide (at most [MAX_WIDTH]), on a white background. Very tall
     * pages (e.g. receipts) are rendered narrower, so that no page takes more than [MAX_PIXELS].
     *
     * @throws IllegalStateException if closed meanwhile
     */
    suspend fun render(
        index: Int,
        width: Int,
    ): Bitmap =
        lock.withLock {
            withContext(Dispatchers.IO) {
                check(!closed) { "closed" }
                renderer.openPage(index).use { page ->
                    val aspect = validAspect(page.width, page.height)
                    aspectRatios[index] = aspect
                    val fitting = sqrt(MAX_PIXELS / aspect).toInt()
                    val bitmapWidth = minOf(width, MAX_WIDTH, fitting).coerceAtLeast(1)
                    val bitmapHeight = (bitmapWidth * aspect).toInt().coerceAtLeast(1)
                    createBitmap(bitmapWidth, bitmapHeight).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    /**
     * Stops rendering at once; the renderer is released in the background after a page being rendered
     * (which cannot be interrupted), so that closing never blocks the caller (e.g. the main thread).
     */
    override fun close() {
        if (closed) return
        closed = true
        CLOSER.launch {
            lock.withLock {
                renderer.close()
                descriptor.close()
            }
        }
    }

    companion object {
        /** Enough for sharp text on large phones. */
        const val MAX_WIDTH: Int = 1440

        /** At most about 25 MB per rendered page. */
        private const val MAX_PIXELS: Float = 1440f * 1440f * 3f

        /** A4 portrait, for pages that report no usable size. */
        const val A4_ASPECT: Float = 1.414f

        private val CLOSER = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun validAspect(
            width: Int,
            height: Int,
        ): Float {
            val aspect = height.toFloat() / width
            return if (aspect.isFinite() && aspect > 0f) aspect else A4_ASPECT
        }

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
