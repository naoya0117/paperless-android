package com.naoya0117.paperless.feature.uploads

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.ApplicationScope
import com.naoya0117.paperless.core.data.NotSignedInException
import com.naoya0117.paperless.core.data.UploadRepository
import com.naoya0117.paperless.core.data.UploadSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

/** A page of a scan: an image file in app storage, turned by [rotation] degrees (0, 90, 180, 270). */
data class ScanPage(
    val file: File,
    val rotation: Int = 0,
    /**
     * The part of the (turned) page to keep: its corners top left, top right, bottom right, bottom left, as
     * x, y fractions of the turned image (8 values); `null` for all of it. Not a rectangle when the paper
     * was photographed at an angle: the page is straightened then.
     */
    val corners: List<Float>? = null,
)

data class ScanState(
    val pages: List<ScanPage> = emptyList(),
    /** Black and white with more contrast, like a scanner (for text documents). */
    val enhance: Boolean = true,
    val title: String = "",
    /** Pages are being read (picked images) or the PDF is being made. */
    val working: Boolean = false,
    /** Queued for upload: the screen closes (when it is shown again, if it was not). */
    val done: Boolean = false,
)

sealed interface ScanEvent {
    /** Queued for upload: the screen closes. */
    data object Queued : ScanEvent

    data object NotSignedIn : ScanEvent

    /** The pages could not be read or the PDF not made (e.g. out of storage). */
    data object Failed : ScanEvent
}

/**
 * Pages taken with the camera or picked as images, put together into one PDF and queued for upload. The
 * page files live in the app's cache until the upload copied the PDF (or the scan is given up).
 */
