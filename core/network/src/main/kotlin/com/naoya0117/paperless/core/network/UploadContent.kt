package com.naoya0117.paperless.core.network

import java.io.InputStream

/**
 * A file to upload. [open] is called for every attempt (e.g. after a retry), so it must return a fresh
 * stream each time; the stream is closed after sending.
 *
 * @property mimeType e.g. `application/pdf`; the server detects the type itself, this is only a hint.
 * @property contentLength in bytes, if known; needed for progress percentages.
 */
public class UploadContent(
    public val fileName: String,
    public val mimeType: String? = null,
    public val contentLength: Long? = null,
    public val open: () -> InputStream,
) {
    init {
        require(fileName.isNotBlank()) { "fileName must not be blank" }
    }
}

/** Upload progress: [sent] bytes of [total] (`null` if unknown). */
public fun interface UploadProgress {
    public fun onProgress(
        sent: Long,
        total: Long?,
    )
}
