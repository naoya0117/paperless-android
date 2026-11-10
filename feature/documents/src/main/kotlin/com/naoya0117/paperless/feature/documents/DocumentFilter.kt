package com.naoya0117.paperless.feature.documents

import androidx.lifecycle.SavedStateHandle
import com.naoya0117.paperless.core.model.DateRange
import com.naoya0117.paperless.core.model.DocumentOrdering
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSearch
import com.naoya0117.paperless.core.model.IdFilter
import com.naoya0117.paperless.core.model.TagFilter
import java.time.LocalDate

/** How the documents are ordered; [orderBy] `null` is the server's default (by relevance when searching). */
data class DocumentSort(
    val orderBy: DocumentOrdering.Field? = null,
    val descending: Boolean = true,
) {
    val ordering: DocumentOrdering? get() = orderBy?.let { DocumentOrdering(it, descending) }

    companion object {
        /** The fields offered (in this order). */
        val FIELDS =
            listOf(
                DocumentOrdering.Field.CREATED,
                DocumentOrdering.Field.ADDED,
                DocumentOrdering.Field.MODIFIED,
                DocumentOrdering.Field.TITLE,
                DocumentOrdering.Field.CORRESPONDENT,
                DocumentOrdering.Field.DOCUMENT_TYPE,
                DocumentOrdering.Field.ARCHIVE_SERIAL_NUMBER,
            )
    }
}

/** What the document list is narrowed to (beyond the search text), and its order. */
data class DocumentFilter(
    /** Documents must have all of these tags. */
    val tagIds: Set<Long> = emptySet(),
    /** Each of these: documents must have one of them. */
    val correspondentIds: Set<Long> = emptySet(),
    val documentTypeIds: Set<Long> = emptySet(),
    val storagePathIds: Set<Long> = emptySet(),
    val created: DateRange? = null,
    val sort: DocumentSort = DocumentSort(),
) {
    /** How many conditions narrow the list (not the order). */
    val conditionCount: Int
        get() =
            listOf(tagIds, correspondentIds, documentTypeIds, storagePathIds).count { it.isNotEmpty() } +
                (if (created != null) 1 else 0)

    val isDefault: Boolean get() = this == DocumentFilter()

    /** The query for these conditions and [search] (blank: none). */
    fun toQuery(search: String): DocumentQuery =
        DocumentQuery(
            search = search.trim().takeIf { it.isNotEmpty() }?.let(DocumentSearch::Text),
            tags = if (tagIds.isEmpty()) TagFilter.Any else TagFilter.Matching(all = tagIds),
            correspondent = correspondentIds.toIdFilter(),
            documentType = documentTypeIds.toIdFilter(),
            storagePath = storagePathIds.toIdFilter(),
            created = created,
            ordering = sort.ordering,
        )

    /** Kept across process death: plain values only. */
    fun save(handle: SavedStateHandle) {
        handle[TAGS] = tagIds.toLongArray()
        handle[CORRESPONDENTS] = correspondentIds.toLongArray()
        handle[DOCUMENT_TYPES] = documentTypeIds.toLongArray()
        handle[STORAGE_PATHS] = storagePathIds.toLongArray()
        handle[CREATED_FROM] = created?.from?.toEpochDay()
        handle[CREATED_TO] = created?.to?.toEpochDay()
        handle[SORT_FIELD] = sort.orderBy?.name
        handle[SORT_DESCENDING] = sort.descending
    }

    companion object {
        private const val PREFIX = "documents.filter."
        private const val TAGS = PREFIX + "tags"
        private const val CORRESPONDENTS = PREFIX + "correspondents"
        private const val DOCUMENT_TYPES = PREFIX + "documentTypes"
        private const val STORAGE_PATHS = PREFIX + "storagePaths"
        private const val CREATED_FROM = PREFIX + "createdFrom"
        private const val CREATED_TO = PREFIX + "createdTo"
        private const val SORT_FIELD = PREFIX + "sortField"
        private const val SORT_DESCENDING = PREFIX + "sortDescending"

        fun restore(handle: SavedStateHandle): DocumentFilter {
            fun ids(key: String) = handle.get<LongArray>(key)?.toSet().orEmpty()
            val from = handle.get<Long>(CREATED_FROM)?.let(LocalDate::ofEpochDay)
            val to = handle.get<Long>(CREATED_TO)?.let(LocalDate::ofEpochDay)
            return DocumentFilter(
                tagIds = ids(TAGS),
                correspondentIds = ids(CORRESPONDENTS),
                documentTypeIds = ids(DOCUMENT_TYPES),
                storagePathIds = ids(STORAGE_PATHS),
                created = if (from == null && to == null) null else DateRange(from, to),
                sort =
                    DocumentSort(
                        orderBy =
                            handle.get<String>(SORT_FIELD)?.let { name ->
                                DocumentOrdering.Field.entries.firstOrNull {
                                    it.name ==
                                        name
                                }
                            },
                        descending = handle.get<Boolean>(SORT_DESCENDING) ?: true,
                    ),
            )
        }

        private fun Set<Long>.toIdFilter(): IdFilter = if (isEmpty()) IdFilter.Any else IdFilter.AnyOf(this)
    }
}
