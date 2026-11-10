package com.naoya0117.paperless.core.model

import java.time.Instant

/**
 * A file queued for upload to an account's server.
 *
 * @property id local identifier
 * @property sentBytes bytes sent so far while [state] is [UploadState.UPLOADING]
 * @property taskId the server's consume task, once the file was sent
 * @property documentId the created document, once [state] is [UploadState.DONE]
 * @property duplicateOfDocumentId the existing document, when the server rejected the file as a duplicate
 * @property error why the upload failed, for [UploadState.FAILED]
 */
data class Upload(
    val id: String,
    val accountId: String,
    val fileName: String,
    val mimeType: String?,
    val size: Long,
    val metadata: DocumentUpload,
    val state: UploadState,
    val sentBytes: Long,
    val taskId: String?,
    val documentId: Long?,
    val duplicateOfDocumentId: Long?,
    val error: String?,
    val createdAt: Instant,
)

enum class UploadState {
    /** Waiting to be sent (e.g. for a network connection). */
    QUEUED,

    UPLOADING,

    /** Sent; the server is consuming it. */
    PROCESSING,

    DONE,

    FAILED,
}
