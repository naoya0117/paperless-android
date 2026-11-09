package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.Page

/**
 * Deleted documents, kept until the server empties the trash (after `PAPERLESS_EMPTY_TRASH_DELAY` days).
 * Users other than superusers only see and manage the documents they own.
 */
public interface TrashApi {
    /**
     * Documents in the trash, with [Document.deletedAt] set. The server applies no explicit order, so pages
     * may shift while documents are restored or deleted; a page beyond the last one is returned empty.
     */
    public suspend fun list(
        page: Int = 1,
        pageSize: Int = 25,
    ): Page<Document>

    /**
     * @return the IDs the server restored
     * @throws PaperlessException.Http.BadRequest if a document is not in the trash (key `documents`)
     */
    public suspend fun restore(documentIds: Collection<Long>): List<Long>

    /**
     * Deletes the given documents permanently. The server reports success even if deleting failed
     * internally.
     *
     * @return the IDs the server deleted
     */
    public suspend fun empty(documentIds: Collection<Long>): List<Long>

    /** Deletes every document in the trash that the user can see, permanently. */
    public suspend fun emptyAll(): List<Long>
}
