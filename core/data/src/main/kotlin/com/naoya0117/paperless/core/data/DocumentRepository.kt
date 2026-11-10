package com.naoya0117.paperless.core.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.naoya0117.paperless.core.database.CachedDocuments
import com.naoya0117.paperless.core.database.OfflineCache
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.DocumentDraft
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Documents of the active account: lists are paged from the offline cache, which is filled from the server
 * as the user scrolls.
 */
@Singleton
class DocumentRepository internal constructor(
    private val accounts: AccountRepository,
    cache: OfflineCache,
    private val clock: Clock,
) {
    @Inject
    constructor(accounts: AccountRepository, cache: OfflineCache) : this(accounts, cache, Clock.systemUTC())

    private val cache: CachedDocuments = cache.documents

    /** Serializes cache writes with the server requests whose results they store (see [Labels]). */
    private val mutex = Mutex()

    /**
     * The documents matching [query] for the active account, shown from the cache first while the first
     * page is fetched again; further pages are fetched while scrolling. [DocumentQuery.page] and
     * [DocumentQuery.pageSize] are ignored. Failed requests surface as `LoadState.Error` (mediator) with a
     * [PaperlessException]; the cached list stays visible. Emits empty data while there is no account.
     */
    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalPagingApi::class)
    fun documents(query: DocumentQuery): Flow<PagingData<Document>> {
        val listQuery = query.copy(page = 1, pageSize = PAGE_SIZE)
        val listKey = listKey(query)
        return accounts.state
            .map { it.activeAccountId }
            .distinctUntilChanged()
            .flatMapLatest { accountId ->
                if (accountId == null) {
                    flowOf(NO_DOCUMENTS)
                } else {
                    flow {
                        val session = accounts.currentSession()
                        if (session == null || session.account.id != accountId) {
                            emit(NO_DOCUMENTS)
                        } else {
                            val pager =
                                Pager(
                                    config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE * 2),
                                    remoteMediator = Mediator(session, listQuery, listKey),
                                    pagingSourceFactory = { cache.list(accountId, listKey) },
                                )
                            emitAll(pager.flow)
                        }
                    }
                }
            }
    }

    /**
     * Updates the document on the server and in the cache.
     *
     * @throws NotSignedInException
     * @see com.naoya0117.paperless.core.network.DocumentsApi.update
     */
    suspend fun update(
        current: Document,
        draft: DocumentDraft,
    ): Document {
        val session = accounts.currentSession() ?: throw NotSignedInException()
        val updated = session.client.documents.update(current, draft)
        if (updated !== current) {
            // List results carry shortened content; keep the cached content rather than the full text.
            val forCache = updated.copy(content = current.content, searchHit = null)
            withContext(NonCancellable) {
                mutex.withLock { accounts.writeData(session) { cache.update(session.account.id, forCache) } }
            }
        }
        return updated
    }

    /**
     * Moves the document to the trash on the server and removes it from the cached lists. A document the
     * server no longer has (404) counts as deleted.
     *
     * @throws NotSignedInException
     */
    suspend fun delete(id: Long) {
        val session = accounts.currentSession() ?: throw NotSignedInException()
        try {
            session.client.documents.delete(id)
        } catch (_: PaperlessException.Http.NotFound) {
            // Already gone.
        }
        withContext(NonCancellable) {
            mutex.withLock { accounts.writeData(session) { cache.delete(session.account.id, id) } }
        }
    }

    @OptIn(ExperimentalPagingApi::class)
    private inner class Mediator(
        private val session: AccountSession,
        private val query: DocumentQuery,
        private val listKey: String,
    ) : RemoteMediator<Int, Document>() {
        private val accountId = session.account.id

        override suspend fun initialize() = InitializeAction.LAUNCH_INITIAL_REFRESH

        override suspend fun load(
            loadType: LoadType,
            state: PagingState<Int, Document>,
        ): MediatorResult =
            mutex.withLock {
                val page =
                    when (loadType) {
                        LoadType.REFRESH -> 1
                        LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
                        LoadType.APPEND ->
                            cache.listState(accountId, listKey)?.nextPage
                                ?: return MediatorResult.Success(endOfPaginationReached = true)
                    }
                try {
                    val result = session.client.documents.list(query.copy(page = page))
                    val nextPage = if (result.hasNext && result.items.isNotEmpty()) page + 1 else null
                    accounts.writeData(session) {
                        if (loadType == LoadType.REFRESH) {
                            cache.replaceList(accountId, listKey, result.items, nextPage, clock.instant())
                        } else {
                            cache.appendToList(accountId, listKey, result.items, nextPage)
                        }
                    }
                    MediatorResult.Success(endOfPaginationReached = nextPage == null)
                } catch (e: PaperlessException) {
                    MediatorResult.Error(e)
                }
            }
    }

    companion object {
        const val PAGE_SIZE: Int = 25

        /** Nothing to show and nothing more to load (no account). */
        private val NO_DOCUMENTS: PagingData<Document> =
            LoadState.NotLoading(endOfPaginationReached = true).let { done ->
                PagingData.empty(LoadStates(refresh = done, prepend = done, append = done))
            }

        /**
         * Identifies the cached list of [query], ignoring paging. Stable for equal queries within an app
         * version; a changed format only misses the cache once.
         */
        internal fun listKey(query: DocumentQuery): String {
            val normalized = query.copy(page = 1, pageSize = PAGE_SIZE)
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toString().encodeToByteArray())
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
