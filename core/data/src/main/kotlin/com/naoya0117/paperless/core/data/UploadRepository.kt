package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.database.UploadQueue
import com.naoya0117.paperless.core.model.DocumentUpload
import com.naoya0117.paperless.core.model.Upload
import com.naoya0117.paperless.core.model.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A file to upload, e.g. from a content URI shared with the app. */
class UploadSource(
    val fileName: String,
    val mimeType: String?,
    /** Opened once, to copy the file into app storage; the caller's permission may not last longer. */
    val open: () -> InputStream,
) {
    init {
        require(fileName.isNotBlank()) { "fileName must not be blank" }
    }
}

/** Where queued files are kept until they are sent: app-private, not backed up. */
class UploadStorage(
    val directory: File,
)

/** Runs uploads in the background (WorkManager in the app). */
interface UploadScheduler {
    /** Runs the upload when a network connection is available; replaces a pending run of the same upload. */
    fun schedule(uploadId: String)

    fun cancel(uploadId: String)
}

/** Uploads of the active account, sent in the background even if the app is closed. */
@Singleton
class UploadRepository internal constructor(
    private val accounts: AccountRepository,
    private val queue: UploadQueue,
    private val scheduler: UploadScheduler,
    private val storage: UploadStorage,
    private val clock: Clock,
) {
    @Inject
    constructor(
        accounts: AccountRepository,
        queue: UploadQueue,
        scheduler: UploadScheduler,
        storage: UploadStorage,
    ) : this(accounts, queue, scheduler, storage, Clock.systemUTC())

    /** The active account's uploads, newest first; empty without an account. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uploads: Flow<List<Upload>> =
        accounts.state
            .map { it.activeAccountId }
            .distinctUntilChanged()
            .flatMapLatest { accountId -> accountId?.let(queue::observe) ?: flowOf(emptyList()) }

    /**
     * Copies the file into app storage and queues it for the active account.
     *
     * @throws NotSignedInException if there is no active account
     * @throws java.io.IOException if the file cannot be read or stored
     */
    suspend fun enqueue(
        source: UploadSource,
        metadata: DocumentUpload = DocumentUpload(),
    ): Upload {
        val account = accounts.state.first().activeAccount ?: throw NotSignedInException()
        val id = UUID.randomUUID().toString()
        val file = File(storage.directory, id)
        val size =
            withContext(Dispatchers.IO) {
                storage.directory.mkdirs()
                try {
                    source.open().use { input -> file.outputStream().use { input.copyTo(it) } }
                } catch (e: java.io.IOException) {
                    file.delete()
                    throw e
                }
            }
        val upload =
            Upload(
                id = id,
                accountId = account.id,
                fileName = source.fileName,
                mimeType = source.mimeType,
                size = size,
                metadata = metadata,
                state = UploadState.QUEUED,
                sentBytes = 0,
                taskId = null,
                documentId = null,
                duplicateOfDocumentId = null,
                error = null,
                // Stored in milliseconds.
                createdAt = clock.instant().truncatedTo(ChronoUnit.MILLIS),
            )
        try {
            queue.add(upload, file.path)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            throw e
        }
        scheduler.schedule(id)
        return upload
    }

    /**
     * Sends a failed upload again from the start. Does nothing for uploads that did not fail.
     *
     * @return `false` if the upload is gone or its file was already deleted
     */
    suspend fun retry(id: String): Boolean {
        val upload = queue.get(id) ?: return false
        if (upload.state != UploadState.FAILED) return true
        val path = queue.filePath(id) ?: return false
        if (!withContext(Dispatchers.IO) { File(path).exists() }) return false
        queue.update(
            upload.copy(
                state = UploadState.QUEUED,
                sentBytes = 0,
                taskId = null,
                documentId = null,
                duplicateOfDocumentId = null,
                error = null,
            ),
        )
        scheduler.schedule(id)
        return true
    }

    /** Cancels the upload if it is still pending and forgets it, deleting the stored file. */
    suspend fun remove(id: String) {
        scheduler.cancel(id)
        val path = queue.filePath(id)
        queue.remove(id)
        path?.let { withContext(Dispatchers.IO) { File(it).delete() } }
    }

    /** Cancels and deletes every upload of the account, e.g. after signing out. */
    suspend fun clear(accountId: String) {
        queue.forAccount(accountId).forEach { remove(it.id) }
    }
}