@HiltViewModel
class ScanViewModel
    @Inject
    constructor(
        private val uploads: UploadRepository,
        @ApplicationContext private val context: Context,
        @ApplicationScope private val applicationScope: CoroutineScope,
        private val handle: SavedStateHandle,
    ) : ViewModel() {
        /** This scan's files (kept across process death, like the pages). */
        private val directory: File =
            File(
                File(context.cacheDir, SCANS),
                handle.get<String>(SESSION_KEY) ?: UUID.randomUUID().toString().also { handle[SESSION_KEY] = it },
            )

        private val mutableState =
            MutableStateFlow(
                ScanState(
                    pages =
                        handle
                            .get<Array<String>>(PAGES_KEY)
                            .orEmpty()
                            .zip(handle.get<IntArray>(ROTATIONS_KEY)?.toList().orEmpty())
                            .mapIndexed { index, (name, rotation) ->
                                ScanPage(
                                    File(directory, name),
                                    rotation,
                                    savedCorners(handle.get<FloatArray>(CORNERS_KEY), index),
                                )
                            }.filter { it.file.exists() },
                    enhance = handle.get<Boolean>(ENHANCE_KEY) ?: true,
                    title = handle.get<String>(TITLE_KEY).orEmpty(),
                    done = handle.get<Boolean>(DONE_KEY) ?: false,
                ),
            )
        val state: StateFlow<ScanState> = mutableState.asStateFlow()

        init {
            // Scans given up without discarding (e.g. the app was closed) are deleted after a day.
            applicationScope.launch(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                directory.parentFile?.listFiles()?.forEach { old ->
                    if (old != directory && now - old.lastModified() > STALE_AFTER_MILLIS) old.deleteRecursively()
                }
            }
        }

        private val eventChannel = Channel<ScanEvent>(Channel.BUFFERED)
        val events: Flow<ScanEvent> = eventChannel.receiveAsFlow()

        /** A file for the camera to write the next page to. */
        fun newPageFile(): File {
            directory.mkdirs()
            return File(directory, "page-${System.nanoTime()}.jpg")
        }

        fun addPage(file: File) = change { it.copy(pages = it.pages + ScanPage(file)) }

        /** Images picked from other apps: copied at once (the permission to read them may not last). */
        fun addImages(uris: List<Uri>) {
            if (uris.isEmpty()) return
            mutableState.update { it.copy(working = true) }
            viewModelScope.launch {
                val pages =
                    withContext(Dispatchers.IO) {
                        uris.mapNotNull { uri ->
                            runCatching {
                                val file = newPageFile()
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    file.outputStream().use { input.copyTo(it) }
                                } ?: throw IOException("Cannot open $uri")
                                ScanPage(file)
                            }.getOrNull()
                        }
                    }
                change { it.copy(pages = it.pages + pages, working = false) }
                if (pages.size < uris.size) eventChannel.trySend(ScanEvent.Failed)
            }
        }

        /** Turns the page a quarter clockwise (with its trimming). */
        fun rotate(index: Int) =
            changePage(
                index,
            ) { it.copy(rotation = (it.rotation + QUARTER) % FULL, corners = it.corners?.let(::turnCorners)) }

        /** Keeps only the part within [corners] (see [ScanPage.corners]); `null` for the whole page. */
        fun setCorners(
            index: Int,
            corners: List<Float>?,
        ) = changePage(index) {
            // Only a proper quadrilateral (see isValidQuad); the whole page is no trimming.
            it.copy(corners = corners?.takeIf { c -> isValidQuad(c) && c != FULL_PAGE })
        }

        /**
         * The page as it goes into the PDF, at most [maxSide] pixels long (e.g. small for a preview); [crop]
         * `false` for the whole (turned) page, e.g. to choose the part to keep.
         */
        suspend fun render(
            page: ScanPage,
            enhance: Boolean,
            maxSide: Int,
            crop: Boolean = true,
        ): Bitmap =
            withContext(Dispatchers.Default) {
                loadPage(if (crop) page else page.copy(corners = null), enhance, maxSide)
            }

        fun remove(index: Int) {
            val page = mutableState.value.pages.getOrNull(index) ?: return
            change { it.copy(pages = it.pages - page) }
            page.file.delete()
        }

        /** Moves the page by [by] places (e.g. -1: one earlier). */
        fun move(
            index: Int,
            by: Int,
        ) = change { state ->
            val target = (index + by).coerceIn(0, state.pages.lastIndex)
            if (index !in state.pages.indices || target == index) {
                state
            } else {
                state.copy(pages = state.pages.toMutableList().apply { add(target, removeAt(index)) })
            }
        }

        fun setEnhance(enhance: Boolean) = change { it.copy(enhance = enhance) }

        fun setTitle(title: String) = change { it.copy(title = title) }

        /** Makes one PDF of the pages and queues it; finished even if the screen is left. */
        fun upload() {
            val current = mutableState.value
            if (current.pages.isEmpty() || current.working) return
            mutableState.update { it.copy(working = true) }
            applicationScope.launch {
                val event =
                    try {
                        val accountId = uploads.activeAccountId() ?: throw NotSignedInException()
                        val pdf = withContext(Dispatchers.Default) { makePdf(current) }
                        try {
                            uploads.enqueue(
                                UploadSource(fileName(current.title), PDF) { pdf.inputStream() },
                                accountId = accountId,
                            )
                        } finally {
                            pdf.delete()
                        }
                        // The upload has its own copy: the pages are not needed any more.
                        directory.deleteRecursively()
                        mutableState.value = ScanState(done = true)
                        save(mutableState.value)
                        ScanEvent.Queued
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: NotSignedInException) {
                        ScanEvent.NotSignedIn
                    } catch (
                        // Also running out of memory with very large pages: told, not a crash.
                        @Suppress("TooGenericExceptionCaught") _: Throwable,
                    ) {
                        ScanEvent.Failed
                    } finally {
                        mutableState.update { it.copy(working = false) }
                    }
                eventChannel.trySend(event)
            }
        }

        /** Left without uploading or discarding (e.g. the account changed): the pages go. */
        override fun onCleared() {
            if (mutableState.value.pages.isNotEmpty() && !mutableState.value.working) {
                val dir = directory
                applicationScope.launch(Dispatchers.IO) { dir.deleteRecursively() }
            }
        }

        /** Given up: the pages are deleted. */
        fun discard() {
            mutableState.value = ScanState()
            save(mutableState.value)
            val dir = directory
            applicationScope.launch(Dispatchers.IO) { dir.deleteRecursively() }
        }

        private fun changePage(
            index: Int,
            change: (ScanPage) -> ScanPage,
        ) = change { state ->
            if (index !in state.pages.indices) {
                state
            } else {
                state.copy(pages = state.pages.toMutableList().apply { set(index, change(get(index))) })
            }
        }

        private fun change(block: (ScanState) -> ScanState) {
            mutableState.update(block)
            save(mutableState.value)
        }

        private fun save(state: ScanState) {
            handle[PAGES_KEY] = state.pages.map { it.file.name }.toTypedArray()
            handle[ROTATIONS_KEY] = state.pages.map { it.rotation }.toIntArray()
            // 8 values a page; NaN for the whole page.
            handle[CORNERS_KEY] = state.pages.flatMap { it.corners ?: List(CORNER_VALUES) { Float.NaN } }.toFloatArray()
            handle[ENHANCE_KEY] = state.enhance
            handle[TITLE_KEY] = state.title
            handle[DONE_KEY] = state.done
        }

        /** The PDF: a page per image, A4 wide, the image as JPEG (one page in memory at a time). */
        private fun makePdf(state: ScanState): File {
            val file = File(directory, "scan-${System.nanoTime()}.pdf")
            JpegPdf(file).use { pdf ->
                state.pages.forEach { page ->
                    val bitmap = loadPage(page, state.enhance)
                    try {
                        // A4 wide, but no side beyond what PDF allows (e.g. a very long strip).
                        val scale =
                            minOf(
                                PAGE_WIDTH_POINTS / minOf(bitmap.width, bitmap.height),
                                MAX_PAGE_POINTS / maxOf(bitmap.width, bitmap.height),
                            )
                        pdf.addPage(bitmap.width, bitmap.height, bitmap.width * scale, bitmap.height * scale) { out ->
                            if (!bitmap.compress(
                                    Bitmap.CompressFormat.JPEG,
                                    JPEG_QUALITY,
                                    out,
                                )
                            ) {
                                throw IOException("Cannot encode a page")
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                pdf.finish()
            }
            return file
        }

        /**
         * The page at most [maxSide] pixels long, turned, trimmed to its corners (straightened) and (if
         * [enhance]) filtered, in one drawing.
         */
        private fun loadPage(
            page: ScanPage,
            enhance: Boolean,
            maxSide: Int = MAX_SIDE,
        ): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.file.path, bounds)
            // A trimmed page needs more of the image's pixels: decoded so that its part still has maxSide
            // (its long side; a slanted side may be longer than its spread, up to √2).
            val corners = page.corners
            val keptSide =
                if (corners == null) {
                    maxOf(bounds.outWidth, bounds.outHeight).toFloat()
                } else {
                    val turnedSides = (page.rotation + exifRotation(page.file)) % FULL % (2 * QUARTER) != 0
                    val w = (if (turnedSides) bounds.outHeight else bounds.outWidth).toFloat()
                    val h = (if (turnedSides) bounds.outWidth else bounds.outHeight).toFloat()
                    maxOf(
                        corners.filterIndexed { i, _ -> i % 2 == 0 }.spread() * w,
                        corners.filterIndexed { i, _ -> i % 2 == 1 }.spread() * h,
                    ) *
                        SQRT2
                }
            var sample = 1
            while (keptSide / (sample * 2) >= maxSide) sample *= 2
            // Opaque: half the memory of ARGB.
            val decoded =
                BitmapFactory.decodeFile(
                    page.file.path,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    },
                ) ?: throw IOException("Not an image: ${page.file}")
            try {
                // Turned: an image of width × height.
                val rotation = (page.rotation + exifRotation(page.file)) % FULL
                val turned = rotation == QUARTER || rotation == 3 * QUARTER
                val width = (if (turned) decoded.height else decoded.width).toFloat()
                val height = (if (turned) decoded.width else decoded.height).toFloat()
                val matrix =
                    Matrix().apply {
                        postTranslate(-decoded.width / 2f, -decoded.height / 2f)
                        postRotate(rotation.toFloat())
                        postTranslate(width / 2f, height / 2f)
                    }
                // Trimmed: the corners onto a rectangle as large as the quadrilateral's sides.
                val quad =
                    (page.corners ?: FULL_PAGE)
                        .mapIndexed { i, v ->
                            v *
                                if (i % 2 ==
                                    0
                                ) {
                                    width
                                } else {
                                    height
                                }
                        }.toFloatArray()
                val outWidth = (distance(quad, 0, 1) + distance(quad, 3, 2)) / 2
                val outHeight = (distance(quad, 0, 3) + distance(quad, 1, 2)) / 2
                val scale = minOf(1f, maxSide / maxOf(outWidth, outHeight))
                val w = (outWidth * scale).toInt().coerceAtLeast(1)
                val h = (outHeight * scale).toInt().coerceAtLeast(1)
                val target = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
                // Not mappable (e.g. corners on a line): the whole page.
                val straighten = Matrix()
                if (straighten.setPolyToPoly(quad, 0, target, 0, 4)) matrix.postConcat(straighten)
                val paint =
                    Paint(Paint.FILTER_BITMAP_FLAG).apply {
                        if (enhance) colorFilter = ColorMatrixColorFilter(documentFilter())
                    }
                return Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).also { result ->
                    Canvas(result).apply {
                        drawColor(android.graphics.Color.WHITE)
                        drawBitmap(decoded, matrix, paint)
                    }
                }
            } finally {
                decoded.recycle()
            }
        }

        /** Between corners [a] and [b] (indexes of x, y pairs in [quad]). */
        private fun distance(
            quad: FloatArray,
            a: Int,
            b: Int,
        ): Float = kotlin.math.hypot(quad[2 * b] - quad[2 * a], quad[2 * b + 1] - quad[2 * a + 1])

        companion object {
            private const val SCANS = "scans"
            private const val SESSION_KEY = "scan.session"
            private const val PAGES_KEY = "scan.pages"
            private const val ROTATIONS_KEY = "scan.rotations"
            private const val CORNERS_KEY = "scan.corners"
            private const val CORNER_VALUES = 8

            /** The whole page (see [ScanPage.corners]). */
            val FULL_PAGE = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

            private fun savedCorners(
                values: FloatArray?,
                index: Int,
            ): List<Float>? {
                val start = index * CORNER_VALUES
                if (values == null || values.size < start + CORNER_VALUES) return null
                return values.copyOfRange(start, start + CORNER_VALUES).toList().takeIf { it.none(Float::isNaN) }
            }

            private const val MAX_PAGE_POINTS = 14_400f
            private const val SQRT2 = 1.4142135f

            /** The smallest part kept, of the page's area. */
            private const val MIN_AREA = 0.01f

            private fun List<Float>.spread(): Float = max() - min()

            /**
             * Whether [corners] make a usable quadrilateral: convex, in order (clockwise on screen), and not
             * tiny. Crossed or folded corners would mirror or garble the page.
             */
            fun isValidQuad(corners: List<Float>): Boolean {
                if (corners.size != CORNER_VALUES) return false
                val p = corners.chunked(2)
                // y points down: clockwise on screen gives positive cross products.
                val crosses =
                    (0 until 4).map { i ->
                        val a = p[i]
                        val b = p[(i + 1) % 4]
                        val c = p[(i + 2) % 4]
                        (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
                    }
                val area =
                    (0 until 4).sumOf { i -> (p[i][0] * p[(i + 1) % 4][1] - p[(i + 1) % 4][0] * p[i][1]).toDouble() } /
                        2
                return crosses.all { it > 0 } && area >= MIN_AREA
            }

            /** The corners after turning the page a quarter clockwise: (x, y) → (1 − y, x), the order kept. */
            internal fun turnCorners(corners: List<Float>): List<Float> {
                val points = corners.chunked(2).map { (x, y) -> listOf(1 - y, x) }
                // The old bottom left is the new top left.
                return (listOf(points[3]) + points.take(3)).flatten()
            }

            private const val ENHANCE_KEY = "scan.enhance"
            private const val TITLE_KEY = "scan.title"
            private const val DONE_KEY = "scan.done"
            private const val PDF = "application/pdf"
            private const val STALE_AFTER_MILLIS = 24L * 60 * 60 * 1000
            private const val JPEG_QUALITY = 85
            private const val QUARTER = 90
            private const val FULL = 360

            /** Longest side of a page image: sharp enough for text (about 200 dpi on A4). */
            private const val MAX_SIDE = 2400

            /** The shorter side of a page: A4's width. */
            private const val PAGE_WIDTH_POINTS = 595f

            private const val CONTRAST = 1.6f
            private const val BRIGHTNESS = -40f

            /** Grey with more contrast: paper whiter, text darker. */
            internal fun documentFilter(): ColorMatrix =
                ColorMatrix().apply {
                    setSaturation(0f)
                    postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                CONTRAST,
                                0f,
                                0f,
                                0f,
                                BRIGHTNESS,
                                0f,
                                CONTRAST,
                                0f,
                                0f,
                                BRIGHTNESS,
                                0f,
                                0f,
                                CONTRAST,
                                0f,
                                BRIGHTNESS,
                                0f,
                                0f,
                                0f,
                                1f,
                                0f,
                            ),
                        ),
                    )
                }

            /** "Scan 2026-11-10 14.05.pdf" without a title. */
            internal fun fileName(
                title: String,
                now: LocalDateTime = LocalDateTime.now(),
            ): String {
                val name =
                    title
                        .trim()
                        .removeSuffix(".pdf")
                        .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
                        .ifEmpty { "Scan " + now.format(NAME_TIME) }
                return "$name.pdf"
            }

            private val NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")

            /** How much the camera's image must be turned (from its EXIF orientation). */
            private fun exifRotation(file: File): Int =
                runCatching {
                    when (
                        androidx.exifinterface.media
                            .ExifInterface(file.path)
                            .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
                    ) {
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> QUARTER
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 2 * QUARTER
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 3 * QUARTER
                        else -> 0
                    }
                }.getOrDefault(0)
        }
    }
