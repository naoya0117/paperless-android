package com.naoya0117.paperless.core.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.testing.TestPager
import androidx.paging.testing.asSnapshot
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.database.CachedDocuments
import com.naoya0117.paperless.core.database.OfflineCache
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSearch
import com.naoya0117.paperless.core.model.TagFilter
import com.naoya0117.paperless.core.model.toDraft
import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class DocumentRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val cache = OfflineCache.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())
    private lateinit var accounts: AccountRepository
    private lateinit var documents: DocumentRepository
    private lateinit var accountId: String

    /** The server's documents in list order, as JSON objects by ID. */
    private val serverDocuments = CopyOnWriteArrayList<Long>()
    private val offline = AtomicBoolean(false)

    /** When set, the next document list request signals the first latch and waits for the second. */
    @Volatile private var blockNextList: Pair<CountDownLatch, CountDownLatch>? = null
    private val listRequests = CopyOnWriteArrayList<String>()

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    private val serverTitles = ConcurrentHashMap<Long, String>()

    private fun documentJson(
        id: Long,
        title: String = serverTitles[id] ?: "Doc $id",
    ) = """{"id": $id, "title": "$title", "content": "short", "created": "2026-01-01",""" +
        """ "added": "2026-01-01T00:00:00Z", "modified": "2026-01-01T00:00:00Z", "tags": [1]}"""

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .addHeader("X-Api-Version", "10")
        .body(body)
        .build()

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path == "/api/ui_settings/" ->
                            json(
                                200,
                                """{"user": {"id": 3, "username": "alice"}, "settings": {}}""",
                            )
                        offline.get() -> json(503, """{"detail": "unavailable"}""")
                        path == "/api/documents/" && request.method == "GET" -> {
                            listRequests += request.url.query.orEmpty()
                            val page = request.url.queryParameter("page")!!.toInt()
                            val size = request.url.queryParameter("page_size")!!.toInt()
                            val ids = serverDocuments.toList()
                            val slice = ids.drop((page - 1) * size).take(size)
                            val next = if (page * size < ids.size) "\"http://x/?page=${page + 1}\"" else "null"
                            val results = slice.joinToString { documentJson(it) }
                            // Built before blocking: a response that is already stale when it arrives.
                            val response = json(200, """{"count": ${ids.size}, "next": $next, "results": [$results]}""")
                            blockNextList?.let { (requested, release) ->
                                blockNextList = null
                                requested.countDown()
                                release.await(10, TimeUnit.SECONDS)
                            }
                            response
                        }
                        request.method == "PATCH" -> {
                            val id = path.split("/")[3].toLong()
                            serverTitles[id] = "Renamed"
                            json(200, documentJson(id))
                        }
                        request.method == "DELETE" -> MockResponse.Builder().code(204).build()
                        else -> json(404, """{"detail": "Not found."}""")
                    }
                }
            }
        server.start()
        accounts =
            AccountRepository(
                AccountStore.create(folder.root.resolve("accounts.json"), XorCipher),
                PaperlessClientFactory(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()),
                { error("no client certificates in this test") },
                { cache.clear(it) },
            )
        documents = DocumentRepository(accounts, cache)
    }

    @After
    fun tearDown() = server.close()

    private suspend fun signIn() {
        accountId = accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls()).id
    }

    @OptIn(ExperimentalPagingApi::class)
    private suspend fun load(
        loadType: LoadType,
        query: DocumentQuery = DocumentQuery(),
    ): RemoteMediator.MediatorResult {
        val session = accounts.currentSession()!!
        val state = PagingState<Int, Document>(emptyList(), null, PagingConfig(DocumentRepository.PAGE_SIZE), 0)
        return documents.mediator(session, query).load(loadType, state)
    }

    private suspend fun cachedIds(query: DocumentQuery = DocumentQuery()): List<Long> {
        val source = cache.documents.list(accountId, DocumentRepository.listKey(query))
        val page = TestPager(PagingConfig(pageSize = 100), source).refresh() as PagingSource.LoadResult.Page
        return page.data.map { it.id }
    }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun `pages are fetched one after another and kept in the cache`() =
        runTest {
            serverDocuments += (1L..60L)
            signIn()

            val results =
                listOf(load(LoadType.REFRESH), load(LoadType.APPEND), load(LoadType.APPEND), load(LoadType.APPEND))

            assertEquals(
                listOf(false, false, true, true),
                results.map { (it as RemoteMediator.MediatorResult.Success).endOfPaginationReached },
            )
            assertEquals((1L..60L).toList(), cachedIds())
            assertEquals(listOf("page=1", "page=2", "page=3"), listRequests.map { it.substringBefore("&") })
            assertTrue((load(LoadType.PREPEND) as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun `the paged flow shows the cached list`() =
        runTest {
            serverDocuments += (1L..10L)
            signIn()
            load(LoadType.REFRESH)

            assertEquals((1L..10L).toList(), documents.documents(DocumentQuery()).asSnapshot().map { it.id })
        }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun `a further page of a list that is no longer cached starts over`() =
        runTest {
            serverDocuments += (1L..30L)
            signIn()

            // No REFRESH stored the list (e.g. it was dropped): APPEND loads the first page instead.
            val result = load(LoadType.APPEND)

            assertEquals(false, (result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals((1L..25L).toList(), cachedIds())
        }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun `failures become load errors`() =
        runTest {
            signIn()
            offline.set(true)

            val result = load(LoadType.REFRESH)

            assertTrue((result as RemoteMediator.MediatorResult.Error).throwable is PaperlessException)
        }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun `the cached list is shown when the server cannot be reached`() =
        runTest {
            serverDocuments += (1L..10L)
            signIn()
            load(LoadType.REFRESH)

            offline.set(true)
            val refresh = runCatching { documents.documents(DocumentQuery()).asSnapshot() }

            assertTrue(refresh.exceptionOrNull().toString(), refresh.exceptionOrNull() is PaperlessException)
            // What the UI pages from: the cached list is still there.
            val cached =
                TestPager(
                    PagingConfig(pageSize = 25),
                    cache.documents.list(accountId, DocumentRepository.listKey(DocumentQuery())),
                ).refresh() as PagingSource.LoadResult.Page
            assertEquals((1L..10L).toList(), cached.data.map { it.id })
        }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun `documents that moved between pages appear once`() =
        runTest {
            serverDocuments += (1L..30L)
            signIn()
            load(LoadType.REFRESH)
            // A new document at the top shifts page 2 by one: document 25 is listed again.
            serverDocuments.add(0, 100)

            load(LoadType.APPEND)

            assertEquals((1L..30L).toList(), cachedIds())
        }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun `updates and deletions reach the cached lists`() =
        runTest {
            serverDocuments += (1L..3L)
            signIn()
            load(LoadType.REFRESH)
            val first = cache.documents.observe(accountId, 1).first()!!

            documents.update(first, first.toDraft().copy(title = "Renamed"))
            assertEquals(
                "Renamed",
                cache.documents
                    .observe(accountId, 1)
                    .first()
                    ?.title,
            )
            assertEquals(
                "list content is kept",
                "short",
                cache.documents
                    .observe(accountId, 1)
                    .first()
                    ?.content,
            )

            documents.delete(2)
            assertNull(cache.documents.observe(accountId, 2).first())
        }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun `signing out deletes the cached documents`() =
        runTest {
            serverDocuments += (1L..3L)
            signIn()
            load(LoadType.REFRESH)

            accounts.signOut(accountId)

            assertNull(cache.documents.observe(accountId, 1).first())
            assertEquals(emptyList<Any>(), documents.documents(DocumentQuery()).asSnapshot())
        }

    @Test
    fun `the list key ignores paging`() {
        assertEquals(
            DocumentRepository.listKey(DocumentQuery(page = 1, pageSize = 10)),
            DocumentRepository.listKey(DocumentQuery(page = 3, pageSize = 50)),
        )
    }

    @Test
    fun `equal filters built in a different order share the list`() {
        assertEquals(
            DocumentRepository.listKey(
                DocumentQuery(
                    tags = TagFilter.Matching(all = setOf(3, 1)),
                    extraParams =
                        mapOf(
                            "a" to "1",
                            "b" to "2",
                        ),
                ),
            ),
            DocumentRepository.listKey(
                DocumentQuery(
                    tags = TagFilter.Matching(all = setOf(1, 3)),
                    extraParams =
                        mapOf(
                            "b" to "2",
                            "a" to "1",
                        ),
                ),
            ),
        )
        assertNotEquals(
            DocumentRepository.listKey(DocumentQuery(search = DocumentSearch.Text("a&b"))),
            DocumentRepository.listKey(
                DocumentQuery(search = DocumentSearch.Text("a"), extraParams = mapOf("b" to "")),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a query the server cannot be sent is rejected at once`() {
        documents.documents(
            DocumentQuery(search = DocumentSearch.Text("x"), extraParams = mapOf("title_search" to "y")),
        )
    }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun `a list on screen is not dropped by other lists`() =
        runTest {
            serverDocuments += (1L..3L)
            signIn()
            val mainKey = DocumentRepository.listKey(DocumentQuery())
            load(LoadType.REFRESH)
            // Collecting keeps the pager running, as a screen does.
            val onScreen = launch(Dispatchers.IO) { documents.documents(DocumentQuery()).collect {} }
            withContext(Dispatchers.IO) {
                while (mainKey !in documents.inUse(accountId)) Thread.sleep(20)
            }

            // One more than MAX_LISTS besides the one being written: the oldest search is dropped.
            for (i in 0..CachedDocuments.MAX_LISTS + 1) {
                load(LoadType.REFRESH, DocumentQuery(search = DocumentSearch.Title("search $i")))
            }

            assertNotNull(cache.documents.listState(accountId, DocumentRepository.listKey(DocumentQuery())))
            assertNull(
                "lists not in use are dropped",
                cache.documents.listState(
                    accountId,
                    DocumentRepository.listKey(DocumentQuery(search = DocumentSearch.Title("search 0"))),
                ),
            )
            onScreen.cancel()
        }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun `a list fetched before an edit does not undo it`() =
        runTest {
            serverDocuments += (1L..3L)
            signIn()
            load(LoadType.REFRESH)
            val first = cache.documents.observe(accountId, 1).first()!!
            val requested = CountDownLatch(1)
            val release = CountDownLatch(1)
            blockNextList = requested to release

            val refresh = async(Dispatchers.IO) { load(LoadType.REFRESH) }
            withContext(Dispatchers.IO) { assertTrue(requested.await(10, TimeUnit.SECONDS)) }
            documents.update(first, first.toDraft().copy(title = "Renamed"))
            release.countDown()
            refresh.await()

            // The list requested before the edit still says "Doc 1"; it must not replace the edited document.
            assertEquals(
                "Renamed",
                cache.documents
                    .observe(accountId, 1)
                    .first()
                    ?.title,
            )
        }
}
