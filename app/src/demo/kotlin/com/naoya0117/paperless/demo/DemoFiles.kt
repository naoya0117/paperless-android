package com.naoya0117.paperless.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Draws the demo documents' files: a thumbnail (PNG), and the document itself as a PDF with real text (so
 * that searching in the viewer works) or, for image documents, as a JPEG. Results are kept by document,
 * kind and modification time.
 */
class DemoFiles {
    class File(
        val bytes: ByteArray,
        val contentType: String,
        val name: String,
    )

    private val cache = HashMap<String, File>()

    fun render(
        document: JSONObject,
        kind: String,
    ): File {
        val key = "${document.getLong("id")}:$kind:${document.optString("modified")}"
        return cache.getOrPut(key) { draw(document, kind) }
    }

    fun size(
        document: JSONObject,
        kind: String,
    ): Long = render(document, kind).bytes.size.toLong()

    private fun draw(
        document: JSONObject,
        kind: String,
    ): File {
        val name = document.optString("original_file_name").ifEmpty { "document.pdf" }
        return when {
            kind == "thumb" ->
                File(
                    bitmap(document, THUMB_WIDTH).encode(Bitmap.CompressFormat.PNG),
                    "image/png",
                    "thumbnail.png",
                )
            document.optString("mime_type").startsWith("image/") ->
                File(bitmap(document, IMAGE_WIDTH).encode(Bitmap.CompressFormat.JPEG), "image/jpeg", name)
            else -> File(pdf(document), "application/pdf", name.substringBeforeLast('.') + ".pdf")
        }
    }

    /** The first page as an image, [width] pixels wide (A4 proportions). */
    private fun bitmap(
        document: JSONObject,
        width: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, (width * A4_RATIO).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width / PAGE_WIDTH, width / PAGE_WIDTH)
        drawPage(canvas, document, page = 1)
        return bitmap
    }

    private fun pdf(document: JSONObject): ByteArray {
        val pdf = PdfDocument()
        try {
            val pages = document.optInt("page_count", 1).coerceIn(1, MAX_PAGES)
            for (number in 1..pages) {
                val page =
                    pdf.startPage(
                        PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), number).create(),
                    )
                drawPage(page.canvas, document, number)
                pdf.finishPage(page)
            }
            return ByteArrayOutputStream().also(pdf::writeTo).toByteArray()
        } finally {
            pdf.close()
        }
    }

    /** One page in PDF points (A4, 595 × 842). */
    private fun drawPage(
        canvas: Canvas,
        document: JSONObject,
        page: Int,
    ) {
        canvas.drawColor(Color.WHITE)
        val lines = document.optString("content").lines()
        val sender = lines.firstOrNull().orEmpty()
        canvas.drawRect(0f, 0f, PAGE_WIDTH, 8f, paint(ACCENT))
        if (page == 1) {
            canvas.drawText(sender, MARGIN, 80f, paint(ACCENT, 20f, bold = true))
            canvas.drawText(document.optString("created").take(10), PAGE_WIDTH - MARGIN - 90f, 80f, paint(GRAY, 12f))
            canvas.drawText(document.optString("title"), MARGIN, 150f, paint(Color.BLACK, 24f, bold = true))
            var y = 200f
            for (line in lines.drop(1)) {
                canvas.drawText(line, MARGIN, y, paint(DARK, 15f))
                y += 26f
            }
            y += 20f
            repeat(FILLER_LINES) { i ->
                canvas.drawRect(
                    MARGIN,
                    y + i * 22f,
                    PAGE_WIDTH - MARGIN - (i % 3) * 60f,
                    y + i * 22f + 8f,
                    paint(LIGHT),
                )
            }
        } else {
            canvas.drawText(document.optString("title"), MARGIN, 70f, paint(GRAY, 12f))
            repeat(FILLER_LINES * 2) { i ->
                canvas.drawRect(
                    MARGIN,
                    110f + i * 22f,
                    PAGE_WIDTH - MARGIN - (i % 4) * 45f,
                    118f + i * 22f,
                    paint(LIGHT),
                )
            }
        }
        canvas.drawText(
            "$page / ${document.optInt("page_count", 1)}",
            PAGE_WIDTH / 2 - 12f,
            PAGE_HEIGHT - 30f,
            paint(GRAY, 10f),
        )
    }

    private fun paint(
        color: Int,
        size: Float = 12f,
        bold: Boolean = false,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun Bitmap.encode(format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream()
            .also { compress(format, JPEG_QUALITY, it) }
            .toByteArray()
            .also { recycle() }

    private companion object {
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val A4_RATIO = PAGE_HEIGHT / PAGE_WIDTH
        const val MARGIN = 56f
        const val THUMB_WIDTH = 400
        const val IMAGE_WIDTH = 1240
        const val MAX_PAGES = 20
        const val FILLER_LINES = 12
        const val JPEG_QUALITY = 90
        val ACCENT = Color.rgb(0x17, 0x54, 0x1F)
        val DARK = Color.rgb(0x33, 0x33, 0x33)
        val GRAY = Color.rgb(0x88, 0x88, 0x88)
        val LIGHT = Color.rgb(0xE6, 0xE6, 0xE6)
    }
}
