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
 * @property errorKind why the upload failed, for [UploadState.FAILED]
 * @property error details, usually the server's message (in the server's language), if any
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
    val errorKind: UploadError?,
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

enum class UploadError {
    /** The account's credentials no longer work. */
    SIGN_IN_REQUIRED,

    /** The server refused the file or its metadata (e.g. an unsupported file type). */
    REJECTED,

    /** The server accepted the file but could not turn it into a document. */
    PROCESSING_FAILED,

    /** The server already has this file ([Upload.duplicateOfDocumentId]). */
    DUPLICATE,

    /** The server did not report the outcome in time; the document may still appear. */
    NOT_REPORTED,

    /** Anything else (e.g. the server could not be reached for a long time). */
    UNEXPECTED,
}
