package com.naoya0117.paperless.feature.labels

import androidx.lifecycle.SavedStateHandle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.data.PaperlessClientFactory
import com.naoya0117.paperless.core.database.OfflineCache
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.MatchingAlgorithm
import com.naoya0117.paperless.core.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LabelViewModelsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val cache = OfflineCache.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())
    private lateinit var accounts: AccountRepository
    private lateinit var repository: LabelRepository

    /** Requests other than GET, as "METHOD path body". */
    private val writes = CopyOnWriteArrayList<String>()

    @Volatile private var tags =
        listOf(
            """{"id": 1, "name": "tax", "color": "#ff0000", "document_count": 3, "user_can_change": true}""",
            """{"id": 2, "name": "Bank", "parent": 1, "user_can_change": true}""",
            """{"id": 3, "name": "Archive", "user_can_change": false}""",
        )

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

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    if (request.method != "GET") writes += "${request.method} $path ${request.body?.utf8().orEmpty()}"
                    return when {
                        path == "/api/ui_settings/" ->
                            json(
                                """{"user": {"id": 3, "username": "alice"}, "settings": {}}""",
                            )
                        request.method == "POST" && path == "/api/tags/" ->
                            if ("Dup" in request.body?.utf8().orEmpty()) {
                                json("""{"name": ["A tag with this name already exists."]}""", 400)
                            } else {
                                json("""{"id": 9, "name": "New", "user_can_change": true}""", 201)
                            }
                        request.method == "PATCH" && path == "/api/tags/1/" ->
                            json("""{"id": 1, "name": "Taxes", "color": "#ff0000", "user_can_change": true}""")
                        request.method == "DELETE" -> MockResponse.Builder().code(204).build()
                        path == "/api/tags/" ->
                            json(
                                """{"count": ${tags.size}, "next": null, "results": [${tags.joinToString()}]}""",
                            )
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
        repository = LabelRepository(accounts, cache)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.close()
    }

    private suspend fun signIn() =
        accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { first(predicate) } }

    private fun listViewModel() =
        LabelListViewModel(repository, SavedStateHandle(mapOf(LabelListViewModel.KIND_ARGUMENT to "tags")))

    private fun editViewModel(id: Long? = null) =
        LabelEditViewModel(
            repository,
            SavedStateHandle(
                buildMap {
                    put(LabelListViewModel.KIND_ARGUMENT, "tags")
                    id?.let { put(LabelEditViewModel.LABEL_ID_ARGUMENT, it) }
                },
            ),
        )

    @Test
    fun `the list is refreshed when opened, sorted by name and searchable`() =
        runTest {
            signIn()
            val viewModel = listViewModel()

            val state = viewModel.state.await { it.items?.size == 3 && !it.refreshing }
            assertEquals(listOf("Archive", "Bank", "tax"), state.items!!.map { it.name })
            assertFalse(state.refreshFailed)

            viewModel.search("AN")
            assertEquals(
                listOf("Bank"),
                viewModel.state
                    .await { it.query == "AN" }
                    .items!!
                    .map { it.name },
            )
        }

    @Test
    fun `a new tag is created with the entered values`() =
        runTest {
            signIn()
            val viewModel = editViewModel()
            assertFalse(viewModel.state.value.canSave)

            viewModel.setName("  New ")
            viewModel.setColor("#00ff00")
            viewModel.setInboxTag(true)
            viewModel.save()

            viewModel.state.await { it.done }
            val post = writes.single()
            assertTrue(post, post.startsWith("POST /api/tags/"))
            assertTrue(
                post,
                "\"name\":\"New\"" in post && "\"color\":\"#00ff00\"" in post && "\"is_inbox_tag\":true" in post,
            )
            assertTrue(
                repository.tags.items
                    .first()
                    .any { it.id == 9L },
            )
        }

    @Test
    fun `an existing tag is edited and only changes are sent`() =
        runTest {
            signIn()
            repository.tags.refresh()
            val viewModel = editViewModel(1)
            val loaded = viewModel.state.await { it.loaded }
            assertEquals("tax", loaded.form.name)
            assertFalse(loaded.changed)

            viewModel.setName("Taxes")
            viewModel.save()

            viewModel.state.await { it.done }
            assertEquals("PATCH /api/tags/1/ {\"name\":\"Taxes\"}", writes.single())
        }

    @Test
    fun `edits are kept on top of a newer version from the server`() =
        runTest {
            signIn()
            repository.tags.refresh()
            // Changed elsewhere: the editor's refresh brings the new color.
            tags = listOf(tags[0].replace("#ff0000", "#0000ff"), tags[1], tags[2])
            val viewModel = editViewModel(1)
            viewModel.state.await { it.loaded }
            viewModel.setName("Taxes")

            val state = viewModel.state.await { it.form.color == "#0000ff" }
            assertEquals("Taxes", state.form.name)
            viewModel.save()
            viewModel.state.await { it.done }
            assertEquals("PATCH /api/tags/1/ {\"name\":\"Taxes\"}", writes.single())
        }

    @Test
    fun `server errors are shown at the field and cleared by editing it`() =
        runTest {
            signIn()
            val viewModel = editViewModel()
            viewModel.setName("Dup")
            viewModel.save()

            val failed = viewModel.state.await { it.error != null }
            assertEquals(LabelError.INVALID, failed.error)
            assertEquals(listOf("A tag with this name already exists."), failed.fieldErrors["name"])

            viewModel.setName("Dup 2")
            assertTrue("name" !in viewModel.state.value.fieldErrors)
            viewModel.setColor("#12")
            assertFalse(viewModel.state.value.canSave)
            viewModel.setColor("#123456")
            assertTrue(viewModel.state.value.canSave)
        }

    @Test
    fun `a tag is deleted, and one the user may not change cannot be edited`() =
        runTest {
            signIn()
            repository.tags.refresh()
            val readOnly = editViewModel(3)
            assertFalse(readOnly.state.await { it.loaded }.editable)
            readOnly.setName("Changed")
            assertEquals("Archive", readOnly.state.value.form.name)

            val viewModel = editViewModel(2)
            viewModel.state.await { it.loaded }
            viewModel.delete()

            viewModel.state.await { it.done }
            assertEquals("DELETE /api/tags/2/ ", writes.single())
            assertTrue(
                repository.tags.items
                    .first()
                    .none { it.id == 2L },
            )
        }

    @Test
    fun `a tag cannot become the child of itself or its descendants`() =
        runTest {
            signIn()
            repository.tags.refresh()
            val viewModel = editViewModel(1)

            val options = viewModel.parentOptions.await { it.isNotEmpty() }

            assertEquals(listOf(3L), options.map(Tag::id))
            assertEquals(1, viewModel.subTagCount.await { it > 0 })
            viewModel.setMatching(Matching(MatchingAlgorithm.REGEX, "^Tax"))
            assertEquals(MatchingAlgorithm.REGEX, viewModel.state.value.form.matching.algorithm)
        }
}
