package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.database.UploadQueue
import com.naoya0117.paperless.core.model.TaskStatus
import com.naoya0117.paperless.core.model.Upload
import com.naoya0117.paperless.core.model.UploadState
import com.naoya0117.paperless.core.network.PaperlessClient
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.TaskWaitResult
import com.naoya0117.paperless.core.network.UploadContent
import com.naoya0117.paperless.core.network.UploadProgress
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

    suspend fun process(uploadId: String): Outcome {
        val upload = queue.get(uploadId) ?: return Outcome.FINISHED
        if (upload.state == UploadState.DONE || upload.state == UploadState.FAILED) return Outcome.FINISHED
        val account =
            accounts.state
                .first()
                .accounts
                .firstOrNull { it.id == upload.accountId }
        if (account == null) {
            // Signed out; its uploads are being removed.
            return Outcome.FINISHED
        }
        val client = accounts.client(account) ?: return fail(upload, "Sign in to ${account.serverUrl} again")
        // The latest stored state: once sent, the task must never be lost (that would send the file twice).
        var current = upload
        return try {
            if (current.taskId == null) current = send(current, client) ?: return Outcome.FINISHED
            awaitProcessing(current, client)
        } catch (e: PaperlessException) {
            if (e.isTransient()) {
                save(if (current.taskId == null) current.copy(state = UploadState.QUEUED, sentBytes = 0) else current)
                Outcome.RETRY
            } else {
                fail(current, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** @return the upload with its task, or `null` if it was removed meanwhile */
    private suspend fun send(
        upload: Upload,
        client: PaperlessClient,
    ): Upload? {
        val file = queue.filePath(upload.id)?.let(::File) ?: return null
        if (!save(upload.copy(state = UploadState.UPLOADING, sentBytes = 0))) return null
        val taskId =
            withProgress(upload.id) { progress ->
                client.documents.upload(
                    UploadContent(upload.fileName, upload.mimeType, upload.size) { file.inputStream() },
                    upload.metadata,
                    progress,
                )
            }
        val sent = upload.copy(state = UploadState.PROCESSING, sentBytes = upload.size, taskId = taskId)
        return sent.takeIf { save(it) }
    }

    private suspend fun awaitProcessing(
        upload: Upload,
        client: PaperlessClient,
    ): Outcome {
        val taskId = checkNotNull(upload.taskId)
        val task =
            when (val result = client.tasks.awaitCompletion(taskId, timeout = processingWait)) {
                is TaskWaitResult.Finished -> result.task
                is TaskWaitResult.TimedOut -> return Outcome.RETRY
            }
        val finished =
            if (task.status == TaskStatus.SUCCESS && task.documentId != null) {
                upload.copy(state = UploadState.DONE, documentId = task.documentId)
            } else {
                upload.copy(
                    state = UploadState.FAILED,
                    duplicateOfDocumentId = task.duplicateOfDocumentId,
                    error = task.message ?: "Processing ended with status ${task.status.value}",
                )
            }
        if (save(finished) && finished.state == UploadState.DONE) deleteFile(upload.id)
        return Outcome.FINISHED
    }

    private suspend fun fail(
        upload: Upload,
        reason: String,
    ): Outcome {
        save(upload.copy(state = UploadState.FAILED, error = reason))
        return Outcome.FINISHED
    }

    /** Not cancellable, so that the stored state matches what happened on the server. */
    private suspend fun save(upload: Upload): Boolean = withContext(NonCancellable) { queue.update(upload) }

    private suspend fun deleteFile(id: String) {
        queue.filePath(id)?.let { path -> withContext(Dispatchers.IO) { File(path).delete() } }
    }

    /**
     * Reports progress to the queue at most every [PROGRESS_STEP] bytes, off the network thread and one write
     * at a time (so the stored value never goes back). Returns after the last write.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> withProgress(
        id: String,
        block: suspend (UploadProgress) -> T,
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

    private companion object {
        const val PROGRESS_STEP = 256L * 1024
    }
}
