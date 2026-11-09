package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.BulkEdit
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.Page
import com.naoya0117.paperless.core.network.BulkApi
import com.naoya0117.paperless.core.network.TrashApi
import com.naoya0117.paperless.core.network.internal.dto.documentsBody
import com.naoya0117.paperless.core.network.internal.dto.putPaging
import com.naoya0117.paperless.core.network.internal.dto.toModel
import com.naoya0117.paperless.core.network.internal.dto.toRequestBody
import com.naoya0117.paperless.core.network.internal.dto.trashBody
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

internal class TrashApiImpl(
    private val service: BulkService,
) : TrashApi {
    override suspend fun list(
        page: Int,
        pageSize: Int,
    ): Page<Document> {
        require(page >= 1) { "page starts at 1: $page" }
        require(pageSize in 1..DocumentQuery.MAX_PAGE_SIZE) { "pageSize out of range: $pageSize" }
        val query = buildMap { putPaging(page, pageSize) }
        return pageOrEmptyBeyondEnd(page) { service.trash(query) }.toModel { parsing { it.toModel() } }
    }

    override suspend fun restore(documentIds: Collection<Long>) {
        if (documentIds.isNotEmpty()) service.trashAction(trashBody("restore", documentIds))
    }

    override suspend fun empty(documentIds: Collection<Long>?) {
        if (documentIds == null || documentIds.isNotEmpty()) service.trashAction(trashBody("empty", documentIds))
    }
}
