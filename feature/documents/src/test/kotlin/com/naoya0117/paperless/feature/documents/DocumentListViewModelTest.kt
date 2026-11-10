package com.naoya0117.paperless.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.DocumentRepository
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.data.PaperlessClientFactory
import com.naoya0117.paperless.core.database.OfflineCache
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DocumentListViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val inboxRequests =
        java.util.concurrent.atomic
            .AtomicInteger()
    private val offline =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val cache = OfflineCache.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())
    private lateinit var accounts: AccountRepository

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Api-Version", "10")
            .body(body)
            .build()

    private fun page(vararg items: String) =
        json("""{"count": ${items.size}, "next": null, "results": [${items.joinToString()}]}""")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.url.encodedPath) {
                        "/api/ui_settings/" -> json("""{"user": {"id": 3, "username": "alice"}, "settings": {}}""")
                        "/api/tags/" ->
                            page(
                                """{"id": 1, "name": "Inbox", "color": "#ff0000", "text_color": "#ffffff"}""",
                            )
                        "/api/correspondents/" -> page("""{"id": 2, "name": "ACME"}""")
                        "/api/document_types/" -> page("""{"id": 3, "name": "Invoice"}""")
                        "/api/documents/" ->
                            if (request.url.queryParameter("is_in_inbox") == "true") {
                                inboxRequests.incrementAndGet()
                                if (offline.get()) {
                                    return MockResponse
                                        .Builder()
                                        .code(503)
                                        .body("{}")
                                        .build()
                                }
                                json("""{"count": 3, "next": "http://x/?page=2", "results": []}""")
                            } else {
                                page()
                            }
                        else -> page()
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.close()
    }

    private fun viewModel(savedState: SavedStateHandle) =
        DocumentListViewModel(
            DocumentRepository(accounts, cache),
            LabelRepository(accounts, cache),
            accounts,
            savedState,
        )

    private fun viewModel(mode: DocumentListMode) =
        viewModel(SavedStateHandle(mapOf(DocumentListViewModel.MODE_ARGUMENT to mode.name)))

    @Test
    fun `the mode comes from the navigation argument`() {
        assertEquals(DocumentListMode.INBOX, viewModel(DocumentListMode.INBOX).mode)
        assertEquals(DocumentListMode.ALL, viewModel(SavedStateHandle()).mode)
    }

    @Test
    fun `labels are refreshed and named`() =
        runTest {
            accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())

            val viewModel = viewModel(DocumentListMode.ALL)
            val labels =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(10_000) {
                        viewModel.labelNames.first { it.tags.isNotEmpty() && it.documentTypes.isNotEmpty() }
                    }
                }

            assertEquals(TagLabel("Inbox", "#ff0000", "#ffffff"), labels.tags[1])
            assertEquals("ACME", labels.correspondents[2])
            assertEquals("Invoice", labels.documentTypes[3])
        }

    @Test
    fun `the search text is kept across process death`() {
        val savedState = SavedStateHandle(mapOf(DocumentListViewModel.MODE_ARGUMENT to DocumentListMode.ALL.name))
        viewModel(savedState).setSearchText("invoice")

        assertEquals("invoice", viewModel(savedState).searchText.value)
    }

    @Test
    fun `the active account is exposed for thumbnails`() =
        runTest {
            val account = accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())
            val viewModel = viewModel(DocumentListMode.ALL)

            val accountId =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(10_000) { viewModel.accountId.first { it != null } }
                }

            assertEquals(account.id, accountId)
        }

    @Test
    fun `the inbox count comes from the server`() =
        runTest {
            accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())
            // The count is set from the network thread: the view model must run on a real dispatcher here.
            Dispatchers.setMain(Dispatchers.Unconfined)

            val inbox = InboxViewModel(DocumentRepository(accounts, cache))

            val count = withContext(Dispatchers.Default) { withTimeout(10_000) { inbox.count.first { it > 0 } } }
            assertEquals(3, count)
        }

    @Test
    fun `without an account the inbox count stays 0`() {
        assertEquals(0, InboxViewModel(DocumentRepository(accounts, cache)).count.value)
    }

    private class MutableClock(
        var now: java.time.Instant = java.time.Instant.parse("2026-11-10T00:00:00Z"),
    ) : java.time.Clock() {
        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this

        override fun instant(): java.time.Instant = now
    }

    private suspend fun InboxViewModel.awaitCount(predicate: (Int) -> Boolean): Int =
        withContext(Dispatchers.Default) { withTimeout(10_000) { count.first(predicate) } }

    @Test
    fun `returning to the list refreshes the inbox count at most every 30 seconds`() =
        runTest {
            accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())
            Dispatchers.setMain(Dispatchers.Unconfined)
            val clock = MutableClock()
            val inbox = InboxViewModel(DocumentRepository(accounts, cache), clock)
            inbox.awaitCount { it == 3 }

            inbox.refresh()
            assertEquals("throttled", 1, inboxRequests.get())
            inbox.refresh(force = true)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (inboxRequests.get() <
                        2
                    ) {
                        kotlinx.coroutines.delay(10)
                    }
                }
            }
            clock.now = clock.now.plusSeconds(31)
            inbox.refresh()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    while (inboxRequests.get() <
                        3
                    ) {
                        kotlinx.coroutines.delay(10)
                    }
                }
            }
        }

    @Test
    fun `offline the cached inbox keeps the button`() =
        runTest {
            val account = accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())
            val document =
                com.naoya0117.paperless.core.model.Document(
                    id = 1,
                    title = "Scan",
                    content = "",
                    created = java.time.LocalDate.of(2026, 1, 1),
                    added = java.time.Instant.EPOCH,
                    modified = java.time.Instant.EPOCH,
                    correspondentId = null,
                    documentTypeId = null,
                    storagePathId = null,
                    tagIds = listOf(1),
                    archiveSerialNumber = null,
                    originalFileName = null,
                    archivedFileName = null,
                    mimeType = null,
                    pageCount = null,
                    customFields = emptyList(),
                    notes = null,
                    owner = null,
                    userCanChange = true,
                    deletedAt = null,
                    searchHit = null,
                )
            val inboxKey =
                DocumentRepository.listKey(
                    com.naoya0117.paperless.core.model
                        .DocumentQuery(inInbox = true),
                )
            cache.documents.replaceList(account.id, inboxKey, listOf(document), null, java.time.Instant.now())
            offline.set(true)
            Dispatchers.setMain(Dispatchers.Unconfined)

            val inbox = InboxViewModel(DocumentRepository(accounts, cache))

            assertEquals(1, inbox.awaitCount { it > 0 })
        }

    @Test
    fun `clearing the search applies at once`() =
        runTest {
            val viewModel = viewModel(DocumentListMode.ALL)
            viewModel.setSearchText("invoice")
            withContext(
                Dispatchers.Default,
            ) { withTimeout(10_000) { viewModel.activeSearch.first { it == "invoice" } } }

            viewModel.setSearchText("  ")

            assertEquals("", viewModel.activeSearch.value)
        }
}
