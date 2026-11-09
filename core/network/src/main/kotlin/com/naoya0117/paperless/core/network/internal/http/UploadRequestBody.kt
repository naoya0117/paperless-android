package com.naoya0117.paperless.core.network.internal.http

import com.naoya0117.paperless.core.network.UploadContent
import com.naoya0117.paperless.core.network.UploadProgress
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

/**
 * Streams [content], reporting progress. One-shot: OkHttp must not silently re-send an upload after a
 * connection failure (the server may already have received it and would reject the copy as a duplicate).
 *
 * Failures of the file or the progress callback are thrown as [UploadSourceException] — an IOException,
 * so OkHttp reports them to the call instead of rethrowing them on its dispatcher thread (which would
 * crash the app).
 */
internal class UploadRequestBody(
    private val content: UploadContent,
    private val progress: UploadProgress?,
) : RequestBody() {
    override fun contentType(): MediaType? = content.mimeType?.toMediaTypeOrNull() ?: OCTET_STREAM

    override fun contentLength(): Long = content.contentLength ?: -1

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val input = uploadSource { content.open() }
        input.source().use { source ->
            var sent = 0L
            report(0)
            while (true) {
                val read = uploadSource { source.read(sink.buffer, SEGMENT) }
                if (read < 0) break
                sent += read
                content.contentLength?.let {
                    if (sent >
                        it
                    ) {
                        throw UploadSourceException("file is longer than $it bytes")
                    }
                }
                sink.emitCompleteSegments()
                report(sent)
            }
            content.contentLength?.let {
                if (sent !=
                    it
                ) {
                    throw UploadSourceException("file has $sent bytes, expected $it")
                }
            }
            sink.flush()
            report(sent)
        }
    }

    private fun report(sent: Long) {
        progress?.let { callback -> uploadSource { callback.onProgress(sent, content.contentLength) } }
    }

    private companion object {
        const val SEGMENT = 64L * 1024
        val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()
    }
}

/** A failure of the upload's own input (not of the network); mapped to PaperlessException.LocalFile. */
internal class UploadSourceException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Runs [block], turning any failure into an [UploadSourceException]. */
private inline fun <T> uploadSource(block: () -> T): T =
    try {
        block()
    } catch (e: UploadSourceException) {
        throw e
    } catch (e: Exception) {
        throw UploadSourceException(e.message ?: e.javaClass.simpleName, e)
    }
