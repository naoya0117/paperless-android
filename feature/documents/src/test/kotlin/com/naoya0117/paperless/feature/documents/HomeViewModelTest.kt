package com.naoya0117.paperless.feature.documents

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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    /** Every view model of a test, cleared in tearDown (before Main is reset). */
    private val viewModels = TestViewModels()

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val cache = OfflineCache.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())
    private lateinit var accounts: AccountRepository
    private val offline = AtomicBoolean(false)

    /** Signing in is allowed even "offline" (it is before). */
    private var signedIn = false

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    private fun json(body: String) =
        MockResponse
            .Builder()
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
                    if (offline.get() && signedIn) {
                        return MockResponse
                            .Builder()
                            .code(503)
                            .body("{}")
                            .build()
                    }
                    return when (request.url.encodedPath) {
                        "/api/ui_settings/" ->
                            json(
                                """{"user": {"id": 3, "username": "alice"}, "settings": {"saved_views": """ +
                                    """{"dashboard_views_visible_ids": [8, 7], "sidebar_views_visible_ids": []}}}""",
                            )
                        "/api/saved_views/" ->
                            json(
                                """{"count": 3, "next": null, "results": [""" +
                                    """{"id": 7, "name": "Seven", "filter_rules": []},""" +
                                    """{"id": 8, "name": "Eight", "filter_rules": []},""" +
                                    """{"id": 9, "name": "Nine", "filter_rules": []}]}""",
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

    private fun viewModel() =
        viewModels.create {
            HomeViewModel(
                DocumentRepository(accounts, cache),
                LabelRepository(accounts, cache),
                accounts,
                ApplicationProvider.getApplicationContext(),
            )
        }

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { first(predicate) } }

    private suspend fun signIn() {
        accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())
        signedIn = true
    }

    @Test
    fun `the dashboard's views are shown in its order`() =
        runTest {
            signIn()
            val viewModel = viewModel()

            val state = viewModel.state.await { it.views?.isNotEmpty() == true && !it.refreshing }

            assertEquals(listOf("Eight", "Seven"), state.views!!.map { it.name })
        }

    @Test
    fun `offline the dashboard as known before is shown`() =
        runTest {
            signIn()
            viewModel().state.await { it.views?.size == 2 && !it.refreshing }
            offline.set(true)

            val state = viewModel().state.await { it.views?.size == 2 }

            assertEquals(listOf(8L, 7L), state.views!!.map { it.id })
        }
}
