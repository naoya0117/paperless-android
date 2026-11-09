package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.DocumentOrdering
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.SavedView
import com.naoya0117.paperless.core.network.internal.dto.toQueryParams

/**
 * The document list of this saved view, with its filter rules and sort order converted like the web UI
 * does. Use the result with [DocumentsApi.list]; [DocumentQuery.extraParams] carries the conditions that
 * have no typed equivalent.
 *
 * @param existingCustomFieldIds if given, a sort by a custom field that no longer exists falls back to the
 *   created date, as in the web UI (the server would reject the request)
 */
public fun SavedView.toDocumentQuery(
    page: Int = 1,
    pageSize: Int = this.pageSize?.takeIf { it > 0 }?.coerceAtMost(DocumentQuery.MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE,
    existingCustomFieldIds: Set<Long>? = null,
): DocumentQuery {
    val params = filterRules.toQueryParams().toMutableMap()
    var ordering: DocumentOrdering? = null
    val field = sortField?.let { sortFieldOrFallback(it, existingCustomFieldIds) }
    if (field != null) {
        val typed = DocumentOrdering.Field.entries.firstOrNull { it.apiName == field }
        if (typed != null) {
            ordering = DocumentOrdering(typed, descending = sortReverse)
        } else {
            params["ordering"] = (if (sortReverse) "-" else "") + field
        }
    }
    return DocumentQuery(page = page, pageSize = pageSize, ordering = ordering, extraParams = params)
}

private fun sortFieldOrFallback(
    field: String,
    existingCustomFieldIds: Set<Long>?,
): String {
    val customFieldId =
        field
            .removePrefix(CUSTOM_FIELD_PREFIX)
            .takeIf {
                field.startsWith(CUSTOM_FIELD_PREFIX)
            }?.toLongOrNull()
    return if (customFieldId != null && existingCustomFieldIds != null && customFieldId !in existingCustomFieldIds) {
        DocumentOrdering.Field.CREATED.apiName
    } else {
        field
    }
}

private const val DEFAULT_PAGE_SIZE = 25
private const val CUSTOM_FIELD_PREFIX = "custom_field_"
