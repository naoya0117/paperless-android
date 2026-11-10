package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.network.DocumentFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where document files are cached: a cache directory the system may also clear, and the most it may hold.
 */
class DocumentFileStorage(
    val directory: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_BYTES: Long = 256L * 1024 * 1024
    }
}

/** A document file on disk. [file] stays valid until the cache is trimmed or the account signs out. */
data class CachedDocumentFile(
    val file: File,
    val contentType: String?,
    /** The server's file name, e.g. to show or to save the file under. */
    val fileName: String?,
)

/**
 * Document files (thumbnails, previews, downloads) of the active account, kept on disk so that they are
 * shown at once and offline. A file is cached per document, kind and modification time, so editing the
 * document on the server leads to a fresh download.
 */
@Singleton
class DocumentFileRepository
    @Inject
    constructor(
        private val accounts: AccountRepository,
        private val storage: DocumentFileStorage,
    ) {
        /** Downloads of the same file wait for each other; striped to bound memory. */
        private val locks = List(LOCK_STRIPES) { Mutex() }

        /** Serializes trimming and clearing, which walk the whole cache. */
        private val trimLock = Mutex()

        /**
         * The file of [document] (from the active account), downloaded if it is not cached yet.
         *
         * @throws NotSignedInException
         * @throws com.naoya0117.paperless.core.network.PaperlessException if it must be downloaded and that fails
         */
        suspend fun file(
            document: Document,
            kind: DocumentFileKind,
        ): CachedDocumentFile {
            val session = accounts.currentSession() ?: throw NotSignedInException()
            val directory = accountDirectory(session.account.id)
            val name = "${document.id}-${kind.name.lowercase()}-${document.modified.toEpochMilli()}"
            val file = File(directory, name)
            return locks[Math.floorMod(name.hashCode(), LOCK_STRIPES)].withLock {
                cached(file) ?: download(session, document, kind, file)
            }
        }

        /** Deletes the account's cached files, e.g. after signing out. */
        suspend fun clear(accountId: String) =
            trimLock.withLock { withContext(Dispatchers.IO) { accountDirectory(accountId).deleteRecursively() } }

        private suspend fun cached(file: File): CachedDocumentFile? =
            withContext(Dispatchers.IO) {
                val meta = metaFile(file)
                if (!file.isFile || !meta.isFile) return@withContext null
                // Marks it as recently used for trimming.
                file.setLastModified(System.currentTimeMillis())
                readMeta(file, meta)
            }

        private suspend fun download(
            session: AccountSession,
            document: Document,
            kind: DocumentFileKind,
            file: File,
        ): CachedDocumentFile {
            val directory = file.parentFile!!
            val partial = File(directory, "${file.name}.part")
            try {
                val (contentType, fileName) =
                    session.client.documents.openFile(document.id, kind).use { remote ->
                        withContext(Dispatchers.IO) { directory.mkdirs() }
                        withContext(Dispatchers.IO) { partial.outputStream() }.use { remote.copyTo(it) }
                        remote.contentType to remote.fileName
                    }
                val result = CachedDocumentFile(file, contentType, fileName)
                val stored =
                    withContext(NonCancellable) {
                        // Not after a sign-out: the account's files were deleted and must stay so.
                        accounts.writeData(session) {
                            withContext(Dispatchers.IO) {
                                writeMeta(result)
                                check(partial.renameTo(file)) { "Could not store $file" }
                                deleteOtherVersions(file)
                            }
                        }
                    }
                if (stored) trim(keep = file)
                return result
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { partial.delete() }
            }
        }

        /** Older modification times of the same document and kind (not downloads still in progress). */
        private fun deleteOtherVersions(file: File) {
            val prefix = file.name.substringBeforeLast('-') + "-"
            file.parentFile
                ?.listFiles { other ->
                    other.name.startsWith(prefix) &&
                        other.name != file.name &&
                        other != metaFile(file) &&
                        !other.name.endsWith(".part")
                }?.forEach { it.delete() }
        }

        /** Deletes the least recently used files until the cache fits [DocumentFileStorage.maxBytes]. */
        private suspend fun trim(keep: File) =
            trimLock.withLock {
                withContext(Dispatchers.IO) {
                    val files =
                        storage.directory
                            .walkTopDown()
                            .filter { it.isFile && !it.name.endsWith(META) && !it.name.endsWith(".part") }
                            .toList()
                    var total = files.sumOf { it.length() + metaFile(it).length() }
                    for (oldest in files.sortedBy { it.lastModified() }) {
                        if (total <= storage.maxBytes) break
                        if (oldest == keep) continue
                        total -= oldest.length() + metaFile(oldest).length()
                        metaFile(oldest).delete()
                        oldest.delete()
                    }
                }
            }

        private fun accountDirectory(accountId: String) = File(storage.directory, accountId)

        private fun metaFile(file: File) = File(file.parentFile, file.name + META)

        private fun writeMeta(file: CachedDocumentFile) {
            val properties = Properties()
            file.contentType?.let { properties.setProperty(CONTENT_TYPE, it) }
            file.fileName?.let { properties.setProperty(FILE_NAME, it) }
            metaFile(file.file).outputStream().use { properties.store(it, null) }
        }

        private fun readMeta(
            file: File,
            meta: File,
        ): CachedDocumentFile {
            val properties = Properties()
            meta.inputStream().use { properties.load(it) }
            return CachedDocumentFile(file, properties.getProperty(CONTENT_TYPE), properties.getProperty(FILE_NAME))
        }

        private companion object {
            const val LOCK_STRIPES = 32
            const val META = ".meta"
            const val CONTENT_TYPE = "contentType"
            const val FILE_NAME = "fileName"
        }
    }
