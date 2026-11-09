package com.naoya0117.paperless.core.model

import java.time.LocalDate

/** Filters, search and ordering for listing documents. All filters are combined with AND. */
data class DocumentQuery(
    val page: Int = 1,
    val pageSize: Int = 25,
    val search: DocumentSearch? = null,
    val tags: TagFilter = TagFilter.Any,
    val correspondent: IdFilter = IdFilter.Any,
    val documentType: IdFilter = IdFilter.Any,
    val storagePath: IdFilter = IdFilter.Any,
    val created: DateRange? = null,
    /** Compared with the date the document was added, in the server's time zone (`PAPERLESS_TIME_ZONE`). */
    val added: DateRange? = null,
    val archiveSerialNumber: Long? = null,
    /** `true`: only documents with an inbox tag; `false`: only documents without. */
    val inInbox: Boolean? = null,
    /**
     * Raw `custom_field_query` JSON, e.g. `["Due date", "exists", true]` or
     * `["AND", [["Amount", "gt", 100], ...]]`. See the Paperless-ngx API documentation.
     */
    val customFieldQuery: String? = null,
    /** `null`: the server's default (by relevance for searches, otherwise newest created first). */
    val ordering: DocumentOrdering? = null,
    /** Shorten [Document.content] in the results, as lists rarely need the full text. */
    val truncateContent: Boolean = true,
    /**
     * Additional raw query parameters for conditions the typed properties cannot express (e.g. from a
     * saved view's filter rules), combined with the typed ones by AND: "all of" / "none of" lists with the
     * same key are merged; any other key set both here and by a typed property, or a second kind of search,
     * is rejected when the request is built. `page` and `page_size` cannot be set here.
     */
    val extraParams: Map<String, String> = emptyMap(),
) {
    init {
        require(page >= 1) { "page starts at 1: $page" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be in 1..$MAX_PAGE_SIZE: $pageSize" }
    }

    companion object {
        const val MAX_PAGE_SIZE: Int = 1000
    }
}

/** Full-text search. The server accepts only one kind per request. */
sealed interface DocumentSearch {
    /** Simple search over title and content. */
    data class Text(
        val text: String,
    ) : DocumentSearch {
        init {
            requireSearchText(text)
        }
    }

    data class Title(
        val text: String,
    ) : DocumentSearch {
        init {
            requireSearchText(text)
        }
    }

    /** Search syntax with fields and operators, e.g. `tag:invoice created:[2026 to now]`. */
    data class Advanced(
        val query: String,
    ) : DocumentSearch {
        init {
            requireSearchText(query)
        }
    }

    /** Documents similar to the given one. */
    data class MoreLike(
        val documentId: Long,
    ) : DocumentSearch

    companion object {
        /** Longer queries are rejected by the server. */
        const val MAX_LENGTH: Int = 4096
    }
}

sealed interface TagFilter {
    data object Any : TagFilter

    data object Untagged : TagFilter

    /**
     * @property all documents must have every one of these tags
     * @property anyOf documents must have at least one of these tags (if not empty)
     * @property none documents must have none of these tags
     */
    data class Matching(
        val all: Set<Long> = emptySet(),
        val anyOf: Set<Long> = emptySet(),
        val none: Set<Long> = emptySet(),
    ) : TagFilter
}

/** Filter on a single-valued reference (correspondent, document type, storage path). */
sealed interface IdFilter {
    data object Any : IdFilter

    data object NotAssigned : IdFilter

    /** [ids] must not be empty: the server ignores an empty list, which would match every document. */
    data class AnyOf(
        val ids: Set<Long>,
    ) : IdFilter {
        init {
            require(ids.isNotEmpty()) { "AnyOf needs at least one ID; use Any for no restriction" }
        }
    }

    data class NoneOf(
        val ids: Set<Long>,
    ) : IdFilter {
        init {
            require(ids.isNotEmpty()) { "NoneOf needs at least one ID; use Any for no restriction" }
        }
    }
}

/** Inclusive date range; either end may be open. */
data class DateRange(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

data class DocumentOrdering(
    val field: Field,
    val descending: Boolean = true,
) {
    enum class Field(
        val apiName: String,
    ) {
        CREATED("created"),
        ADDED("added"),
        MODIFIED("modified"),
        TITLE("title"),
        CORRESPONDENT("correspondent__name"),
        DOCUMENT_TYPE("document_type__name"),
        STORAGE_PATH("storage_path__name"),
        ARCHIVE_SERIAL_NUMBER("archive_serial_number"),
        NOTE_COUNT("num_notes"),
        PAGE_COUNT("page_count"),
    }
}

private fun requireSearchText(text: String) {
    require(text.isNotBlank()) { "Search text must not be blank" }
    // The server counts code points, not UTF-16 units.
    require(text.codePointCount(0, text.length) <= DocumentSearch.MAX_LENGTH) {
        "Search text longer than ${DocumentSearch.MAX_LENGTH} characters"
    }
}
