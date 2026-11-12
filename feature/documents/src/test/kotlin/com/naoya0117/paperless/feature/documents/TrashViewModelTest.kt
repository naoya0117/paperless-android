package com.naoya0117.paperless.feature.documents

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.PaperlessClientFactory
import com.naoya0117.paperless.core.data.TrashRepository
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.testing.TestViewModels
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
class TrashViewModelTest {
    /** Every view model of a test, cleared in tearDown (before Main is reset). */
    private val viewModels = TestViewModels()

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var accounts: AccountRepository
    private val actions = CopyOnWriteArrayList<String>()

    /** IDs in the trash on the server. */
    @Volatile private var inTrash = listOf(1L, 2L)

    @Volatile private var refuse = false

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

    private fun document(id: Long) =
        """{"id": $id, "title": "Doc $id", "created": "2026-01-01", "added": "2026-01-01T00:00:00Z",""" +
            """ "modified": "2026-01-01T00:00:00Z", "deleted_at": "2026-03-0${id}T00:00:00Z"}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.url.encodedPath == "/api/ui_settings/" ->
                            json("""{"user": {"id": 3, "username": "alice"}, "settings": {}}""")
                        request.method == "POST" -> {
                            val body = request.body?.utf8().orEmpty()
                            actions += body
                            if (refuse) {
                                json("""{"detail": "no"}""", 403)
                            } else {
                                val ids =
                                    if ("documents" in
                                        body
                                    ) {
                                        listOf(Regex("""\[(\d+)]""").find(body)!!.groupValues[1].toLong())
                                    } else {
                                        inTrash
                                    }
                                inTrash = inTrash - ids.toSet()
                                json(
                                    """{"result": "OK", "doc_ids": ${ids.joinToString(prefix = "[", postfix = "]")}}""",
                                )
                            }
                        }
                        else ->
                            json(
                                """{"count": ${inTrash.size}, "next": null, "results": [${inTrash.joinToString {
                                    document(
                                        it,
                                    )
                                }}]}""",
                            )
                    }
            }
        server.start()
        accounts =
            AccountRepository(
                AccountStore.create(folder.root.resolve("accounts.json"), XorCipher),
                PaperlessClientFactory(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()),
                { error("no client certificates in this test") },
                {},
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

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { first(predicate) } }

    private suspend fun viewModel(): TrashViewModel {
        accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())
        return viewModels.create { TrashViewModel(TrashRepository(accounts)) }
    }

    @Test
    fun `the trash is listed, most recently deleted first`() =
        runTest {
            val viewModel = viewModel()

            val state = viewModel.state.await { it.documents != null && !it.loading }

            assertEquals(listOf(2L, 1L), state.documents!!.map { it.id })
        }

    @Test
    fun `documents are restored, deleted, and the trash emptied`() =
        runTest {
            val viewModel = viewModel()
            viewModel.state.await { it.documents?.size == 2 && !it.loading }

            viewModel.restore(1)
            assertEquals(TrashEvent.Done(TrashAction.RESTORE, 1), viewModel.events.await { true })
            assertEquals(
                listOf(2L),
                viewModel.state
                    .await { it.documents?.size == 1 }
                    .documents!!
                    .map { it.id },
            )

            inTrash = listOf(2L, 3L)
            viewModel.delete(2)
            assertEquals(TrashEvent.Done(TrashAction.DELETE, 1), viewModel.events.await { true })
            viewModel.state.await { it.documents?.map { d -> d.id } == listOf(3L) && !it.loading }
            viewModel.empty()
            assertEquals(TrashEvent.Done(TrashAction.EMPTY, 1), viewModel.events.await { true })
            viewModel.state.await { it.documents?.isEmpty() == true && !it.busy }

            assertEquals(
                listOf(
                    """{"action":"restore","documents":[1]}""",
                    """{"action":"empty","documents":[2]}""",
                    """{"action":"empty"}""",
                ),
                actions.toList(),
            )
        }

    @Test
    fun `a refusal is reported as such`() =
        runTest {
            val viewModel = viewModel()
            viewModel.state.await { it.documents != null && !it.loading }
            refuse = true

            viewModel.restore(1)

            assertEquals(TrashEvent.Failed(TrashAction.RESTORE, TrashFailure.REFUSED), viewModel.events.await { true })
        }

    @Test
    fun `zoom stays within 1x to 4x and never shows space beside the content`() {
        val zoom = ZoomState()
        val size = IntSize(1000, 2000)

        zoom.transform(2f, Offset.Zero, Offset(500f, 1000f), size)
        assertEquals(2f, zoom.scale)
        assertEquals(Offset(-500f, -1000f), zoom.offset)

        zoom.transform(1f, Offset(5000f, 0f), Offset.Zero, size)
        assertEquals(0f, zoom.offset.x)

        zoom.transform(10f, Offset.Zero, Offset.Zero, size)
        assertEquals(ZoomState.MAX_SCALE, zoom.scale)

        zoom.transform(0.1f, Offset.Zero, Offset.Zero, size)
        assertEquals(1f, zoom.scale)
        // A slow pinch (small steps) still zooms in; snapping back only happens when the gesture ends.
        repeat(10) { zoom.transform(1.01f, Offset.Zero, Offset.Zero, size) }
        assertTrue(zoom.scale > 1.05f)
        zoom.reset()
        zoom.transform(1.01f, Offset.Zero, Offset.Zero, size)
        zoom.settle()
        assertEquals(1f, zoom.scale)
        assertTrue(zoom.offset == Offset.Zero)
    }
}
