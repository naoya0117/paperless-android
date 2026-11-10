package com.naoya0117.paperless.feature.documents

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.models.PageMatchBounds
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val file: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {
    /** Android 12–14: a second renderer for searching only (opened on the first search). */
    private var searcher: Closeable? = null
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
     * Where [query] occurs, page by page (Android 15+: PdfRenderer finds text in the PDF's text layer, e.g. the
     * OCR layer of archived documents). Other work (rendering) may run between pages.
     *
     * @throws IllegalStateException if closed meanwhile
     */
    @SuppressLint("NewApi") // canSearchPages checks the API level and the SDK extension.
    suspend fun search(query: String): List<SearchMatch.InPage> {
        check(canSearchPages) { "Page search needs Android 15, or 12+ with SDK extension 13" }
        val matches = mutableListOf<SearchMatch.InPage>()
        for (index in 0 until pageCount) {
            if (matches.size >= MAX_TEXT_MATCHES) break
            currentCoroutineContext().ensureActive()
            matches +=
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        check(!closed) { "closed" }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            renderer.openPage(index).use { page ->
                                pageMatches(index, page.width, page.height, page.searchText(query))
                            }
                        } else {
                            preV().openPage(index).use { page ->
                                pageMatches(index, page.width, page.height, page.searchText(query))
                            }
                        }
                    }
                }
        }
        return matches.take(MAX_TEXT_MATCHES)
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = PREV_EXTENSION)
    private fun preV(): PdfRendererPreV =
        (searcher as? PreVSearcher)?.renderer
            ?: PreVSearcher(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            ).also { searcher = it }.renderer

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = PREV_EXTENSION)
    private class PreVSearcher(
        private val descriptor: ParcelFileDescriptor,
    ) : Closeable {
        val renderer: PdfRendererPreV =
            try {
                PdfRendererPreV(descriptor)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                descriptor.close()
                throw e
            }

        override fun close() {
            renderer.close()
            descriptor.close()
        }
    }

    /** The matches of a page, as fractions of its size; its aspect ratio is known then too. */
    @RequiresExtension(extension = Build.VERSION_CODES.S, version = PREV_EXTENSION)
    private fun pageMatches(
        index: Int,
        pageWidth: Int,
        pageHeight: Int,
        found: List<PageMatchBounds>,
    ): List<SearchMatch.InPage> {
        // Known now, so that scrolling to a match on a page not shown yet is right.
        aspectRatios[index] = validAspect(pageWidth, pageHeight)
        val width = pageWidth.toFloat().takeIf { it > 0 } ?: 1f
        val height = pageHeight.toFloat().takeIf { it > 0 } ?: 1f
        return found.map { match ->
            SearchMatch.InPage(
                page = index,
                bounds =
                    match.bounds.map {
                        PageRect(
                            it.left / width,
                            it.top / height,
                            it.right / width,
                            it.bottom / height,
                        )
                    },
            )
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
                searcher?.close()
            }
        }
    }

    companion object {
        /** The SDK extension (of Android 12) with PdfRendererPreV, which can search text. */
        private const val PREV_EXTENSION = 13

        /**
         * Whether [search] can find text on the pages: Android 15+, or Android 12–14 updated with the PDF
         * renderer of SDK extension 13 (through system updates).
         */
        val canSearchPages: Boolean by lazy {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ||
                (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PREV_EXTENSION
                )
        }

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
                    PdfPages(file, descriptor, PdfRenderer(descriptor))
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    descriptor.close()
                    throw e
                }
            }
    }
}
