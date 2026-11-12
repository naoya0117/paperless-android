package com.naoya0117.paperless.feature.uploads

import android.net.Uri
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.PaperlessClientFactory
import com.naoya0117.paperless.core.data.UploadRepository
import com.naoya0117.paperless.core.data.UploadScheduler
import com.naoya0117.paperless.core.data.UploadStorage
import com.naoya0117.paperless.core.database.UploadQueue
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.Upload
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
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UploadsViewModelTest {
    /** Every view model of a test, cleared in tearDown (before Main is reset). */
    private val viewModels = TestViewModels()

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val queue = UploadQueue.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())
    private val scheduled = CopyOnWriteArrayList<String>()
    private lateinit var accounts: AccountRepository
    private lateinit var viewModel: UploadsViewModel
    private val applicationScope = viewModels.applicationScope()

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-Api-Version", "10")
                        .body("""{"user": {"id": 3, "username": "alice"}, "settings": {}}""")
                        .build()
            }
        server.start()
        accounts =
            AccountRepository(
                AccountStore.create(folder.root.resolve("accounts.json"), XorCipher),
                PaperlessClientFactory(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()),
                { error("no client certificates in this test") },
                {},
            )
        val scheduler =
            object : UploadScheduler {
                override fun schedule(
                    uploadId: String,
                    replace: Boolean,
                ) {
                    scheduled += uploadId
                }

                override fun cancel(uploadId: String) = Unit
            }
        val repository = UploadRepository(accounts, queue, scheduler, UploadStorage(folder.newFolder("uploads")))
        viewModel =
            viewModels.create {
                UploadsViewModel(
                    repository,
                    ApplicationProvider.getApplicationContext(),
                    applicationScope,
                )
            }
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

    private suspend fun signIn() =
        accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls())

    private fun file(
        name: String,
        content: String,
    ): Uri = Uri.fromFile(folder.root.resolve(name).apply { writeText(content) })

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean = { true }): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { first(predicate) } }

    @Test
    fun `queues each file once under its name and schedules it`() =
        runTest {
            signIn()
            val invoice = file("invoice.pdf", "%PDF-1.4")

            viewModel.enqueue(listOf(invoice, invoice, file("receipt.png", "png")))

            assertEquals(EnqueueResult.Queued(2), viewModel.enqueueResults.await())
            val uploads: List<Upload> = viewModel.uploads.await { it.size == 2 }
            assertEquals(setOf("invoice.pdf", "receipt.png"), uploads.map { it.fileName }.toSet())
            assertEquals(uploads.map { it.id }.toSet(), scheduled.toSet())
        }

    @Test
    fun `reports files that cannot be read and queues the rest`() =
        runTest {
            signIn()

            viewModel.enqueue(listOf(file("a.pdf", "a"), Uri.fromFile(folder.root.resolve("missing.pdf"))))

            assertEquals(EnqueueResult.PartlyFailed(queued = 1, failed = 1), viewModel.enqueueResults.await())
        }

    @Test
    fun `reports when no file can be read`() =
        runTest {
            signIn()

            viewModel.enqueue(listOf(Uri.fromFile(folder.root.resolve("missing.pdf"))))

            assertEquals(EnqueueResult.Failed(1), viewModel.enqueueResults.await())
        }

    @Test
    fun `reports when no account is signed in`() =
        runTest {
            viewModel.enqueue(listOf(file("a.pdf", "a")))

            assertEquals(EnqueueResult.NotSignedIn, viewModel.enqueueResults.await())
            assertEquals(emptyList<String>(), scheduled)
        }

    @Test
    fun `removes an upload`() =
        runTest {
            signIn()
            viewModel.enqueue(listOf(file("a.pdf", "a")))
            assertEquals(EnqueueResult.Queued(1), viewModel.enqueueResults.await())
            val upload = viewModel.uploads.await { it.isNotEmpty() }.single()

            viewModel.remove(upload.id)

            viewModel.uploads.await { it.isEmpty() }
        }
}
