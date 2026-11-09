package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.Page
import com.naoya0117.paperless.core.network.LabelApi
import com.naoya0117.paperless.core.network.ListQuery
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.internal.dto.PageDto
import com.naoya0117.paperless.core.network.internal.dto.putOrdering
import com.naoya0117.paperless.core.network.internal.dto.putPaging
import com.naoya0117.paperless.core.network.internal.dto.toModel
import kotlinx.serialization.json.JsonObject

/**
 * The CRUD logic shared by all label endpoints. Each endpoint plugs in its Retrofit service methods,
 * DTO mapping and request bodies.
 *
 * @param extraListParams added to every list request (e.g. to make the server include optional fields).
 * @param supportsNameFilter / supportedOrderings what the endpoint's list supports; the server silently
 *   ignores anything else, so other options are rejected instead of returning unfiltered results.
 */
internal class CrudApiImpl<Dto, T, D>(
    private val listPage: suspend (Map<String, String>) -> PageDto<Dto>,
    private val getOne: suspend (Long) -> Dto,
    private val createOne: suspend (JsonObject) -> Dto,
    private val updateOne: suspend (Long, JsonObject) -> Dto,
    private val deleteOne: suspend (Long) -> Unit,
    private val toModel: (Dto) -> T,
    private val idOf: (T) -> Long,
    private val createBody: (D) -> JsonObject,
    private val patchBody: (T, D) -> JsonObject,
    private val extraListParams: Map<String, String> = emptyMap(),
    private val supportsNameFilter: Boolean = true,
    private val supportedOrderings: Set<ListQuery.Ordering.Field> =
        ListQuery.Ordering.Field.entries
            .toSet(),
) : LabelApi<T, D> {
    override suspend fun list(query: ListQuery): Page<T> {
        require(supportsNameFilter || query.nameContains == null) { "This endpoint cannot filter by name" }
        require(query.ordering == null || query.ordering.field in supportedOrderings) {
            "This endpoint cannot order by ${query.ordering?.field}"
        }
        return loadPage(query)
    }

    private suspend fun loadPage(query: ListQuery): Page<T> =
        pageOrEmptyBeyondEnd(query.page) { listPage(query.toQueryMap() + extraListParams) }
            .toModel { parsing { toModel(it) } }

    override suspend fun listAll(): List<T> {
        // Label endpoints cannot be ordered by a unique key, so pages may shift when objects change between
        // requests: large pages keep the window small, and items are de-duplicated by ID.
        val items = LinkedHashMap<Long, T>()
        var page = 1
        var maxPages = Int.MAX_VALUE
        while (true) {
            val result =
                try {
                    listPage(ListQuery(page = page, pageSize = ListQuery.MAX_PAGE_SIZE).toQueryMap() + extraListParams)
                } catch (e: PaperlessException.Http.NotFound) {
                    // "Invalid page": objects were deleted while paging.
                    if (page == 1) throw e
                    break
                }
            result.results.map { parsing { toModel(it) } }.forEach { items.putIfAbsent(idOf(it), it) }
            if (page == 1) maxPages = result.count / ListQuery.MAX_PAGE_SIZE + 2
            if (result.next == null || result.results.isEmpty()) break
            if (++page >
                maxPages
            ) {
                throw PaperlessException.InvalidResponse("pagination did not end after $maxPages pages")
            }
        }
        return items.values.toList()
    }

    override suspend fun get(id: Long): T = parsing { toModel(getOne(id)) }

    override suspend fun create(draft: D): T = parsing { toModel(createOne(createBody(draft))) }

    override suspend fun update(
        current: T,
        draft: D,
    ): T {
        val body = patchBody(current, draft)
        return if (body.isEmpty()) current else parsing { toModel(updateOne(idOf(current), body)) }
    }

    override suspend fun delete(id: Long) = deleteOne(id)
}

internal fun ListQuery.toQueryMap(): Map<String, String> =
    buildMap {
        putPaging(page, pageSize)
        nameContains?.takeIf { it.isNotBlank() }?.let { put("name__icontains", it) }
        ordering?.let { putOrdering(it.field.apiName, it.descending) }
    }
