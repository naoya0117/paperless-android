package com.naoya0117.paperless.core.data

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import androidx.paging.testing.asSnapshot
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.database.OfflineCache
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSearch
import com.naoya0117.paperless.core.model.toDraft
import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
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
    private val listRequests = CopyOnWriteArrayList<String>()

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    private fun documentJson(
        id: Long,
        title: String = "Doc $id",
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
                            json(
                                200,
                                """{"count": ${ids.size}, "next": $next, "results": [${slice.joinToString {
                                    documentJson(
                                        it,
                                    )
                                }}]}""",
                            )
                        }
                        request.method == "PATCH" -> json(200, documentJson(path.split("/")[3].toLong(), "Renamed"))
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

    @Test
    fun `pages are fetched while scrolling and kept in the cache`() =
        runTest {
            serverDocuments += (1L..60L)
            signIn()

            val loaded = documents.documents(DocumentQuery()).asSnapshot { scrollTo(59) }

            assertEquals((1L..60L).toList(), loaded.map { it.id })
            assertEquals(listOf("page=1", "page=2", "page=3"), listRequests.map { it.substringBefore("&") }.distinct())
            val state = cache.documents.listState(accountId, DocumentRepository.listKey(DocumentQuery()))
            assertNull("the end was reached", state?.nextPage)
        }

    @Test
    fun `the cached list is shown when the server cannot be reached`() =
        runTest {
            serverDocuments += (1L..10L)
            signIn()
            documents.documents(DocumentQuery()).asSnapshot()

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

    @Test
    fun `documents that moved between pages appear once`() =
        runTest {
            serverDocuments += (1L..30L)
            signIn()
            val query = DocumentQuery(search = DocumentSearch.Title("Doc"))
            documents.documents(query).asSnapshot()
            // A new document at the top shifts page 2 by one: document 25 is listed again.
            serverDocuments.add(0, 100)

            val loaded = documents.documents(query).asSnapshot { scrollTo(29) }

            assertEquals(loaded.map { it.id }.distinct(), loaded.map { it.id })
        }

    @Test
    fun `updates and deletions reach the cached lists`() =
        runTest {
            serverDocuments += (1L..3L)
            signIn()
            val first = documents.documents(DocumentQuery()).asSnapshot().first()

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

    @Test
    fun `signing out deletes the cached documents`() =
        runTest {
            serverDocuments += (1L..3L)
            signIn()
            documents.documents(DocumentQuery()).asSnapshot()

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
}
