package com.naoya0117.paperless.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.database.UploadQueue
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.DocumentUpload
import com.naoya0117.paperless.core.model.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
class UploadRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val queue = UploadQueue.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())
    private lateinit var accounts: AccountRepository
    private lateinit var uploads: UploadRepository
    private lateinit var processor: UploadProcessor
    private lateinit var storageDir: File

    private val scheduled = CopyOnWriteArrayList<String>()
    private val cancelled = CopyOnWriteArrayList<String>()

    /** Responses by "METHOD path", served once each; requests without one get the defaults below. */
    private val responses = ConcurrentHashMap<String, MutableList<MockResponse>>()
    private val uploadBodies = CopyOnWriteArrayList<String>()

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

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

    private fun respond(
        key: String,
        vararg response: MockResponse,
    ) {
        responses.getOrPut(key) { mutableListOf() } += response
    }

    private fun task(
        status: String,
        result: String,
    ) = json(
        200,
        """{"count": 1, "next": null, "results": [{"id": 1, "task_id": "task-1", "task_type": "consume_file",""" +
            """ "status": "$status", "result_data": $result}]}""",
    )

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    if (path == "/api/documents/post_document/") uploadBodies += request.body?.utf8().orEmpty()
                    synchronized(responses) {
                        responses["${request.method} $path"]?.let { if (it.isNotEmpty()) return it.removeAt(0) }
                    }
                    return when (path) {
                        "/api/ui_settings/" -> json(200, """{"user": {"id": 3, "username": "alice"}, "settings": {}}""")
                        "/api/documents/post_document/" -> json(200, "\"task-1\"")
                        "/api/tasks/" -> json(200, """{"count": 0, "next": null, "results": []}""")
                        else -> json(404, """{"detail": "Not found."}""")
                    }
                }
            }
        server.start()
        val cache =
            com.naoya0117.paperless.core.database.OfflineCache
                .inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())
        accounts =
            AccountRepository(
                AccountStore.create(folder.root.resolve("accounts.json"), XorCipher),
                PaperlessClientFactory(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()),
                { error("no client certificates in this test") },
                { accountId ->
                    uploads.clear(accountId)
                    cache.clear(accountId)
                },
            )
        storageDir = folder.newFolder("uploads")
        val scheduler =
            object : UploadScheduler {
                override fun schedule(uploadId: String) {
                    scheduled += uploadId
                }

                override fun cancel(uploadId: String) {
                    cancelled += uploadId
                }
            }
        uploads = UploadRepository(accounts, queue, scheduler, UploadStorage(storageDir))
        processor = UploadProcessor(accounts, queue, processingWait = 300.milliseconds)
    }

    @After
    fun tearDown() = server.close()

    private suspend fun signIn() =
        accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls()).id

    /** In real time: the processor's timeouts must not run on the test's virtual clock. */
    private suspend fun process(id: String) = withContext(Dispatchers.IO) { processor.process(id) }

    private suspend fun enqueue(content: String = "%PDF-1.7 hello") =
        uploads.enqueue(
            UploadSource("scan.pdf", "application/pdf") {
                content.byteInputStream()
            },
            DocumentUpload(title = "Scan"),
        )

    @Test
    fun `queued files are copied, stored and scheduled`() =
        runTest {
            val accountId = signIn()

            val upload = enqueue()

            assertEquals(UploadState.QUEUED, upload.state)
            assertEquals(14L, upload.size)
            assertEquals(listOf(upload.id), scheduled)
            assertEquals(listOf(upload), uploads.uploads.first())
            assertEquals(accountId, upload.accountId)
            assertEquals("%PDF-1.7 hello", File(storageDir, upload.id).readText())
        }

    @Test
    fun `an upload is sent and followed until the document exists`() =
        runTest {
            signIn()
            val upload = enqueue()
            respond("GET /api/tasks/", task("success", """{"document_id": 12}"""))

            assertEquals(UploadProcessor.Outcome.FINISHED, process(upload.id))

            val done = uploads.uploads.first().single()
            assertEquals(UploadState.DONE, done.state)
            assertEquals(12L, done.documentId)
            assertEquals("task-1", done.taskId)
            assertEquals(14L, done.sentBytes)
            assertTrue(uploadBodies.single().contains("%PDF-1.7 hello"))
            assertTrue(uploadBodies.single().contains("Scan"))
            assertFalse("the sent copy is deleted", File(storageDir, upload.id).exists())
        }

    @Test
    fun `slow processing is checked again later without sending twice`() =
        runTest {
            signIn()
            val upload = enqueue()

            assertEquals(UploadProcessor.Outcome.RETRY, process(upload.id))
            assertEquals(UploadState.PROCESSING, queue.get(upload.id)?.state)

            respond("GET /api/tasks/", task("success", """{"document_id": 12}"""))
            assertEquals(UploadProcessor.Outcome.FINISHED, process(upload.id))

            assertEquals(UploadState.DONE, queue.get(upload.id)?.state)
            assertEquals(1, uploadBodies.size)
        }

    @Test
    fun `network failures are retried, rejected files fail with the reason`() =
        runTest {
            signIn()
            val upload = enqueue()
            respond("POST /api/documents/post_document/", json(503, """{"detail": "unavailable"}"""))

            assertEquals(UploadProcessor.Outcome.RETRY, process(upload.id))
            assertEquals(UploadState.QUEUED, queue.get(upload.id)?.state)

            respond(
                "POST /api/documents/post_document/",
                json(400, """{"document": ["File type text/plain not supported"]}"""),
            )
            assertEquals(UploadProcessor.Outcome.FINISHED, process(upload.id))
            val failed = queue.get(upload.id)!!
            assertEquals(UploadState.FAILED, failed.state)
            assertTrue(failed.error.orEmpty(), failed.error.orEmpty().isNotEmpty())

            scheduled.clear()
            assertTrue(uploads.retry(upload.id))
            assertEquals(UploadState.QUEUED, queue.get(upload.id)?.state)
            assertEquals(listOf(upload.id), scheduled)
        }

    @Test
    fun `duplicates and processing errors are recorded`() =
        runTest {
            signIn()
            val upload = enqueue()
            respond(
                "GET /api/tasks/",
                task(
                    "failure",
                    """{"error_message": "Not consuming scan.pdf: It is a duplicate.", "duplicate_of": 7}""",
                ),
            )

            process(upload.id)

            val failed = queue.get(upload.id)!!
            assertEquals(UploadState.FAILED, failed.state)
            assertEquals(7L, failed.duplicateOfDocumentId)
            assertEquals("Not consuming scan.pdf: It is a duplicate.", failed.error)
            assertTrue("kept for a retry", File(storageDir, upload.id).exists())
        }

    @Test
    fun `removing cancels the work and deletes the copy`() =
        runTest {
            signIn()
            val upload = enqueue()

            uploads.remove(upload.id)

            assertEquals(listOf(upload.id), cancelled)
            assertNull(queue.get(upload.id))
            assertFalse(File(storageDir, upload.id).exists())
            assertEquals(UploadProcessor.Outcome.FINISHED, process(upload.id))
        }

    @Test
    fun `signing out removes the account's uploads`() =
        runTest {
            val accountId = signIn()
            val upload = enqueue()

            accounts.signOut(accountId)

            assertNull(queue.get(upload.id))
            assertEquals(listOf(upload.id), cancelled)
            assertFalse(File(storageDir, upload.id).exists())
        }

    @Test
    fun `nothing is queued without an account or when the file cannot be read`() =
        runTest {
            assertTrue(runCatching { enqueue() }.exceptionOrNull() is NotSignedInException)

            signIn()
            val unreadable = runCatching { uploads.enqueue(UploadSource("x.pdf", null) { throw IOException("gone") }) }

            assertTrue(unreadable.exceptionOrNull() is IOException)
            assertEquals(emptyList<Any>(), uploads.uploads.first())
            assertEquals(emptyList<String>(), storageDir.list()!!.toList())
        }
}
