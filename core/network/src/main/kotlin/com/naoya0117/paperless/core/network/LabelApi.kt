package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.Page

/**
 * CRUD access to one kind of label (tags, correspondents, document types, storage paths, custom fields).
 *
 * @param T the label as read from the server
 * @param D the values to create or update it with
 */
public interface LabelApi<T, D> {
    /** One page; [ListQuery.page] starts at 1. */
    public suspend fun list(query: ListQuery = ListQuery()): Page<T>

    /** Every object the user can see, fetched page by page. */
    public suspend fun listAll(): List<T>

    /** @throws PaperlessException.Http.NotFound */
    public suspend fun get(id: Long): T

    /** @throws PaperlessException.Http.BadRequest e.g. for a duplicate name */
    public suspend fun create(draft: D): T

    /** Applies [draft] to the object (PATCH); see the draft type for how `null` properties are treated. */
    public suspend fun update(
        id: Long,
        draft: D,
    ): T

    public suspend fun delete(id: Long)
}

/**
 * @property nameContains case-insensitive substring filter on the name.
 */
public data class ListQuery(
    val page: Int = 1,
    val pageSize: Int = 25,
    val nameContains: String? = null,
    val ordering: Ordering? = null,
) {
    init {
        require(page >= 1) { "page starts at 1: $page" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be in 1..$MAX_PAGE_SIZE: $pageSize" }
    }

    public data class Ordering(
        val field: Field,
        val descending: Boolean = false,
    ) {
        public enum class Field(
            internal val apiName: String,
        ) {
            NAME("name"),
            DOCUMENT_COUNT("document_count"),
        }
    }

    public companion object {
        /** Kept well below the server's limit (100000) to bound memory per request. */
        public const val MAX_PAGE_SIZE: Int = 1000
    }
}
