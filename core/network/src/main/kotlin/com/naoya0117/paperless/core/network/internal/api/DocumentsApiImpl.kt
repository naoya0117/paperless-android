package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.DocumentDraft
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.Page
import com.naoya0117.paperless.core.network.DocumentsApi
import com.naoya0117.paperless.core.network.internal.dto.toModel
import com.naoya0117.paperless.core.network.internal.dto.toPatchBody
import com.naoya0117.paperless.core.network.internal.dto.toQueryMap
import com.naoya0117.paperless.core.network.internal.service.DocumentService

internal class DocumentsApiImpl(
    private val service: DocumentService,
) : DocumentsApi {
    override suspend fun list(query: DocumentQuery): Page<Document> =
        pageOrEmptyBeyondEnd(query.page) { service.list(query.toQueryMap()) }.toModel { parsing { it.toModel() } }

    override suspend fun get(id: Long): Document = parsing { service.get(id).toModel() }

    override suspend fun update(
        current: Document,
        draft: DocumentDraft,
    ): Document {
        val body = draft.toPatchBody(current)
        if (body.isEmpty()) return current
        // The response carries `permissions` instead of `user_can_change`; a successful PATCH proves the right.
        return parsing { service.update(current.id, body).toModel() }.copy(userCanChange = true)
    }

    override suspend fun delete(id: Long) = service.delete(id)
}
