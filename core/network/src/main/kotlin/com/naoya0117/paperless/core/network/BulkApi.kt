package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.BulkEdit
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.Page

/** Operations on many documents at once. */
public interface BulkApi {
    /** @throws PaperlessException.Http.BadRequest e.g. if a document does not exist (key `documents`) */
    public suspend fun edit(
        documentIds: Collection<Long>,
        edit: BulkEdit,
    )

    /** Moves the documents to the trash. */
    public suspend fun delete(documentIds: Collection<Long>)

    /** Runs consumption (OCR, matching) again for the documents, in the background on the server. */
    public suspend fun reprocess(documentIds: Collection<Long>)
}

/** Deleted documents, kept until the server empties the trash (after `PAPERLESS_EMPTY_TRASH_DELAY` days). */
public interface TrashApi {
    /** Documents in the trash, with [Document.deletedAt] set. */
    public suspend fun list(
        page: Int = 1,
        pageSize: Int = 25,
    ): Page<Document>

    public suspend fun restore(documentIds: Collection<Long>)

    /** Deletes the documents permanently; `null` empties the whole trash. */
    public suspend fun empty(documentIds: Collection<Long>?)
}
