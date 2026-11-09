package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.network.internal.http.toPaperlessException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** Which file of a document to open. */
public enum class DocumentFileKind {
    /** Small preview image (WebP). */
    THUMBNAIL,

    /** The file for display: the archived PDF if there is one, otherwise the original. */
    PREVIEW,

    /** The file for saving: the archived PDF if there is one, otherwise the original. */
    DOWNLOAD,

    /** The file as uploaded. */
    ORIGINAL,
}

/**
 * A document file being received; the content is streamed, not held in memory. Always close it (e.g. with
 * `use`), also when not reading everything, so the connection is released.
 *
 * Prefer [copyTo] or [readBytes]: they read on [Dispatchers.IO], stop when the coroutine is cancelled and
 * report failures as [PaperlessException]. Reading [stream] directly blocks the calling thread, ignores
 * cancellation (only [close] or the read timeout interrupts it) and throws plain [IOException]s.
 */
public class DocumentFile internal constructor(
    public val contentType: String?,
    /**
     * From `Content-Disposition`, reduced to a plain file name (no directories or control characters);
     * `null` if the server did not send a usable one.
     */
    public val fileName: String?,
    /** `null` if unknown (e.g. chunked transfer). */
    public val contentLength: Long?,
    public val stream: InputStream,
) : Closeable {
    /** Copies the whole content to [out]; returns the number of bytes copied. */
    public suspend fun copyTo(out: OutputStream): Long =
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
            } catch (e: IOException) {
                throw e.toPaperlessException()
            }
            total
        }

    /** Reads the whole content into memory; meant for small files such as thumbnails. */
    public suspend fun readBytes(): ByteArray = ByteArrayOutputStream().also { copyTo(it) }.toByteArray()

    override fun close(): Unit = stream.close()

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
