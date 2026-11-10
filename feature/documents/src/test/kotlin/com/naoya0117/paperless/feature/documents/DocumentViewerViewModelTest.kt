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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DocumentViewerViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val cache = OfflineCache.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())
    private lateinit var accounts: AccountRepository
    private val offline = AtomicBoolean(false)
    private val deleted = AtomicBoolean(false)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var previewType = "image/png"

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

    private val documentJson =
        """{"id": 1, "title": "Invoice", "content": "full text", "created": "2026-01-01",""" +
            """ "added": "2026-01-01T00:00:00Z", "modified": "2026-01-01T00:00:00Z", "original_file_name": "scan.docx", "notes": []}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path == "/api/ui_settings/" ->
                            json(
                                """{"user": {"id": 3, "username": "alice"}, "settings": {}}""",
                            )
                        offline.get() ->
                            MockResponse
                                .Builder()
                                .code(503)
                                .body("{}")
                                .build()
                        path == "/api/documents/1/notes/" && request.method == "POST" ->
                            json(
                                """[{"id": 4, "note": "Paid", "created": "2026-02-01T10:00:00Z", "user": {"id": 3, "username": "alice"}}]""",
                            )
                        path == "/api/documents/1/notes/" && request.method == "DELETE" -> json("[]")
                        path == "/api/documents/1/" && request.method == "DELETE" -> {
                            deleted.set(true)
                            MockResponse.Builder().code(204).build()
                        }
                        path == "/api/documents/1/" -> json(documentJson)
                        path == "/api/documents/1/preview/" ->
                            MockResponse
                                .Builder()
                                .code(200)
                                .addHeader("Content-Type", previewType)
                                .addHeader("Content-Disposition", "inline; filename=\"scan.docx\"")
                                .body("not really an image")
                                .build()
                        else -> json("""{"count": 0, "next": null, "results": []}""")
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
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
        Dispatchers.resetMain()
        server.close()
    }

    private val filesDir by lazy { folder.newFolder() }

    private fun viewModel() =
        DocumentViewerViewModel(
            DocumentRepository(accounts, cache),
            DocumentFileRepository(accounts, DocumentFileStorage(filesDir)),
            LabelRepository(accounts, cache),
            SavedStateHandle(mapOf(DocumentViewerViewModel.DOCUMENT_ID_ARGUMENT to 1L)),
            ApplicationProvider.getApplicationContext(),
            applicationScope,
        )

    private suspend fun DocumentViewerViewModel.awaitState(predicate: (ViewerState) -> Boolean): ViewerState =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(predicate) } }

    private suspend fun signIn() =
        accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())

    @Test
    fun `an image document is shown with its details`() =
        runTest {
            signIn()

            val state = viewModel().awaitState { it.content !is ViewerContent.Loading && it.document != null }

            assertTrue(state.content.toString(), state.content is ViewerContent.Image)
            assertEquals("Invoice", state.document?.title)
        }

    @Test
    fun `other file types are reported as unsupported`() =
        runTest {
            signIn()
            previewType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

            val state = viewModel().awaitState { it.content !is ViewerContent.Loading }

            assertEquals(ViewerContent.Unsupported("scan.docx"), state.content)
        }

    @Test
    fun `a listed document opened before can be shown offline`() =
        runTest {
            val account = signIn()
            val document = viewModel().awaitState { it.content is ViewerContent.Image }.document!!
            // As if it had been listed (documents are opened from lists, which cache them).
            cache.documents.replaceList(account.id, "list", listOf(document), null, Instant.now())
            offline.set(true)

            val state = viewModel().awaitState { it.content !is ViewerContent.Loading }

            assertTrue(state.content.toString(), state.content is ViewerContent.Image)
        }

    @Test
    fun `a document neither cached nor reachable fails and can be retried`() =
        runTest {
            signIn()
            offline.set(true)
            val viewModel = viewModel()

            assertEquals(null, viewModel.awaitState { it.content is ViewerContent.Failed }.document)

            offline.set(false)
            viewModel.retry()
            assertTrue(viewModel.awaitState { it.content is ViewerContent.Image }.document != null)
        }

    private suspend fun DocumentViewerViewModel.nextEvent(): ViewerEvent =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { events.first() } }

    // The only test handing out a content URI: FileProvider keeps its paths per authority for the whole process,
    // so a second test (with another temporary cache directory) would be refused.
    @Test
    fun `sharing hands a copy of the file under its server name to other apps`() =
        runTest {
            signIn()
            val viewModel = viewModel()
            viewModel.awaitState { it.document?.content == "full text" }

            viewModel.share()

            val event = viewModel.nextEvent() as ViewerEvent.SendFile
            assertEquals("image/png", event.mimeType)
            assertFalse(event.open)
            assertEquals("content", event.uri.scheme)
            assertEquals("scan.docx", event.uri.lastPathSegment)
            val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
            assertEquals(
                "not really an image",
                resolver.openInputStream(event.uri)!!.use { it.readBytes().decodeToString() },
            )
            // The state is only up to date while collected.
            viewModel.awaitState { !it.busy }
        }

    @Test
    fun `a failed deletion is reported`() =
        runTest {
            signIn()
            val viewModel = viewModel()
            viewModel.awaitState { it.document != null }
            offline.set(true)

            viewModel.delete()

            assertEquals(ViewerEvent.Failed(FileAction.DELETE, FailureReason.SERVER), viewModel.nextEvent())
            assertFalse(deleted.get())
        }

    @Test
    fun `deleting moves the document to the trash`() =
        runTest {
            signIn()
            val viewModel = viewModel()
            viewModel.awaitState { it.document != null }

            viewModel.delete()

            assertEquals(ViewerEvent.Deleted, viewModel.nextEvent())
            assertTrue(deleted.get())
        }

    @Test
    fun `file names are made safe for file systems`() {
        assertEquals("a_b_c.pdf", DocumentFileExport.safeFileName("a/b:c.pdf"))
        assertEquals("document", DocumentFileExport.safeFileName(" .. "))
        // At most 200 bytes (UTF-8), keeping the extension.
        val long = DocumentFileExport.safeFileName("請求書".repeat(40) + ".pdf")
        assertTrue(long.toByteArray().size <= 200)
        assertTrue(long.endsWith(".pdf") && long.startsWith("請求書"))
        assertEquals("Invoice.pdf", DocumentFileExport.fileName(null, "Invoice", "application/pdf"))
        assertEquals("scan.png", DocumentFileExport.fileName("scan.png", "Invoice", "application/pdf"))
    }

    @Test
    fun `tapping toggles the details`() {
        val viewModel = viewModel()

        viewModel.toggleInfo()
        assertTrue(viewModel.infoVisible.value)
        viewModel.toggleInfo()
        assertEquals(false, viewModel.infoVisible.value)
    }

    @Test
    fun `the newer of the cached and the fetched document is shown`() {
        val fetched = document(modified = 10, title = "Fetched")
        val cached = document(modified = 20, title = "Edited")

        assertEquals("Edited", DocumentViewerViewModel.newer(cached, fetched)?.title)
        assertEquals(
            "Fetched",
            DocumentViewerViewModel.newer(cached.copy(modified = Instant.ofEpochMilli(10)), fetched)?.title,
        )
        assertEquals("Fetched", DocumentViewerViewModel.newer(null, fetched)?.title)
        assertEquals("Edited", DocumentViewerViewModel.newer(cached, null)?.title)
    }

    private fun document(
        modified: Long,
        title: String,
    ) = Document(
        id = 1,
        title = title,
        content = "",
        created = LocalDate.of(2026, 1, 1),
        added = Instant.EPOCH,
        modified = Instant.ofEpochMilli(modified),
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

    @Test
    fun `notes are added and deleted`() =
        runTest {
            signIn()
            val viewModel = viewModel()
            viewModel.awaitState { it.document?.notes != null }

            assertFalse(viewModel.addNote("   "))
            assertTrue(viewModel.addNote(" Paid "))

            assertEquals(ViewerEvent.NoteAdded("Paid"), viewModel.nextEvent())
            val note =
                viewModel
                    .awaitState { it.document?.notes?.isNotEmpty() == true }
                    .document!!
                    .notes!!
                    .single()
            assertEquals("Paid", note.text)
            assertEquals("alice", note.user?.username)

            viewModel.deleteNote(note.id)
            viewModel.awaitState { it.document?.notes?.isEmpty() == true }
        }

    @Test
    fun `money is shown in its currency`() {
        val default = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            assertEquals("€120.50", formatMoney("EUR120.50", null))
            assertEquals("$3.00", formatMoney("3", "USD"))
            assertEquals("12.30", formatMoney("12.3", null))
        } finally {
            java.util.Locale.setDefault(default)
        }
    }
}
