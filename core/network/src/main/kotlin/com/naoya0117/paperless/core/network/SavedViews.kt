package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.SavedView
import com.naoya0117.paperless.core.network.internal.dto.toQueryParams

/**
 * The document list of this saved view, with its filter rules and sort order converted like the web UI
 * does. Use the result with [DocumentsApi.list]; [DocumentQuery.extraParams] carries the conditions.
 */
public fun SavedView.toDocumentQuery(
    page: Int = 1,
    pageSize: Int = this.pageSize?.coerceIn(1, DocumentQuery.MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE,
): DocumentQuery {
    val params = filterRules.toQueryParams().toMutableMap()
    sortField?.let { params["ordering"] = (if (sortReverse) "-" else "") + it }
    return DocumentQuery(page = page, pageSize = pageSize, extraParams = params)
}

private const val DEFAULT_PAGE_SIZE = 25
