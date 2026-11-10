package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.network.DocumentFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where document files are cached: a cache directory the system may also clear, and the most each area may
 * hold (for all accounts together; the least recently used files go first, whichever account they belong
 * to). Thumbnails have their own area, so that scrolling through lists does not push out the documents
 * opened before (e.g. to read them offline).
 */
class DocumentFileStorage(
    val directory: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val thumbnailMaxBytes: Long = DEFAULT_THUMBNAIL_MAX_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_BYTES: Long = 256L * 1024 * 1024
        const val DEFAULT_THUMBNAIL_MAX_BYTES: Long = 64L * 1024 * 1024
    }
}

/**
 * A document file on disk. Use it soon: [file] may be deleted later to keep the cache within its limit (not
 * within a minute of being returned), when the account signs out, or by the system; ask again then.
 */
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
        /** Per file name: downloads of the same file wait for each other, others do not. */
        private val locks = HashMap<String, Pair<Mutex, Int>>()

        /** Serializes trimming and clearing, which walk the whole cache. */
        private val trimLock = Mutex()

        /** Thumbnails, and everything else: cached and trimmed separately. */
        private enum class Area(
            val directoryName: String,
        ) {
            THUMBNAILS("thumbnails"),
            FILES("files"),
        }

        /** Per area, bytes as far as known; negative until counted. Trimming walks the disk only when over. */
        private val knownBytes = Area.entries.associateWith { AtomicLong(-1) }

        private fun Area.limit() = if (this == Area.THUMBNAILS) storage.thumbnailMaxBytes else storage.maxBytes

        private fun areaOf(kind: DocumentFileKind) =
            if (kind ==
                DocumentFileKind.THUMBNAIL
            ) {
                Area.THUMBNAILS
            } else {
                Area.FILES
            }

        /**
         * The file of [document] (from the active account), downloaded if it is not cached yet.
         *
         * @throws NotSignedInException also if the account signed out while downloading
         * @throws com.naoya0117.paperless.core.network.PaperlessException if downloading fails
         * @throws IOException if the file cannot be stored (e.g. the device is out of storage)
         */
        suspend fun file(
            document: Document,
            kind: DocumentFileKind,
        ): CachedDocumentFile {
            val session = accounts.currentSession() ?: throw NotSignedInException()
            val file = File(accountDirectory(areaOf(kind), session.account.id), fileName(document, kind))
            // Stored files appear atomically (renamed into place with their metadata), so a hit needs no lock.
            cached(file)?.let { return it }
            return withFileLock(file.name) { cached(file) ?: download(session, document, kind, file) }
        }

        /** Deletes the account's cached files, e.g. after signing out. */
        suspend fun clear(accountId: String) =
            trimLock.withLock {
                withContext(
                    Dispatchers.IO,
                ) { Area.entries.forEach { accountDirectory(it, accountId).deleteRecursively() } }
                knownBytes.values.forEach { it.set(-1) }
            }

        /** `null` if not cached (or deleted meanwhile). */
        private suspend fun cached(file: File): CachedDocumentFile? =
            withContext(Dispatchers.IO) {
                try {
                    val meta = metaFile(file)
                    if (!file.isFile) return@withContext null
                    val properties = Properties()
                    meta.inputStream().use { properties.load(it) }
                    // Marks it as recently used for trimming (best effort: some file systems ignore it).
                    file.setLastModified(System.currentTimeMillis())
                    CachedDocumentFile(file, properties.getProperty(CONTENT_TYPE), properties.getProperty(FILE_NAME))
                } catch (_: IOException) {
                    null
                }
            }

        private suspend fun download(
            session: AccountSession,
            document: Document,
            kind: DocumentFileKind,
            file: File,
        ): CachedDocumentFile {
            val directory = file.parentFile!!
            val partial = File(directory, file.name + PART)
            val partialMeta = File(directory, file.name + META + PART)
            var stored = false
            try {
                val result =
                    session.client.documents.openFile(document.id, kind).use { remote ->
                        withContext(Dispatchers.IO) {
                            directory.mkdirs()
                            partial.outputStream()
                        }.use { remote.copyTo(it) }
                        CachedDocumentFile(file, remote.contentType, remote.fileName)
                    }
                withContext(Dispatchers.IO) { writeMeta(result, partialMeta) }
                stored =
                    withContext(NonCancellable) {
                        // Not after a sign-out: the account's files were deleted and must stay so.
                        accounts.writeData(session) {
                            withContext(Dispatchers.IO) {
                                if (!partialMeta.renameTo(metaFile(file)) || !partial.renameTo(file)) {
                                    throw IOException("Could not store $file")
                                }
                            }
                        }
                    }
                if (!stored) throw NotSignedInException()
                withContext(Dispatchers.IO) { deleteOlderVersions(file) }
                val area = areaOf(kind)
                knownBytes.getValue(area).let {
                    if (it.get() >=
                        0
                    ) {
                        it.addAndGet(file.length() + metaFile(file).length())
                    }
                }
                trimIfNeeded(area, keep = file)
                return result
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    partial.delete()
                    partialMeta.delete()
                    // E.g. the account signed out while downloading: do not leave its directory behind.
                    if (!stored) directory.delete() // only succeeds when empty
                }
            }
        }

        /** Versions of the same document and kind with an older modification time. */
        private fun deleteOlderVersions(file: File) {
            val prefix = file.name.substringBeforeLast('-') + "-"
            val modified = file.name.substringAfterLast('-').toLong()
            file.parentFile?.listFiles()?.forEach { other ->
                if (!other.name.startsWith(prefix) ||
                    other.name.endsWith(PART) ||
                    other.name.endsWith(META)
                ) {
                    return@forEach
                }
                val otherModified = other.name.substringAfterLast('-').toLongOrNull() ?: return@forEach
                if (otherModified < modified) {
                    metaFile(other).delete()
                    other.delete()
                }
            }
        }

        /**
         * Deletes the least recently used files until the cache fits [DocumentFileStorage.maxBytes]. Files used
         * within the last minute are kept (they may just have been returned), and a single file larger than half
         * the limit does not push out everything else: it is the first to go next time. Leftovers of interrupted
         * downloads are removed.
         */
        private suspend fun trimIfNeeded(
            area: Area,
            keep: File,
        ) {
            val maxBytes = area.limit()
            val known = knownBytes.getValue(area).get()
            if (known in 0..maxBytes) return
            trimLock.withLock {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val entries = mutableListOf<Entry>()
                    File(storage.directory, area.directoryName).walkTopDown().filter { it.isFile }.forEach { file ->
                        val name = file.name
                        when {
                            name.endsWith(PART) -> if (file.lastModified() < now - LEFTOVER_AGE_MILLIS) file.delete()
                            name.endsWith(META) ->
                                if (!File(file.parentFile, name.removeSuffix(META)).exists() &&
                                    file.lastModified() < now - LEFTOVER_AGE_MILLIS
                                ) {
                                    file.delete()
                                }
                            else -> entries += Entry(file, file.lastModified(), file.length() + metaFile(file).length())
                        }
                    }
                    var total = entries.sumOf { it.bytes }
                    val oversized = keep.length() > maxBytes / 2
                    if (oversized) total -= entries.firstOrNull { it.file == keep }?.bytes ?: 0
                    for (entry in entries.sortedBy { it.lastUsed }) {
                        if (total <= maxBytes) break
                        if (entry.file == keep || entry.lastUsed > now - RECENTLY_USED_MILLIS) continue
                        metaFile(entry.file).delete()
                        entry.file.delete()
                        total -= entry.bytes
                    }
                    if (oversized) total += keep.length() + metaFile(keep).length()
                    knownBytes.getValue(area).set(total)
                }
            }
        }

        private class Entry(
            val file: File,
            val lastUsed: Long,
            val bytes: Long,
        )

        private suspend fun <T> withFileLock(
            name: String,
            block: suspend () -> T,
        ): T {
            val mutex =
                synchronized(locks) {
                    val (mutex, users) = locks[name] ?: (Mutex() to 0)
                    locks[name] = mutex to users + 1
                    mutex
                }
            try {
                return mutex.withLock { block() }
            } finally {
                synchronized(locks) {
                    val (_, users) = locks.getValue(name)
                    if (users == 1) locks.remove(name) else locks[name] = mutex to users - 1
                }
            }
        }

        private fun accountDirectory(
            area: Area,
            accountId: String,
        ) = File(File(storage.directory, area.directoryName), accountId)

        private fun metaFile(file: File) = File(file.parentFile, file.name + META)

        private fun writeMeta(
            file: CachedDocumentFile,
            target: File,
        ) {
            val properties = Properties()
            file.contentType?.let { properties.setProperty(CONTENT_TYPE, it) }
            file.fileName?.let { properties.setProperty(FILE_NAME, it) }
            target.outputStream().use { properties.store(it, null) }
        }

        private companion object {
            const val META = ".meta"
            const val PART = ".part"
            const val CONTENT_TYPE = "contentType"
            const val FILE_NAME = "fileName"
            const val RECENTLY_USED_MILLIS = 60_000L
            const val LEFTOVER_AGE_MILLIS = 60 * 60 * 1000L

            /** PREVIEW and DOWNLOAD are the same file (the archived PDF, or else the original): cache it once. */
            fun fileName(
                document: Document,
                kind: DocumentFileKind,
            ): String {
                val key = if (kind == DocumentFileKind.DOWNLOAD) DocumentFileKind.PREVIEW else kind
                return "${document.id}-${key.name.lowercase()}-${document.modified.toEpochMilli()}"
            }
        }
    }
