package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.Page
import com.naoya0117.paperless.core.network.ListQuery
import com.naoya0117.paperless.core.network.TrashApi
import com.naoya0117.paperless.core.network.internal.dto.docIds
import com.naoya0117.paperless.core.network.internal.dto.putPaging
import com.naoya0117.paperless.core.network.internal.dto.toModel
import com.naoya0117.paperless.core.network.internal.dto.trashBody
import com.naoya0117.paperless.core.network.internal.service.TrashService

internal class TrashApiImpl(
    private val service: TrashService,
) : TrashApi {
    override suspend fun list(
        page: Int,
        pageSize: Int,
    ): Page<Document> {
        ListQuery(page = page, pageSize = pageSize) // validates the paging values
        val query = buildMap { putPaging(page, pageSize) }
        return pageOrEmptyBeyondEnd(page) { service.list(query) }.toModel { parsing { it.toModel() } }
    }

    override suspend fun restore(documentIds: Collection<Long>): List<Long> =
        if (documentIds.isEmpty()) emptyList() else service.action(trashBody("restore", documentIds)).docIds()

    override suspend fun empty(documentIds: Collection<Long>): List<Long> =
        if (documentIds.isEmpty()) emptyList() else service.action(trashBody("empty", documentIds)).docIds()

    override suspend fun emptyAll(): List<Long> = service.action(trashBody("empty", null)).docIds()
}
