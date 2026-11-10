package com.naoya0117.paperless.feature.uploads

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Writes a PDF whose pages are JPEG images, embedded as they are (DCTDecode): small files, and only one page
 * in memory at a time (Android's PdfDocument keeps every page's pixels until the end and stores them
 * uncompressed).
 */
internal class JpegPdf(
    private val file: File,
) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val offsets = mutableListOf<Long>()

    /** Per page: its page object and its image's width and height in points. */
    private val pages = mutableListOf<Int>()

    init {
        // The binary comment marks the file as binary for transfer tools.
        write("%PDF-1.4\n%âãÏÓ\n")
        // Objects 1 and 2 (catalog, page tree) are written last; their numbers are fixed.
        offsets += 0L
        offsets += 0L
    }

    /**
     * Adds a page showing a JPEG image of [pixelWidth] × [pixelHeight] pixels (written by [writeJpeg]), sized
     * to [pageWidth] × [pageHeight] points.
     */
    fun addPage(
        pixelWidth: Int,
        pixelHeight: Int,
        pageWidth: Float,
        pageHeight: Float,
        writeJpeg: (OutputStream) -> Unit,
    ) {
        val image = beginObject()
        write("<< /Type /XObject /Subtype /Image /Width $pixelWidth /Height $pixelHeight ")
        write("/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ")
        val lengthAt = out.filePointer
        write("0000000000 >>\nstream\n")
        val start = out.filePointer
        writeJpeg(RandomAccessOutput(out))
        val length = out.filePointer - start
        write("\nendstream\nendobj\n")
        val end = out.filePointer
        out.seek(lengthAt)
        write(length.toString().padStart(LENGTH_DIGITS, '0'))
        out.seek(end)

        val width = format(pageWidth)
        val height = format(pageHeight)
        val drawing = "q $width 0 0 $height 0 0 cm /Im0 Do Q"
        val content = beginObject()
        write("<< /Length ${drawing.length} >>\nstream\n$drawing\nendstream\nendobj\n")

        val page = beginObject()
        write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] ")
        write("/Resources << /XObject << /Im0 $image 0 R >> >> /Contents $content 0 R >>\nendobj\n")
        pages += page
    }

    /** Writes the page tree, the catalog and the cross-reference table. */
    fun finish() {
        offsets[1] = out.filePointer
        write(
            "2 0 obj\n<< /Type /Pages /Kids [${pages.joinToString(
                " ",
            ) { "$it 0 R" }}] /Count ${pages.size} >>\nendobj\n",
        )
        offsets[0] = out.filePointer
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val xref = out.filePointer
        write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write(it.toString().padStart(OFFSET_DIGITS, '0') + " 00000 n \n") }
        write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }

    override fun close() = out.close()

    /** Starts the next object; returns its number. */
    private fun beginObject(): Int {
        offsets += out.filePointer
        val number = offsets.size
        write("$number 0 obj\n")
        return number
    }

    private fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))

    private fun format(points: Float): String = "%.2f".format(java.util.Locale.ROOT, points)

    private class RandomAccessOutput(
        private val file: RandomAccessFile,
    ) : OutputStream() {
        override fun write(b: Int) = file.write(b)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = file.write(b, off, len)
    }

    private companion object {
        const val LENGTH_DIGITS = 10
        const val OFFSET_DIGITS = 10
    }
}
