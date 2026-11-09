package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.Page
import com.naoya0117.paperless.core.network.LabelApi
import com.naoya0117.paperless.core.network.ListQuery
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.internal.dto.PageDto
import com.naoya0117.paperless.core.network.internal.dto.toModel

/**
 * The CRUD logic shared by all label endpoints. Each endpoint plugs in its Retrofit service methods and
 * DTO mappings.
 */
internal class CrudApiImpl<Dto, T, D, WriteDto>(
    private val listPage: suspend (Map<String, String>) -> PageDto<Dto>,
    private val getOne: suspend (Long) -> Dto,
    private val createOne: suspend (WriteDto) -> Dto,
    private val updateOne: suspend (Long, WriteDto) -> Dto,
    private val deleteOne: suspend (Long) -> Unit,
    private val toModel: (Dto) -> T,
    private val toCreateDto: (D) -> WriteDto,
    private val toUpdateDto: (D) -> WriteDto = toCreateDto,
) : LabelApi<T, D> {
    override suspend fun list(query: ListQuery): Page<T> = listPage(query.toQueryMap()).toModel(toModel)

    override suspend fun listAll(): List<T> {
        val items = mutableListOf<T>()
        var page = 1
        while (true) {
            val result = listPage(ListQuery(page = page, pageSize = ALL_PAGE_SIZE).toQueryMap())
            items += result.results.map(toModel)
            if (result.next == null || result.results.isEmpty()) return items
            // A server whose count keeps growing must not keep us paging forever.
            if (page > result.count / ALL_PAGE_SIZE + 1) {
                throw PaperlessException.InvalidResponse("pagination did not end after $page pages")
            }
            page++
        }
    }

    override suspend fun get(id: Long): T = toModel(getOne(id))

    override suspend fun create(draft: D): T = toModel(createOne(toCreateDto(draft)))

    override suspend fun update(
        id: Long,
        draft: D,
    ): T = toModel(updateOne(id, toUpdateDto(draft)))

    override suspend fun delete(id: Long) = deleteOne(id)

    private companion object {
        const val ALL_PAGE_SIZE = 100
    }
}

internal fun ListQuery.toQueryMap(): Map<String, String> =
    buildMap {
        put("page", page.toString())
        put("page_size", pageSize.toString())
        nameContains?.takeIf { it.isNotBlank() }?.let { put("name__icontains", it) }
        ordering?.let { put("ordering", (if (it.descending) "-" else "") + it.field.apiName) }
    }
