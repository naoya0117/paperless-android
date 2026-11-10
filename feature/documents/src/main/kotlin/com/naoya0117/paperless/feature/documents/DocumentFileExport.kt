package com.naoya0117.paperless.feature.documents

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.naoya0117.paperless.core.data.CachedDocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Hands document files to other apps and saves them to the device's downloads. */
object DocumentFileExport {
    /** Where files are put for other apps (see `res/xml/document_file_paths.xml`). */
    private const val SHARED_DIRECTORY = "shared-documents"
    private const val AUTHORITY_SUFFIX = ".documentfiles"

    /** File systems allow 255 bytes per name; some room is left (e.g. for MediaStore's " (1)"). */
    private const val MAX_NAME_BYTES = 200

    /** Copies handed out are kept this long: the receiving app may read them later (e.g. when sending a mail). */
    private val SHARED_FILE_LIFETIME_MILLIS = TimeUnit.HOURS.toMillis(1)

    /**
     * A file name without path separators or characters file systems reject, at most [MAX_NAME_BYTES] bytes
     * (UTF-8) long, keeping its extension.
     */
    fun safeFileName(name: String): String {
        val clean =
            name
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
                .trim()
                .trimStart('.')
                .ifBlank { "document" }
        if (clean.toByteArray().size <= MAX_NAME_BYTES) return clean
        val dot = clean.lastIndexOf('.')
        val extension = if (dot > 0 && clean.length - dot <= MAX_EXTENSION_LENGTH) clean.substring(dot) else ""
        var base = clean.dropLast(extension.length)
        while ((base + extension).toByteArray().size > MAX_NAME_BYTES) base = base.dropLast(1)
        // Not ending in the middle of a surrogate pair.
        if (base.lastOrNull()?.isHighSurrogate() == true) base = base.dropLast(1)
        return base.trimEnd() + extension
    }

    /** [name], or the document's title with an extension for [mimeType] if the server gave none. */
    fun fileName(
        name: String?,
        title: String,
        mimeType: String?,
    ): String {
        if (!name.isNullOrBlank()) return name
        val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (extension == null) title else "$title.$extension"
    }

    /**
     * A content URI for [file] under [name], readable by the app it is granted to. The file is copied (the
     * cached one has an internal name and may be deleted to keep the cache small). Copies handed out before
     * are deleted after a while.
     */
    suspend fun shareable(
        context: Context,
        file: CachedDocumentFile,
        name: String,
    ): Uri =
        withContext(Dispatchers.IO) {
            val root = File(context.cacheDir, SHARED_DIRECTORY)
            val now = System.currentTimeMillis()
            root.listFiles()?.filter { now - it.lastModified() > SHARED_FILE_LIFETIME_MILLIS }?.forEach {
                it.deleteRecursively()
            }
            // A directory per share: the file keeps its own name, which receiving apps show.
            val directory = File(root, System.nanoTime().toString())
            if (!directory.mkdirs()) throw IOException("Cannot create $directory")
            val copy = File(directory, safeFileName(name))
            file.file.copyTo(copy)
            FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, copy)
        }

    /** Deletes all copies handed to other apps, e.g. when signing out. */
    fun clearShared(context: Context) {
        File(context.cacheDir, SHARED_DIRECTORY).deleteRecursively()
    }

    /**
     * Saves [file] as [name] to the public Downloads folder (no permission needed on Android 10+).
     *
     * @return the name it was saved under (made unique by the system if the name was taken)
     */
    suspend fun saveToDownloads(
        context: Context,
        file: CachedDocumentFile,
        name: String,
        mimeType: String?,
    ): String =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val requested = safeFileName(name)
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, requested)
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
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: requested
        }

    private const val MAX_EXTENSION_LENGTH = 10
}

/** A provider class of its own, so that it never clashes with another FileProvider in the merged manifest. */
class DocumentFileProvider : FileProvider()
