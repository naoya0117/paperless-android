package com.naoya0117.paperless.core.network

import java.io.Closeable
import java.io.InputStream

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
 * A document file being received. Read [stream] and close this object (e.g. with `use`); the content is
 * streamed, not held in memory.
 */
public class DocumentFile internal constructor(
    public val contentType: String?,
    /** From `Content-Disposition`; `null` if the server did not send one. */
    public val fileName: String?,
    /** `null` if unknown (e.g. chunked transfer). */
    public val contentLength: Long?,
    public val stream: InputStream,
) : Closeable {
    override fun close(): Unit = stream.close()
}
