package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.DateRange
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.DocumentDraft
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSearch
import com.naoya0117.paperless.core.model.IdFilter
import com.naoya0117.paperless.core.model.TagFilter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal fun DocumentQuery.toQueryMap(): Map<String, String> =
    buildMap {
        putPaging(page, pageSize)
        if (truncateContent) put("truncate_content", "true")
        when (val s = search) {
            null -> Unit
            is DocumentSearch.Text -> put("text", s.text)
            is DocumentSearch.Title -> put("title_search", s.text)
            is DocumentSearch.Advanced -> put("query", s.query)
            is DocumentSearch.MoreLike -> put("more_like_id", s.documentId.toString())
        }
        when (val t = tags) {
            TagFilter.Any -> Unit
            TagFilter.Untagged -> put("is_tagged", "false")
            is TagFilter.Matching -> {
                if (t.all.isNotEmpty()) put("tags__id__all", t.all.joinToString(","))
                if (t.anyOf.isNotEmpty()) put("tags__id__in", t.anyOf.joinToString(","))
                if (t.none.isNotEmpty()) put("tags__id__none", t.none.joinToString(","))
            }
        }
        putIdFilter("correspondent", correspondent)
        putIdFilter("document_type", documentType)
        putIdFilter("storage_path", storagePath)
        putDateRange("created", created)
        // `added` is a timestamp; compare its date part.
        putDateRange("added__date", added)
        archiveSerialNumber?.let { put("archive_serial_number", it.toString()) }
        inInbox?.let { put("is_in_inbox", it.toString()) }
        customFieldQuery?.let { put("custom_field_query", it) }
        ordering?.let { putOrdering(it.field.apiName, it.descending) }
        extraParams.forEach { (key, value) -> if (key !in RESERVED_PARAMS && key !in this) put(key, value) }
    }

private val RESERVED_PARAMS = setOf("page", "page_size")

private fun MutableMap<String, String>.putIdFilter(
    name: String,
    filter: IdFilter,
) {
    when (filter) {
        IdFilter.Any -> Unit
        IdFilter.NotAssigned -> put("${name}__isnull", "true")
        is IdFilter.AnyOf -> put("${name}__id__in", filter.ids.joinToString(","))
        is IdFilter.NoneOf -> put("${name}__id__none", filter.ids.joinToString(","))
    }
}

private fun MutableMap<String, String>.putDateRange(
    name: String,
    range: DateRange?,
) {
    range?.from?.let { put("${name}__gte", it.toString()) }
    range?.to?.let { put("${name}__lte", it.toString()) }
}

/** PATCH body with only what differs from [current] (see LabelRequests.kt for why). */
internal fun DocumentDraft.toPatchBody(current: Document): JsonObject =
    buildJsonObject {
        if (title != current.title) put("title", title)
        if (created != current.created) put("created", created.toString())
        if (correspondentId != current.correspondentId) put("correspondent", correspondentId)
        if (documentTypeId != current.documentTypeId) put("document_type", documentTypeId)
        if (storagePathId != current.storagePathId) put("storage_path", storagePathId)
        if (tagIds.toSet() != current.tagIds.toSet()) putJsonArray("tags") { tagIds.forEach { add(JsonPrimitive(it)) } }
        if (archiveSerialNumber != current.archiveSerialNumber) put("archive_serial_number", archiveSerialNumber)
        // The server replaces the document's fields with the list it receives; order does not matter.
        if (customFields.associateBy { it.fieldId } != current.customFields.associateBy { it.fieldId }) {
            putJsonArray("custom_fields") {
                customFields.forEach { field ->
                    addJsonObject {
                        put("field", field.fieldId)
                        put("value", field.value.toJson())
                    }
                }
            }
        }
    }

internal fun MutableMap<String, String>.putPaging(
    page: Int,
    pageSize: Int,
) {
    put("page", page.toString())
    put("page_size", pageSize.toString())
}

internal fun MutableMap<String, String>.putOrdering(
    apiName: String,
    descending: Boolean,
) {
    put("ordering", (if (descending) "-" else "") + apiName)
}
