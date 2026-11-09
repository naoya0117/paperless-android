package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.BulkEdit
import com.naoya0117.paperless.core.network.BulkApi
import com.naoya0117.paperless.core.network.internal.dto.documentsBody
import com.naoya0117.paperless.core.network.internal.dto.toRequestBody
import com.naoya0117.paperless.core.network.internal.service.BulkService

internal class BulkApiImpl(
    private val service: BulkService,
) : BulkApi {
    override suspend fun edit(
        documentIds: Collection<Long>,
        edit: BulkEdit,
    ) {
        if (documentIds.isNotEmpty()) service.bulkEdit(edit.toRequestBody(documentIds))
    }

    override suspend fun delete(documentIds: Collection<Long>) {
        if (documentIds.isNotEmpty()) service.delete(documentsBody(documentIds))
    }

    override suspend fun reprocess(documentIds: Collection<Long>) {
        if (documentIds.isNotEmpty()) service.reprocess(documentsBody(documentIds))
    }
}
