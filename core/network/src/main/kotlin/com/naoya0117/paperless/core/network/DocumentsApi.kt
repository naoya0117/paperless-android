package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.DocumentDraft
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.Page

public interface DocumentsApi {
    /** @throws PaperlessException.Http.BadRequest e.g. for an invalid advanced query or custom field query */
    public suspend fun list(query: DocumentQuery = DocumentQuery()): Page<Document>

    /** The full document, including the complete content. */
    public suspend fun get(id: Long): Document

    /**
     * Changes [current] to [draft], sending only what differs (see [LabelApi.update]). Returns [current]
     * without a request if nothing differs.
     */
    public suspend fun update(
        current: Document,
        draft: DocumentDraft,
    ): Document

    /** Moves the document to the trash (restorable until the server empties it). */
    public suspend fun delete(id: Long)
}
