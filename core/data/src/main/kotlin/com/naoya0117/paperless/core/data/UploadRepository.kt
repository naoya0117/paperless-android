package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.database.UploadQueue
import com.naoya0117.paperless.core.model.DocumentUpload
import com.naoya0117.paperless.core.model.Upload
import com.naoya0117.paperless.core.model.UploadError
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
    /**
     * Runs the upload when a network connection is available.
     *
     * @param replace whether to replace a pending or running run of the same upload; otherwise an existing
     *   one is kept (e.g. to make sure an upload is scheduled at all)
     */
    fun schedule(
        uploadId: String,
        replace: Boolean = true,
    )

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
            try {
                withContext(Dispatchers.IO) {
                    storage.directory.mkdirs()
                    source.open().use { input -> file.outputStream().use { input.copyTo(it) } }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                // Including cancellation: never leave a partial copy behind.
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                throw e
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
                errorKind = null,
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
        // Signed out while copying: the sign-out already cleared the account's uploads.
        if (accounts.state
                .first()
                .accounts
                .none { it.id == account.id }
        ) {
            remove(id)
            throw NotSignedInException()
        }
        scheduler.schedule(id)
        return upload
    }

    /**
     * Tries a failed upload again: if the server already has the file (the failure came after sending, e.g. a
     * lost session), only its processing is checked again; otherwise the file is sent again. An upload that is
     * not failed is only scheduled again (in case its work was lost).
     *
     * @return `false` if the upload is gone, or its file was already deleted and it would have to be sent
     */
    suspend fun retry(id: String): Boolean {
        val upload = queue.get(id) ?: return false
        if (upload.state != UploadState.FAILED) {
            scheduler.schedule(id, replace = false)
            return true
        }
        val processingKnown =
            upload.taskId != null &&
                upload.errorKind != UploadError.PROCESSING_FAILED &&
                upload.errorKind != UploadError.DUPLICATE
        val retried =
            if (processingKnown) {
                upload.copy(state = UploadState.PROCESSING, errorKind = null, error = null)
            } else {
                val path = queue.filePath(id) ?: return false
                if (!withContext(Dispatchers.IO) { File(path).exists() }) return false
                upload.copy(
                    state = UploadState.QUEUED,
                    sentBytes = 0,
                    taskId = null,
                    documentId = null,
                    duplicateOfDocumentId = null,
                    errorKind = null,
                    error = null,
                )
            }
        queue.update(retried)
        scheduler.schedule(id)
        return true
    }

    /**
     * Repairs the queue after the process was killed, e.g. at app start: makes sure unfinished uploads are
     * scheduled, drops uploads of accounts that no longer exist, and deletes stored files no upload refers to.
     */
    suspend fun reconcile() {
        val accountIds =
            accounts.state
                .first()
                .accounts
                .mapTo(mutableSetOf()) { it.id }
        for (upload in queue.unfinished()) {
            if (upload.accountId in accountIds) scheduler.schedule(upload.id, replace = false) else remove(upload.id)
        }
        val referenced = queue.allFilePaths()
        val cutoff = clock.millis() - ORPHAN_AGE_MILLIS
        withContext(Dispatchers.IO) {
            // Recent files may belong to an enqueue that has not stored its upload yet.
            storage.directory
                .listFiles()
                ?.filter { it.path !in referenced && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }

    /** Cancels the upload if it is still pending and forgets it, deleting the stored file. */
    suspend fun remove(id: String) {
        scheduler.cancel(id)
        val path = queue.filePath(id)
        queue.remove(id)
        path?.let { withContext(Dispatchers.IO) { File(it).delete() } }
    }

    /**
     * Cancels and deletes every upload of the account, e.g. after signing out. A file being sent at that
     * moment may still reach the server.
     */
    suspend fun clear(accountId: String) {
        queue.forAccount(accountId).forEach { remove(it.id) }
    }

    private companion object {
        const val ORPHAN_AGE_MILLIS = 60 * 60 * 1000L
    }
}
