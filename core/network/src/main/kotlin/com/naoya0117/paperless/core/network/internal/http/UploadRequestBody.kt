package com.naoya0117.paperless.core.network.internal.http

import com.naoya0117.paperless.core.network.UploadContent
import com.naoya0117.paperless.core.network.UploadProgress
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/** Streams [content] from a fresh stream on every write (OkHttp may retry), reporting progress. */
internal class UploadRequestBody(
    private val content: UploadContent,
    private val progress: UploadProgress?,
) : RequestBody() {
    override fun contentType(): MediaType? = content.mimeType?.toMediaTypeOrNull() ?: OCTET_STREAM

    override fun contentLength(): Long = content.contentLength ?: -1

    override fun writeTo(sink: BufferedSink) {
        content.open().source().use { source ->
            var sent = 0L
            progress?.onProgress(0, content.contentLength)
            while (true) {
                val read = source.read(sink.buffer, SEGMENT)
                if (read < 0) break
                sink.emitCompleteSegments()
                sent += read
                progress?.onProgress(sent, content.contentLength)
            }
        }
    }

    private companion object {
        const val SEGMENT = 64L * 1024
        val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()
    }
}
