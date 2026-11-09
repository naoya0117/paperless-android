package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.BulkEdit

/**
 * Operations on many documents at once. Changes are written before the call returns (a following
 * [DocumentsApi.get] sees them); the full-text index and file names are updated shortly after.
 *
 * Failures: [PaperlessException.Http.BadRequest] if a document does not exist (key `documents`),
 * [PaperlessException.Http.Forbidden] (without a detail) if the user may not change one of them.
 */
public interface BulkApi {
    public suspend fun edit(
        documentIds: Collection<Long>,
        edit: BulkEdit,
    )

    /**
     * Moves the documents (with their versions) to the trash. Requires delete permission and ownership.
     * The server reports success even if moving failed internally; refresh the list to be sure.
     */
    public suspend fun delete(documentIds: Collection<Long>)

    /** Queues consumption (OCR, matching) again for the documents; the server runs it in the background. */
    public suspend fun reprocess(documentIds: Collection<Long>)
}
