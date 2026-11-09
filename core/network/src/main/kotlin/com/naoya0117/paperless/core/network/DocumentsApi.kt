package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.DocumentDraft
import com.naoya0117.paperless.core.model.DocumentMetadata
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSuggestions
import com.naoya0117.paperless.core.model.DocumentUpload
import com.naoya0117.paperless.core.model.HistoryEntry
import com.naoya0117.paperless.core.model.Note
import com.naoya0117.paperless.core.model.Page

public interface DocumentsApi {
    /**
     * A page beyond the last one (e.g. after deletions while scrolling) is returned empty.
     * @throws PaperlessException.Http.BadRequest e.g. for an invalid advanced query or custom field query
     */
    public suspend fun list(query: DocumentQuery = DocumentQuery()): Page<Document>

    /** The full document, including the complete content. */
    public suspend fun get(id: Long): Document

    /**
     * Changes [current] to [draft], sending only what differs (see [LabelApi.update]). Returns [current]
     * without a request if nothing differs. The server applies the tag hierarchy, so the result may have
     * more or fewer tags than the draft (adding a child tag adds its parents; removing a parent removes
     * its children).
     */
    public suspend fun update(
        current: Document,
        draft: DocumentDraft,
    ): Document

    /** Moves the document to the trash (restorable until the server empties it). */
    public suspend fun delete(id: Long)

    /** Opens a file of the document for streaming; the caller must close the result. */
    public suspend fun openFile(
        id: Long,
        kind: DocumentFileKind,
    ): DocumentFile

    /** @return all notes of the document after adding, oldest first */
    public suspend fun addNote(
        documentId: Long,
        text: String,
    ): List<Note>

    /** @return the remaining notes, oldest first */
    public suspend fun deleteNote(
        documentId: Long,
        noteId: Long,
    ): List<Note>

    public suspend fun metadata(id: Long): DocumentMetadata

    public suspend fun suggestions(id: Long): DocumentSuggestions

    /** Newest first. */
    public suspend fun history(id: Long): List<HistoryEntry>

    /** The archive serial number the next document would get. */
    public suspend fun nextArchiveSerialNumber(): Long

    /**
     * Uploads a file for consumption. The document is created asynchronously: wait for the returned task
     * with [TasksApi.awaitCompletion] to get the document ID or the failure.
     *
     * @return the consume task's ID
     * @throws PaperlessException.Http.BadRequest e.g. for an unsupported file type (key `document`)
     */
    public suspend fun upload(
        content: UploadContent,
        metadata: DocumentUpload = DocumentUpload(),
        progress: UploadProgress? = null,
    ): String
}
