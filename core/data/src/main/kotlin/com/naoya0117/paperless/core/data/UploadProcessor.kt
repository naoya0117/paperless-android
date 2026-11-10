package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.database.UploadQueue
import com.naoya0117.paperless.core.model.TaskStatus
import com.naoya0117.paperless.core.model.Upload
import com.naoya0117.paperless.core.model.UploadError
import com.naoya0117.paperless.core.model.UploadState
import com.naoya0117.paperless.core.network.PaperlessClient
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.TaskWaitResult
import com.naoya0117.paperless.core.network.UploadContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Sends one queued upload and follows the server's processing; run by the background scheduler. */
class UploadProcessor internal constructor(
    private val accounts: AccountRepository,
    private val queue: UploadQueue,
    private val processingWait: Duration,
) {
    @Inject
    constructor(accounts: AccountRepository, queue: UploadQueue) : this(accounts, queue, 2.minutes)

    enum class Outcome {
        /** Nothing more to do: done, failed for good, or removed. */
        FINISHED,

        /** Try again later (e.g. offline, or the server is still processing). */
        RETRY,
    }

    /**
     * @param attempt how often this upload was run before (0 for the first run); after [MAX_ATTEMPTS] the
     *   upload fails instead of being retried again
     * @param onSending called before the file is sent, e.g. to keep the process alive while sending
     */
    suspend fun process(
        uploadId: String,
        attempt: Int = 0,
        onSending: suspend (Upload) -> Unit = {},
    ): Outcome {
        val upload = queue.get(uploadId) ?: return Outcome.FINISHED
        if (upload.state == UploadState.DONE || upload.state == UploadState.FAILED) return Outcome.FINISHED
        val account =
            accounts.state
                .first()
                .accounts
                .firstOrNull { it.id == upload.accountId }
        if (account == null) {
            // Signed out (its uploads are removed with it) or enqueued during the sign-out.
            queue.remove(uploadId)
            deleteFile(upload)
            return Outcome.FINISHED
        }
        val client =
            accounts.client(account) ?: return fail(upload, UploadError.SIGN_IN_REQUIRED, account.serverUrl)
        // The latest stored state: once sent, the task must never be lost (that would send the file twice).
        var current = upload
        val outcome =
            try {
                if (current.taskId == null) {
                    // Still UPLOADING: the previous run died after sending, possibly after the server took the file.
                    val resent = current.state == UploadState.UPLOADING
                    onSending(current)
                    current = send(current, client) ?: return Outcome.FINISHED
                    awaitProcessing(current, client, resent)
                } else {
                    awaitProcessing(current, client, resent = false)
                }
            } catch (e: CancellationException) {
                // Stopped by the scheduler (e.g. the network went away): show it as waiting again.
                if (current.taskId ==
                    null
                ) {
                    withContext(NonCancellable) { save(current.copy(state = UploadState.QUEUED, sentBytes = 0)) }
                }
                throw e
            } catch (e: PaperlessException) {
                if (e.isTransient()) {
                    save(
                        if (current.taskId ==
                            null
                        ) {
                            current.copy(state = UploadState.QUEUED, sentBytes = 0)
                        } else {
                            current
                        },
                    )
                    Outcome.RETRY
                } else {
                    return fail(current, e.errorKind(), e.message)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Never leave the upload in a running state with no work to finish it.
                return fail(current, UploadError.UNEXPECTED, e.message ?: e.javaClass.simpleName)
            }
        if (outcome == Outcome.RETRY && attempt + 1 >= MAX_ATTEMPTS) {
            val latest = queue.get(uploadId) ?: return Outcome.FINISHED
            val kind = if (latest.taskId != null) UploadError.NOT_REPORTED else UploadError.UNEXPECTED
            return fail(latest, kind, null)
        }
        return outcome
    }

    /**
     * Sends the file and stores the task. Not cancellable once started: the server may accept the file at any
     * moment, and its task ID must be stored (stopping it is only possible by removing the network).
     *
     * @return the upload with its task, or `null` if it was removed meanwhile
     */
    private suspend fun send(
        upload: Upload,
        client: PaperlessClient,
    ): Upload? {
        val file = queue.filePath(upload.id)?.let(::File) ?: return null
        if (!save(upload.copy(state = UploadState.UPLOADING, sentBytes = 0))) return null
        return withContext(NonCancellable) {
            val taskId =
                withProgress(upload.id) { progress ->
                    client.documents.upload(
                        UploadContent(upload.fileName, upload.mimeType, upload.size) { file.inputStream() },
                        upload.metadata,
                        progress,
                    )
                }
            val sent = upload.copy(state = UploadState.PROCESSING, sentBytes = upload.size, taskId = taskId)
            sent.takeIf { save(it) }
        }
    }

    /**
     * @param resent whether the file may have been sent before, so that "duplicate" means "already there"
     */
    private suspend fun awaitProcessing(
        upload: Upload,
        client: PaperlessClient,
        resent: Boolean,
    ): Outcome {
        val taskId = checkNotNull(upload.taskId)
        val task =
            when (val result = client.tasks.awaitCompletion(taskId, timeout = processingWait)) {
                is TaskWaitResult.Finished -> result.task
                is TaskWaitResult.TimedOut -> return Outcome.RETRY
            }
        val finished =
            when {
                // documentId is null when consumption ended without a document (e.g. a barcode split the file).
                task.status == TaskStatus.SUCCESS -> upload.copy(state = UploadState.DONE, documentId = task.documentId)
                resent && task.duplicateOfDocumentId != null ->
                    upload.copy(state = UploadState.DONE, documentId = task.duplicateOfDocumentId)
                else ->
                    upload.copy(
                        state = UploadState.FAILED,
                        duplicateOfDocumentId = task.duplicateOfDocumentId,
                        errorKind =
                            if (task.duplicateOfDocumentId !=
                                null
                            ) {
                                UploadError.DUPLICATE
                            } else {
                                UploadError.PROCESSING_FAILED
                            },
                        error = task.message,
                    )
            }
        if (save(finished) && finished.state == UploadState.DONE) deleteFile(upload)
        return Outcome.FINISHED
    }

    private suspend fun fail(
        upload: Upload,
        kind: UploadError,
        detail: String?,
    ): Outcome {
        save(upload.copy(state = UploadState.FAILED, errorKind = kind, error = detail))
        return Outcome.FINISHED
    }

    /** Not cancellable, so that the stored state matches what happened on the server. */
    private suspend fun save(upload: Upload): Boolean = withContext(NonCancellable) { queue.update(upload) }

    private suspend fun deleteFile(upload: Upload) {
        val path = queue.filePath(upload.id) ?: return
        withContext(Dispatchers.IO) { File(path).delete() }
    }

    /**
     * Reports progress to the queue at most every [PROGRESS_STEP] bytes, off the network thread and one write
     * at a time (so the stored value never goes back). Returns after the last write.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> withProgress(
        id: String,
        block: suspend (com.naoya0117.paperless.core.network.UploadProgress) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val reported = AtomicLong()
            val writes = Dispatchers.IO.limitedParallelism(1)
            val scope: CoroutineScope = this
            block { sent, total ->
                val last = reported.get()
                if ((sent - last >= PROGRESS_STEP || sent == total) && reported.compareAndSet(last, sent)) {
                    scope.launch(writes) { queue.setSentBytes(id, sent) }
                }
            }
        }

    private fun PaperlessException.isTransient(): Boolean =
        (this is PaperlessException.Network && this !is PaperlessException.Network.Tls) ||
            this is PaperlessException.Http.ServerError ||
            this is PaperlessException.Http.RateLimited

    private fun PaperlessException.errorKind(): UploadError =
        when (this) {
            is PaperlessException.Http.Unauthorized, is PaperlessException.Auth -> UploadError.SIGN_IN_REQUIRED
            is PaperlessException.Http.BadRequest, is PaperlessException.Http.Forbidden -> UploadError.REJECTED
            else -> UploadError.UNEXPECTED
        }

    companion object {
        /** With the scheduler's exponential backoff (30 s doubling, at most 5 h) this is about two days. */
        const val MAX_ATTEMPTS: Int = 16

        private const val PROGRESS_STEP = 256L * 1024
    }
}
