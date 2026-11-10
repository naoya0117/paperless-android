package com.naoya0117.paperless.feature.uploads

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
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
)

data class ScanState(
    val pages: List<ScanPage> = emptyList(),
    /** Black and white with more contrast, like a scanner (for text documents). */
    val enhance: Boolean = true,
    val title: String = "",
    /** Pages are being read (picked images) or the PDF is being made. */
    val working: Boolean = false,
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
                            .map { (name, rotation) -> ScanPage(File(directory, name), rotation) }
                            .filter { it.file.exists() },
                    enhance = handle.get<Boolean>(ENHANCE_KEY) ?: true,
                    title = handle.get<String>(TITLE_KEY).orEmpty(),
                ),
            )
        val state: StateFlow<ScanState> = mutableState.asStateFlow()

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

        fun rotate(index: Int) = changePage(index) { it.copy(rotation = (it.rotation + QUARTER) % FULL) }

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
                        ScanEvent.Queued
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: NotSignedInException) {
                        ScanEvent.NotSignedIn
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        ScanEvent.Failed
                    } finally {
                        mutableState.update { it.copy(working = false) }
                    }
                eventChannel.trySend(event)
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
            handle[ENHANCE_KEY] = state.enhance
            handle[TITLE_KEY] = state.title
        }

        /** The PDF, a page per image at its size (limited, so that the file stays small enough). */
        private fun makePdf(state: ScanState): File {
            val document = PdfDocument()
            try {
                state.pages.forEachIndexed { index, page ->
                    val bitmap = loadPage(page, state.enhance)
                    try {
                        // Points (1/72 inch) for an A4-ish page at the image's proportions.
                        val scale = PAGE_WIDTH_POINTS / minOf(bitmap.width, bitmap.height).toFloat()
                        val width = (bitmap.width * scale).toInt()
                        val height = (bitmap.height * scale).toInt()
                        val pdfPage =
                            document.startPage(
                                PdfDocument.PageInfo.Builder(width, height, index + 1).create(),
                            )
                        pdfPage.canvas.drawBitmap(
                            bitmap,
                            Matrix().apply { setScale(scale, scale) },
                            Paint(Paint.FILTER_BITMAP_FLAG),
                        )
                        document.finishPage(pdfPage)
                    } finally {
                        bitmap.recycle()
                    }
                }
                val file = File(directory, "scan-${System.nanoTime()}.pdf")
                file.outputStream().use { document.writeTo(it) }
                return file
            } finally {
                document.close()
            }
        }

        private fun loadPage(
            page: ScanPage,
            enhance: Boolean,
        ): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val decoded =
                BitmapFactory.decodeFile(page.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: throw IOException("Not an image: ${page.file}")
            val rotation = page.rotation + exifRotation(page.file)
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (turned !== decoded) decoded.recycle()
            if (!enhance) return turned
            val result = Bitmap.createBitmap(turned.width, turned.height, Bitmap.Config.ARGB_8888)
            Canvas(result).drawBitmap(
                turned,
                0f,
                0f,
                Paint().apply {
                    colorFilter =
                        ColorMatrixColorFilter(documentFilter())
                },
            )
            turned.recycle()
            return result
        }

        companion object {
            private const val SCANS = "scans"
            private const val SESSION_KEY = "scan.session"
            private const val PAGES_KEY = "scan.pages"
            private const val ROTATIONS_KEY = "scan.rotations"
            private const val ENHANCE_KEY = "scan.enhance"
            private const val TITLE_KEY = "scan.title"
            private const val PDF = "application/pdf"
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
                    title.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").ifEmpty {
                        "Scan " +
                            now.format(NAME_TIME)
                    }
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
