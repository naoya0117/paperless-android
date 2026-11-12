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
import com.naoya0117.paperless.core.testing.TestViewModels
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DocumentEditViewModelTest {
    /** Every view model of a test, cleared in tearDown (before Main is reset). */
    private val viewModels = TestViewModels()

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val cache = OfflineCache.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())
    private lateinit var accounts: AccountRepository

    /** The response to the next PATCH; by default the patched document. */
    private val patchResponse = AtomicReference<MockResponse?>(null)

    /** The response to GET of the document; by default [documentJson]. */
    private val getResponse = AtomicReference<MockResponse?>(null)
    private val patches = CopyOnWriteArrayList<String>()

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    private fun json(
        body: String,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .addHeader("X-Api-Version", "10")
        .body(body)
        .build()

    private fun documentJson(
        title: String = "Invoice",
        modified: String = "2026-01-01T00:00:00Z",
    ) = """{"id": 1, "title": "$title", "content": "", "created": "2026-01-01", "added": "2026-01-01T00:00:00Z",""" +
        """ "modified": "$modified", "correspondent": 2, "tags": [5], "archive_serial_number": 7,""" +
        """ "custom_fields": [{"field": 9, "value": "kept"}]}"""

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
                        path == "/api/documents/1/" && request.method == "PATCH" -> {
                            patches += request.body?.utf8().orEmpty()
                            patchResponse.getAndSet(null) ?: json(documentJson("Renamed", "2026-02-01T00:00:00Z"))
                        }
                        path == "/api/documents/1/" -> getResponse.get() ?: json(documentJson())
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
        try {
            viewModels.clear()
        } finally {
            Dispatchers.resetMain()
            server.close()
        }
    }

    private val savedState = SavedStateHandle(mapOf(DocumentEditViewModel.DOCUMENT_ID_ARGUMENT to 1L))

    private fun viewModel() =
        viewModels.create {
            DocumentEditViewModel(DocumentRepository(accounts, cache), LabelRepository(accounts, cache), savedState)
        }

    private suspend fun signIn() =
        accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())

    private suspend fun loaded(): DocumentEditViewModel {
        signIn()
        val viewModel = viewModel()
        viewModel.awaitState { it.original != null }
        return viewModel
    }

    private suspend fun DocumentEditViewModel.awaitState(predicate: (EditState) -> Boolean): EditState =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(predicate) } }

    @Test
    fun `the form starts with the document's values and nothing to save`() =
        runTest {
            val state = loaded().state.value

            assertEquals(EditForm("Invoice", LocalDate.of(2026, 1, 1), 2, null, null, listOf(5), "7"), state.form)
            assertFalse(state.changed)
            assertFalse(state.canSave)
        }

    @Test
    fun `saving sends only what changed and closes`() =
        runTest {
            val viewModel = loaded()
            viewModel.setTitle("Renamed")
            viewModel.setTags(listOf(5, 6))
            viewModel.setCorrespondent(null)
            assertTrue(viewModel.state.value.canSave)

            viewModel.save()

            assertTrue(viewModel.awaitState { !it.saving }.saved)
            val body = patches.single()
            assertTrue(body, body.contains("\"title\":\"Renamed\""))
            assertTrue(body, body.contains("\"tags\":[5,6]"))
            assertTrue(body, body.contains("\"correspondent\":null"))
            assertFalse("unchanged values are not sent", body.contains("archive_serial_number"))
            assertFalse("custom fields are left alone", body.contains("custom_fields"))
        }

    @Test
    fun `rejected values are shown by field and the input stays`() =
        runTest {
            val viewModel = loaded()
            patchResponse.set(
                json("""{"archive_serial_number": ["Document with this ASN already exists."]}""", code = 400),
            )
            viewModel.setArchiveSerialNumber("8")

            viewModel.save()

            val state = viewModel.awaitState { !it.saving }
            assertEquals(EditError.INVALID, state.error)
            assertEquals(
                listOf("Document with this ASN already exists."),
                state.form.fieldErrors["archive_serial_number"],
            )
            assertEquals("8", state.form.archiveSerialNumber)
            assertFalse(state.saved)
        }

    @Test
    fun `a server failure keeps the input for another try`() =
        runTest {
            val viewModel = loaded()
            patchResponse.set(
                MockResponse
                    .Builder()
                    .code(503)
                    .body("{}")
                    .build(),
            )
            viewModel.setTitle("Renamed")

            viewModel.save()

            val state = viewModel.awaitState { !it.saving }
            assertEquals(EditError.SERVER, state.error)
            assertEquals("Renamed", state.form.title)
            assertTrue(state.canSave)
        }

    @Test
    fun `an empty title or a cleared date cannot be saved, the ASN takes digits only`() =
        runTest {
            val viewModel = loaded()

            viewModel.setTitle(" ")
            assertFalse(viewModel.state.value.canSave)
            viewModel.setTitle("Invoice 2")
            viewModel.setArchiveSerialNumber("12a3")

            assertEquals("123", viewModel.state.value.form.archiveSerialNumber)
            assertTrue(viewModel.state.value.canSave)
        }

    @Test
    fun `an offline copy changed on the server meanwhile is not saved over`() =
        runTest {
            val account = signIn()
            // Cached from a list, then the server cannot be reached while opening the editor.
            val cached = DocumentRepository(accounts, cache).fetch(1)
            cache.documents.replaceList(account.id, "list", listOf(cached), null, Instant.now())
            getResponse.set(
                MockResponse
                    .Builder()
                    .code(503)
                    .body("{}")
                    .build(),
            )
            val viewModel = viewModel()
            assertFalse(viewModel.awaitState { it.original != null }.originalFromServer)
            viewModel.setTitle("Mine")
            // Back online; someone else edited the document.
            getResponse.set(json(documentJson("Theirs", "2026-03-01T00:00:00Z")))

            viewModel.save()

            val state = viewModel.awaitState { !it.saving }
            assertEquals(EditError.CONFLICT, state.error)
            assertEquals(emptyList<String>(), patches)
            assertEquals("Theirs", state.original?.title)
            assertEquals("Mine", state.form.title)
        }

    @Test
    fun `a deleted document cannot be edited from the cache`() =
        runTest {
            signIn()
            getResponse.set(json("""{"detail": "No Document matches the given query."}""", code = 404))

            assertEquals(EditLoadError.NOT_FOUND, viewModel().awaitState { it.loadError != null }.loadError)
        }

    @Test
    fun `editing a field clears its server error`() =
        runTest {
            val viewModel = loaded()
            patchResponse.set(json("""{"title": ["Too long."], "tags": ["Invalid pk."]}""", code = 400))
            viewModel.setTitle("Renamed")
            viewModel.save()
            viewModel.awaitState { !it.saving }

            viewModel.setTitle("Shorter")

            assertEquals(setOf("tags"), viewModel.state.value.form.fieldErrors.keys)
        }

    @Test
    fun `edits survive process death`() =
        runTest {
            val viewModel = loaded()
            viewModel.setTitle("Unsaved")
            viewModel.setTags(listOf(5, 6))

            val restored = viewModel().awaitState { it.original != null }

            assertEquals("Unsaved", restored.form.title)
            assertEquals(listOf(5L, 6L), restored.form.tagIds)
        }

    @Test
    fun `the same ASN written differently is no change`() =
        runTest {
            val viewModel = loaded()

            viewModel.setArchiveSerialNumber("007")

            assertFalse(viewModel.state.value.changed)
        }
}
