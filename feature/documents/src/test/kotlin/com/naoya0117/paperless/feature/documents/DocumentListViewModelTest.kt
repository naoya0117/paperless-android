package com.naoya0117.paperless.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.DocumentFileRepository
import com.naoya0117.paperless.core.data.DocumentFileStorage
import com.naoya0117.paperless.core.data.DocumentRepository
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.data.PaperlessClientFactory
import com.naoya0117.paperless.core.database.OfflineCache
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.Document
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.Proxy
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DocumentListViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
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
                        "/api/documents/1/thumb/" ->
                            MockResponse
                                .Builder()
                                .code(503)
                                .body("{}")
                                .build()
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
            DocumentFileRepository(accounts, DocumentFileStorage(folder.newFolder())),
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
    fun `a thumbnail that cannot be loaded is null`() =
        runTest {
            accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())
            val document =
                Document(
                    id = 1,
                    title = "Doc",
                    content = "",
                    created = LocalDate.of(2026, 1, 1),
                    added = Instant.EPOCH,
                    modified = Instant.EPOCH,
                    correspondentId = null,
                    documentTypeId = null,
                    storagePathId = null,
                    tagIds = emptyList(),
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

            assertNull(withContext(Dispatchers.IO) { viewModel(DocumentListMode.ALL).thumbnail(document) })
        }

    @Test
    fun `the search text is kept across process death`() {
        val savedState = SavedStateHandle(mapOf(DocumentListViewModel.MODE_ARGUMENT to DocumentListMode.SEARCH.name))
        viewModel(savedState).setSearchText("invoice")

        assertEquals("invoice", viewModel(savedState).searchText.value)
    }
}
