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
import com.naoya0117.paperless.core.model.Note
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.toQueryParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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

    /** Serializes cache writes (not the requests, which may be slow). */
    private val writeLock = Mutex()

    /** Counts update/delete cache writes, so a list fetched before one of them can be fetched again. */
    private val writeCount = AtomicLong()

    /** Lists with a running pager, per account; they are not dropped from the cache while in use. */
    private val listsInUse = ConcurrentHashMap<Pair<String, String>, Int>()

    /**
     * The documents matching [query] for the active account, shown from the cache first while the first
     * page is fetched again; further pages are fetched while scrolling. [DocumentQuery.page] and
     * [DocumentQuery.pageSize] are ignored. Failed requests surface as the mediator's `LoadState.Error`
     * (usually with a [PaperlessException]) while the cached list stays visible. Starts over when the active
     * account or its connection changes (e.g. signing in again); emits empty data while there is none.
     *
     * @throws IllegalArgumentException if [DocumentQuery.extraParams] conflict with the typed properties
     */
    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalPagingApi::class)
    fun documents(query: DocumentQuery): Flow<PagingData<Document>> {
        val listQuery = query.copy(page = 1, pageSize = PAGE_SIZE)
        val listKey = listKey(query)
        return accounts.activeClient.flatMapLatest { client ->
            if (client == null) {
                flowOf(NO_DOCUMENTS)
            } else {
                flow {
                    // The current session, with the client just emitted unless it changed again meanwhile (then
                    // activeClient emits the newer one and this flow is replaced).
                    val session = accounts.currentSession()
                    if (session == null) {
                        emit(NO_DOCUMENTS)
                        return@flow
                    }
                    val accountId = session.account.id
                    val inUse = accountId to listKey
                    listsInUse.merge(inUse, 1, Int::plus)
                    try {
                        val pager =
                            Pager(
                                config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE * 2),
                                remoteMediator = Mediator(session, listQuery, listKey),
                                pagingSourceFactory = { cache.list(accountId, listKey) },
                            )
                        emitAll(pager.flow)
                    } finally {
                        listsInUse.computeIfPresent(inUse) { _, count -> (count - 1).takeIf { it > 0 } }
                    }
                }
            }
        }
    }

    /**
     * The document as cached for the active account (from a list), or `null` if it is not cached (e.g. it was
     * opened from a link). Updated when the document is edited or fetched again.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cached(id: Long): Flow<Document?> =
        accounts.activeClient.flatMapLatest {
            flow {
                val session = accounts.currentSession()
                if (session == null) emit(null) else emitAll(cache.observe(session.account.id, id))
            }
        }

    /**
     * How many documents are in the inbox (have an inbox tag): 0 also when no inbox tag is set up.
     *
     * @throws NotSignedInException
     * @throws PaperlessException e.g. when offline
     */
    suspend fun inboxCount(): Int {
        val session = accounts.currentSession() ?: throw NotSignedInException()
        return session.client.documents
            .list(DocumentQuery(inInbox = true, pageSize = 1, truncateContent = true))
            .count
    }

    /** The inbox documents cached for the active account (e.g. to offer the inbox while offline); 0 if none. */
    suspend fun cachedInboxCount(): Int {
        val account = accounts.state.first().activeAccount ?: return 0
        return cache.itemCount(account.id, listKey(DocumentQuery(inInbox = true)))
    }

    /**
     * The document from the server (with its full content and notes); also refreshes the cached copy.
     *
     * @throws NotSignedInException
     * @throws PaperlessException e.g. when offline
     */
    suspend fun fetch(id: Long): Document {
        val session = accounts.currentSession() ?: throw NotSignedInException()
        val document = session.client.documents.get(id)
        writeThrough(session) { cache.update(session.account.id, document) }
        return document
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
        if (updated !==
            current
        ) {
            writeThrough(session) { cache.update(session.account.id, updated.copy(searchHit = null)) }
        }
        return updated
    }

    /**
     * Adds a note to the document.
     *
     * @return all notes of the document, oldest first
     * @throws NotSignedInException
     */
    suspend fun addNote(
        documentId: Long,
        text: String,
    ): List<Note> {
        val session = accounts.currentSession() ?: throw NotSignedInException()
        return session.client.documents.addNote(documentId, text)
    }

    /**
     * Deletes a note of the document.
     *
     * @return the remaining notes, oldest first
     * @throws NotSignedInException
     */
    suspend fun deleteNote(
        documentId: Long,
        noteId: Long,
    ): List<Note> {
        val session = accounts.currentSession() ?: throw NotSignedInException()
        return session.client.documents.deleteNote(documentId, noteId)
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
        writeThrough(session) { cache.delete(session.account.id, id) }
    }

    /** Not cancellable: the server already changed, and the cache must not miss it. */
    private suspend fun writeThrough(
        session: AccountSession,
        block: suspend () -> Unit,
    ) = withContext(NonCancellable) {
        writeLock.withLock {
            accounts.writeData(session, block)
            writeCount.incrementAndGet()
        }
    }

    /** The keys of the account's lists with a running pager. */
    internal fun inUse(accountId: String): Set<String> =
        listsInUse.keys
            .filter { it.first == accountId }
            .mapTo(mutableSetOf()) { it.second }

    /** The mediator of [query]'s list for [session], e.g. to drive it step by step in tests. */
    @OptIn(ExperimentalPagingApi::class)
    internal fun mediator(
        session: AccountSession,
        query: DocumentQuery,
    ): RemoteMediator<Int, Document> = Mediator(session, query.copy(page = 1, pageSize = PAGE_SIZE), listKey(query))

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
        ): MediatorResult {
            if (loadType == LoadType.PREPEND) return MediatorResult.Success(endOfPaginationReached = true)
            // A list no longer cached (e.g. after signing in again) starts over from the first page.
            val list = if (loadType == LoadType.APPEND) cache.listState(accountId, listKey) else null
            if (list != null && list.nextPage == null) return MediatorResult.Success(endOfPaginationReached = true)
            val page = list?.nextPage ?: 1
            return try {
                load(page, replace = page == 1, retries = 1)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // PaperlessException, but also e.g. a query the request builder rejects: never crash the UI.
                MediatorResult.Error(e)
            }
        }

        private suspend fun load(
            page: Int,
            replace: Boolean,
            retries: Int,
        ): MediatorResult {
            val writesBefore = writeCount.get()
            val result = session.client.documents.list(query.copy(page = page))
            val nextPage = if (result.hasNext && result.items.isNotEmpty()) page + 1 else null
            var retry = false
            var stored = false
            writeLock.withLock {
                // An update or deletion since the request started may be missing from the result.
                if (writeCount.get() != writesBefore && retries > 0) {
                    retry = true
                    return@withLock
                }
                accounts.writeData(session) {
                    stored =
                        if (replace) {
                            cache.replaceList(
                                accountId,
                                listKey,
                                result.items,
                                nextPage,
                                clock.instant(),
                                inUse(accountId),
                            )
                            true
                        } else {
                            cache.appendToList(accountId, listKey, result.items, nextPage)
                        }
                }
            }
            return when {
                retry -> load(page, replace, retries - 1)
                // Signed out meanwhile: nothing more to load for this session.
                !stored && !accounts.isCurrent(session) -> MediatorResult.Success(endOfPaginationReached = true)
                // The list was dropped while loading a further page: start over.
                !stored -> load(1, replace = true, retries = 0)
                else -> MediatorResult.Success(endOfPaginationReached = nextPage == null)
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
         * Identifies the cached list of [query]: the request parameters without paging, in a canonical order
         * (so equal filters built in a different order share the list).
         *
         * @throws IllegalArgumentException if the query cannot be sent
         */
        fun listKey(query: DocumentQuery): String {
            val canonical =
                query
                    .toQueryParameters()
                    .filterKeys { it != "page" && it != "page_size" }
                    .toSortedMap()
                    .entries
                    .joinToString("&") { (key, value) -> "${encode(key)}=${encode(sortedIdList(value))}" }
            val digest = MessageDigest.getInstance("SHA-256").digest("v1?$canonical".encodeToByteArray())
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        /** ID lists (`1,5,3`) come from sets; their order does not matter. */
        private fun sortedIdList(value: String): String {
            val ids = value.split(',').map { it.toLongOrNull() ?: return value }
            return ids.sorted().joinToString(",")
        }
    }
}
