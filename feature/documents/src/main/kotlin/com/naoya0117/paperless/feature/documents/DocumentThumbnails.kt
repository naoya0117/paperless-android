package com.naoya0117.paperless.feature.documents

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.naoya0117.paperless.core.data.DocumentFileRepository
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.network.DocumentFileKind
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Coil model for a document's thumbnail. The image comes from [DocumentFileRepository] (disk cache, then the
 * server); Coil keeps it in memory by account, document and modification time, so rows scrolled back into
 * view show it at once.
 */
data class DocumentThumbnail(
    val accountId: String,
    val document: Document,
)

/** Registers the thumbnail loading; [files] is looked up when the first thumbnail is loaded. */
fun ImageLoader.Builder.documentThumbnails(files: () -> DocumentFileRepository): ImageLoader.Builder =
    components {
        add(ThumbnailKeyer())
        add(ThumbnailFetcher.Factory(files))
    }

private class ThumbnailKeyer : Keyer<DocumentThumbnail> {
    override fun key(
        data: DocumentThumbnail,
        options: Options,
    ): String = "thumbnail:${data.accountId}:${data.document.id}:${data.document.modified.toEpochMilli()}"
}

/** Failures (offline and not cached, not signed in, ...) end in Coil's error state: the placeholder stays. */
private class ThumbnailFetcher(
    private val thumbnail: DocumentThumbnail,
    private val files: DocumentFileRepository,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val cached = files.file(thumbnail.document, DocumentFileKind.THUMBNAIL)
        return SourceFetchResult(
            source = ImageSource(cached.file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = cached.contentType,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val files: () -> DocumentFileRepository,
    ) : Fetcher.Factory<DocumentThumbnail> {
        override fun create(
            data: DocumentThumbnail,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = ThumbnailFetcher(data, files())
    }
}
