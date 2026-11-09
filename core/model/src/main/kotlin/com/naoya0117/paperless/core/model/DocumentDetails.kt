package com.naoya0117.paperless.core.model

import java.time.Instant
import java.time.LocalDate

data class Note(
    val id: Long,
    val text: String,
    val created: Instant,
    /** `null` if the author was deleted. */
    val user: NoteAuthor?,
)

data class NoteAuthor(
    val id: Long,
    val username: String,
    val firstName: String?,
    val lastName: String?,
)

/** File details of a document. Archive values are `null` when no archived version exists. */
data class DocumentMetadata(
    val originalFileName: String?,
    val originalChecksum: String,
    val originalSize: Long?,
    val originalMimeType: String?,
    val hasArchiveVersion: Boolean,
    val archiveChecksum: String?,
    val archiveSize: Long?,
    /** Embedded metadata (e.g. PDF/XMP properties) of the original file. */
    val originalProperties: List<FileProperty>,
    val archiveProperties: List<FileProperty>,
)

data class FileProperty(
    val namespace: String?,
    val prefix: String?,
    val key: String,
    val value: String,
)

/** Values Paperless proposes for a document, based on its content and the matching rules. */
data class DocumentSuggestions(
    val correspondentIds: List<Long>,
    val tagIds: List<Long>,
    val documentTypeIds: List<Long>,
    val storagePathIds: List<Long>,
    val dates: List<LocalDate>,
)

/**
 * One entry of a document's audit log (requires `PAPERLESS_AUDIT_LOG_ENABLED`, the default).
 *
 * @property action e.g. `create`, `update`
 * @property changes field name → [old, new] as shown by the server
 */
data class HistoryEntry(
    val id: Long,
    val timestamp: Instant,
    val action: String,
    val changes: Map<String, List<String?>>,
    val actor: HistoryActor?,
)

data class HistoryActor(
    val id: Long,
    val username: String,
)
