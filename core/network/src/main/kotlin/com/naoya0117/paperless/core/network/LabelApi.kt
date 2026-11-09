package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.Page

/**
 * CRUD access to one kind of label (tags, correspondents, document types, storage paths, custom fields).
 *
 * @param T the label as read from the server
 * @param D the values to create or update it with
 */
public interface LabelApi<T, D> {
    /** One page; [ListQuery.page] starts at 1. A page beyond the last one is returned empty. */
    public suspend fun list(query: ListQuery = ListQuery()): Page<T>

    /**
     * Every object the user can see. Fetched in pages of [ListQuery.MAX_PAGE_SIZE]; if objects change
     * between pages, the result may miss some of them (it never contains duplicates).
     */
    public suspend fun listAll(): List<T>

    /** @throws PaperlessException.Http.NotFound */
    public suspend fun get(id: Long): T

    /** @throws PaperlessException.Http.BadRequest e.g. for a duplicate name */
    public suspend fun create(draft: D): T

    /**
     * Changes [current] to [draft] (PATCH), sending only the properties that differ. Returns [current]
     * without a request if nothing differs.
     */
    public suspend fun update(
        current: T,
        draft: D,
    ): T

    /** For tags, sub-tags are deleted too (and the request fails with 403 if one of them may not be deleted). */
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
