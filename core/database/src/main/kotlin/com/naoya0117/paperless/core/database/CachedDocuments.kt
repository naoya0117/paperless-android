package com.naoya0117.paperless.core.database

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.naoya0117.paperless.core.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

/** How far a cached document list was loaded. */
data class DocumentListState(
    /** The server page to load next; `null` when the end was reached. */
    val nextPage: Int?,
    val refreshedAt: Instant,
)

/**
 * Cached document lists, one per query ([listKey], chosen by the caller), per account. A document is
 * stored once and shared by every list containing it, so changing it changes every list.
 */
class CachedDocuments internal constructor(
    private val database: CacheDatabase,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) {
    private val dao = database.documents()

    /** The list in server order; invalidated whenever the cached documents change. */
    fun list(
        accountId: String,
        listKey: String,
    ): PagingSource<Int, Document> = MappedPagingSource(dao.list(accountId, listKey), DocumentListRow::toModel)

    /** `null` if the list was never loaded. */
    suspend fun listState(
        accountId: String,
        listKey: String,
    ): DocumentListState? =
        dao.listState(accountId, listKey)?.let { DocumentListState(it.nextPage, Instant.ofEpochMilli(it.refreshedAt)) }

    /**
     * Replaces the list with its first page. Besides this list and [inUse] (e.g. lists on screen), keeps only
     * the [MAX_LISTS] most recently refreshed lists of the account, and deletes documents no list contains
     * anymore (so [observe] only sees documents of cached lists).
     */
    suspend fun replaceList(
        accountId: String,
        listKey: String,
        firstPage: List<Document>,
        nextPage: Int?,
        refreshedAt: Instant,
        inUse: Set<String> = emptySet(),
    ) = transaction {
        dao.clearList(accountId, listKey)
        dao.upsertDocuments(firstPage.map { it.toEntity(accountId) })
        dao.insertItems(firstPage.mapIndexed { index, document -> document.toListItem(accountId, listKey, index) })
        dao.upsertList(DocumentListEntity(accountId, listKey, nextPage, refreshedAt.toEpochMilli()))
        dao.deleteOldLists(accountId, inUse + listKey, MAX_LISTS)
        dao.deleteItemsOfMissingLists(accountId)
        dao.deleteUnlistedDocuments(accountId)
    }

    /**
     * Adds a further page to the end of the list. Documents already in it (when server pages shifted
     * between requests) keep their place.
     *
     * @return `false` if the list is not cached (anymore), so nothing was added
     */
    suspend fun appendToList(
        accountId: String,
        listKey: String,
        page: List<Document>,
        nextPage: Int?,
    ): Boolean {
        var appended = false
        transaction {
            val list = dao.listState(accountId, listKey)
            if (list != null) {
                val start = dao.nextPosition(accountId, listKey)
                dao.upsertDocuments(page.map { it.toEntity(accountId) })
                dao.insertItems(
                    page.mapIndexed { index, document ->
                        document.toListItem(
                            accountId,
                            listKey,
                            start + index,
                        )
                    },
                )
                dao.upsertList(list.copy(nextPage = nextPage))
                appended = true
            }
        }
        return appended
    }

    /** `null` if the document is not cached. */
    fun observe(
        accountId: String,
        id: Long,
    ): Flow<Document?> = dao.observe(accountId, id).map { it?.toModel() }

    /**
     * Replaces a cached document after editing it; lists keep their order and the cached (list-length)
     * content is kept. A document that is not cached is not added. Lists are not re-filtered: a document
     * that no longer matches a list's query leaves it on the list's next refresh.
     */
    suspend fun update(
        accountId: String,
        document: Document,
    ) = transaction {
        dao.get(accountId, document.id)?.let { cached ->
            dao.update(document.toEntity(accountId).copy(content = cached.content))
        }
    }

    /** Removes the document from the cache and every list. */
    suspend fun delete(
        accountId: String,
        id: Long,
    ) = transaction {
        dao.deleteFromLists(accountId, id)
        dao.deleteDocument(accountId, id)
    }

    internal suspend fun clear(accountId: String) {
        dao.deleteAllListItems(accountId)
        dao.deleteAllLists(accountId)
        dao.deleteAllDocuments(accountId)
    }

    companion object {
        /** Cached lists per account besides those in use; each distinct query (e.g. every search) makes a list. */
        const val MAX_LISTS: Int = 20
    }
}

/** Maps the items of [source] while keeping its keys and invalidation. */
private class MappedPagingSource<T : Any, R : Any>(
    private val source: PagingSource<Int, T>,
    private val transform: (T) -> R,
) : PagingSource<Int, R>() {
    init {
        source.registerInvalidatedCallback { invalidate() }
        registerInvalidatedCallback { source.invalidate() }
    }

    override val jumpingSupported: Boolean get() = source.jumpingSupported

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, R> =
        when (val result = source.load(params)) {
            is LoadResult.Page ->
                LoadResult.Page(
                    // Off the collector's (often main) thread: rows hold JSON to decode.
                    withContext(Dispatchers.Default) { result.data.map(transform) },
                    result.prevKey,
                    result.nextKey,
                    result.itemsBefore,
                    result.itemsAfter,
                )
            is LoadResult.Error -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
        }

    /** Room's keys are row offsets: restart around the anchor, like Room's own sources. */
    override fun getRefreshKey(state: PagingState<Int, R>): Int? =
        state.anchorPosition?.let { anchor -> maxOf(0, anchor - state.config.initialLoadSize / 2) }
}
