package com.naoya0117.paperless.feature.documents

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.naoya0117.paperless.core.data.CachedDocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Hands document files to other apps and saves them to the device's downloads. */
internal object DocumentFileExport {
    /** Where files are put for other apps (see `res/xml/document_file_paths.xml`). */
    private const val SHARED_DIRECTORY = "shared-documents"
    private const val AUTHORITY_SUFFIX = ".documentfiles"

    /** A file name without path separators or other characters file systems reject. */
    fun safeFileName(name: String): String =
        name
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            .trim()
            .trimStart('.')
            .ifBlank { "document" }
            .take(MAX_NAME_LENGTH)

    /**
     * A content URI for [file] under [name], readable by the app it is granted to. The file is copied (the
     * cached one has an internal name and may be deleted to keep the cache small); the copies of earlier
     * shares are deleted, so only the latest stays.
     */
    suspend fun shareable(
        context: Context,
        file: CachedDocumentFile,
        name: String,
    ): Uri =
        withContext(Dispatchers.IO) {
            val root = File(context.cacheDir, SHARED_DIRECTORY)
            root.deleteRecursively()
            // A directory per share: the file keeps its own name, which receiving apps show.
            val directory = File(root, System.nanoTime().toString())
            if (!directory.mkdirs()) throw IOException("Cannot create $directory")
            val copy = File(directory, safeFileName(name))
            file.file.copyTo(copy)
            FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, copy)
        }

    /** Saves [file] as [name] to the public Downloads folder (no permission needed on Android 10+). */
    suspend fun saveToDownloads(
        context: Context,
        file: CachedDocumentFile,
        name: String,
        mimeType: String?,
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeFileName(name))
                mimeType?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Cannot create a download")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Cannot write $uri")
            output.use { out -> file.file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private const val MAX_NAME_LENGTH = 200
}

/** A provider class of its own, so that it never clashes with another FileProvider in the merged manifest. */
class DocumentFileProvider : FileProvider()
