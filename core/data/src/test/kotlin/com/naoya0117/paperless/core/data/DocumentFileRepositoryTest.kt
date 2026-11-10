package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.network.DocumentFileKind
import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class DocumentFileRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var accounts: AccountRepository
    private lateinit var files: DocumentFileRepository
    private lateinit var cacheDir: File
    private lateinit var accountId: String

    private val fileRequests = CopyOnWriteArrayList<String>()
    private val failFiles = AtomicBoolean(false)

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    private fun document(
        id: Long,
        modified: Long = 0,
    ) = Document(
        id = id,
        title = "Doc $id",
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

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    if (path == "/api/ui_settings/") {
                        return MockResponse
                            .Builder()
                            .code(200)
                            .addHeader("X-Api-Version", "10")
                            .body("""{"user": {"id": 3, "username": "alice"}, "settings": {}}""")
                            .build()
                    }
                    fileRequests += path
                    if (failFiles.get()) {
                        return MockResponse
                            .Builder()
                            .code(503)
                            .body("{}")
                            .build()
                    }
                    val id = path.split("/")[3]
                    return MockResponse
                        .Builder()
                        .code(200)
                        .addHeader("Content-Type", "image/webp")
                        .addHeader("Content-Disposition", "attachment; filename=\"doc$id.webp\"")
                        .body("x".repeat(100) + id)
                        .build()
                }
            }
        server.start()
        createRepository(maxBytes = 1_000_000)
    }

    /** A fresh repository (with its own account store) over a fresh cache directory. */
    private fun createRepository(maxBytes: Long) {
        accounts =
            AccountRepository(
                AccountStore.create(folder.newFile().apply { delete() }, XorCipher),
                PaperlessClientFactory(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()),
                { error("no client certificates in this test") },
                { files.clear(it) },
            )
        cacheDir = folder.newFolder()
        files = DocumentFileRepository(accounts, DocumentFileStorage(cacheDir, maxBytes))
    }

    @After
    fun tearDown() = server.close()

    private suspend fun signIn() {
        accountId = accounts.signInWithToken(server.url("/").toString(), AuthToken("abc"), tls = AccountTls()).id
    }

    private suspend fun thumbnail(document: Document) =
        withContext(Dispatchers.IO) { files.file(document, DocumentFileKind.THUMBNAIL) }

    @Test
    fun `files are downloaded once and then served from disk`() =
        runTest {
            signIn()

            val first = thumbnail(document(7))
            val second = thumbnail(document(7))

            assertEquals(first, second)
            assertEquals("x".repeat(100) + "7", first.file.readText())
            assertEquals("image/webp", first.contentType)
            assertEquals("doc7.webp", first.fileName)
            assertEquals(1, fileRequests.size)
        }

    @Test
    fun `a modified document is downloaded again and the old file deleted`() =
        runTest {
            signIn()
            val old = thumbnail(document(7, modified = 1))

            val new = thumbnail(document(7, modified = 2))

            assertEquals(2, fileRequests.size)
            assertFalse(old.file.exists())
            assertTrue(new.file.exists())
            assertEquals(2, File(cacheDir, accountId).listFiles()!!.size) // file + metadata
        }

    @Test
    fun `concurrent requests for the same file download it once`() =
        runTest {
            signIn()

            val results =
                (1..5)
                    .map {
                        async(
                            Dispatchers.IO,
                        ) { files.file(document(7), DocumentFileKind.PREVIEW) }
                    }.awaitAll()

            assertEquals(1, fileRequests.size)
            assertEquals(1, results.map { it.file }.distinct().size)
        }

    @Test
    fun `a failed download leaves nothing behind`() =
        runTest {
            signIn()
            failFiles.set(true)

            val result = runCatching { thumbnail(document(7)) }

            assertTrue(result.exceptionOrNull() is PaperlessException)
            assertEquals(emptyList<String>(), File(cacheDir, accountId).list()?.toList().orEmpty())
        }

    @Test
    fun `the least recently used files are dropped beyond the limit`() =
        runTest {
            signIn()
            val sample = thumbnail(document(99)).file
            val entry = sample.length() + File(sample.path + ".meta").length()
            createRepository(maxBytes = entry * 3 + entry / 2) // room for three files with their metadata
            signIn()
            val first = thumbnail(document(1))
            thumbnail(document(2))
            thumbnail(document(3))
            first.file.setLastModified(System.currentTimeMillis() + 10_000) // used most recently

            thumbnail(document(4))

            val left =
                File(
                    cacheDir,
                    accountId,
                ).list()!!.filter { !it.endsWith(".meta") }.map { it.substringBefore('-') }
            assertTrue(left.toString(), "1" in left && "4" in left)
            assertFalse(left.toString(), "2" in left)
        }

    @Test
    fun `signing out deletes the account's files`() =
        runTest {
            signIn()
            val cached = thumbnail(document(7))

            accounts.signOut(accountId)

            assertFalse(cached.file.exists())
            assertTrue(runCatching { thumbnail(document(7)) }.exceptionOrNull() is NotSignedInException)
        }
}
