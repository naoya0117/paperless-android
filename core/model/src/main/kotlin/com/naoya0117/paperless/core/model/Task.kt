package com.naoya0117.paperless.core.model

import java.time.Instant
import java.time.LocalDate

/** A background task on the server, e.g. consuming an uploaded document. */
data class PaperlessTask(
    val id: Long,
    /** The Celery task ID returned by an upload. */
    val taskId: String,
    val type: TaskType,
    val status: TaskStatus,
    val created: Instant?,
    val started: Instant?,
    val done: Instant?,
    /** For consume tasks: the uploaded file's name. */
    val fileName: String?,
    /** For successful consume tasks: the created document (`null` if consumption stopped without one). */
    val documentId: Long?,
    /** For uploads rejected as duplicates: the existing document. */
    val duplicateOfDocumentId: Long?,
    val relatedDocumentIds: List<Long>,
    /**
     * Why a task failed, or why a consume task stopped without creating a document (e.g. a barcode split
     * the file into separate uploads).
     */
    val message: String?,
    /** Whether a user dismissed the task in a task list. */
    val acknowledged: Boolean,
)

/** A value class rather than an enum so that task types added by newer servers can still be read. */
@JvmInline
value class TaskType(
    val value: String,
) {
    companion object {
        val CONSUME_FILE = TaskType("consume_file")
        val TRAIN_CLASSIFIER = TaskType("train_classifier")
        val SANITY_CHECK = TaskType("sanity_check")
        val MAIL_FETCH = TaskType("mail_fetch")
        val EMPTY_TRASH = TaskType("empty_trash")
        val BULK_UPDATE = TaskType("bulk_update")
        val BULK_DELETE = TaskType("bulk_delete")
    }
}

@JvmInline
value class TaskStatus(
    val value: String,
) {
    /** No further change is expected. */
    val isFinished: Boolean get() = this == SUCCESS || this == FAILURE || this == REVOKED

    companion object {
        val PENDING = TaskStatus("pending")
        val STARTED = TaskStatus("started")
        val SUCCESS = TaskStatus("success")
        val FAILURE = TaskStatus("failure")
        val REVOKED = TaskStatus("revoked")
    }
}

data class TaskQuery(
    val page: Int = 1,
    val pageSize: Int = 25,
    val status: TaskStatus? = null,
    val type: TaskType? = null,
    val acknowledged: Boolean? = null,
) {
    init {
        require(page >= 1) { "page starts at 1: $page" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be in 1..$MAX_PAGE_SIZE: $pageSize" }
    }

    companion object {
        const val MAX_PAGE_SIZE: Int = 1000
    }
}

/** Values to set on an uploaded document; `null`/empty leaves them to the server (and its matching rules). */
data class DocumentUpload(
    val title: String? = null,
    val created: LocalDate? = null,
    val correspondentId: Long? = null,
    val documentTypeId: Long? = null,
    val storagePathId: Long? = null,
    val tagIds: List<Long> = emptyList(),
    val archiveSerialNumber: Long? = null,
    val customFields: List<CustomFieldValue> = emptyList(),
) {
    init {
        require(customFields.map { it.fieldId }.toSet().size == customFields.size) { "Duplicate custom fields" }
    }
}
