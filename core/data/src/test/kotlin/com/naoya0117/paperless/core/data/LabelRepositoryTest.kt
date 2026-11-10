package com.naoya0117.paperless.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.database.OfflineCache
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.CorrespondentDraft
import com.naoya0117.paperless.core.model.TagDraft
import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class LabelRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val cache = OfflineCache.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())
    private lateinit var accounts: AccountRepository
    private lateinit var labels: LabelRepository

    /** Server state per token, i.e. per user: path -> JSON objects of the list. */
    private val lists = ConcurrentHashMap<String, MutableMap<String, List<String>>>()

    /** Responses for writes, by method and path; served once. */
    private val writes = ConcurrentHashMap<String, MockResponse>()

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

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val token = request.headers["Authorization"].orEmpty().removePrefix("Token ")
                    val path = request.url.encodedPath
                    writes.remove("${request.method} $path")?.let { return it }
                    if (path == "/api/ui_settings/") {
                        val userId = if (token == "bob") 4 else 3
                        return json(200, """{"user": {"id": $userId, "username": "$token"}, "settings": {}}""")
                    }
                    val items = lists[token]?.get(path) ?: emptyList()
                    return json(200, """{"count": ${items.size}, "next": null, "results": [${items.joinToString()}]}""")
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
        labels = LabelRepository(accounts, cache)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun serverState(
        token: String,
        vararg entries: Pair<String, List<String>>,
    ) {
        lists[token] = mutableMapOf(*entries)
    }

    private fun named(
        id: Long,
        name: String,
    ) = """{"id": $id, "name": "$name"}"""

    private suspend fun signIn(token: String) =
        accounts.signInWithToken(server.url("/").toString(), AuthToken(token), tls = AccountTls())

    @Test
    fun `refreshing fills the cache of the active account`() =
        runTest {
            serverState(
                "alice",
                "/api/tags/" to listOf(named(1, "Tax"), named(2, "Finance")),
                "/api/correspondents/" to listOf(named(5, "ACME")),
                "/api/storage_paths/" to listOf("""{"id": 6, "name": "Archive", "path": "{{ title }}"}"""),
            )
            signIn("alice")

            labels.refreshAll()

            assertEquals(
                listOf("Finance", "Tax"),
                labels.tags.items
                    .first()
                    .map { it.name },
            )
            assertEquals(
                listOf("ACME"),
                labels.correspondents.items
                    .first()
                    .map { it.name },
            )
            assertEquals(
                listOf("{{ title }}"),
                labels.storagePaths.items
                    .first()
                    .map { it.path },
            )
            assertEquals(emptyList<Any>(), labels.documentTypes.items.first())
        }

    @Test
    fun `refreshing drops what the server deleted`() =
        runTest {
            serverState("alice", "/api/correspondents/" to listOf(named(1, "One"), named(2, "Two")))
            signIn("alice")
            labels.correspondents.refresh()

            serverState("alice", "/api/correspondents/" to listOf(named(2, "Two")))
            labels.correspondents.refresh()

            assertEquals(
                listOf(2L),
                labels.correspondents.items
                    .first()
                    .map { it.id },
            )
        }

    @Test
    fun `a failed refresh keeps the cache`() =
        runTest {
            serverState("alice", "/api/correspondents/" to listOf(named(1, "One")))
            signIn("alice")
            labels.correspondents.refresh()
            writes["GET /api/correspondents/"] = json(500, "{}")

            val result = runCatching { labels.correspondents.refresh() }

            assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is PaperlessException)
            assertEquals(
                listOf("One"),
                labels.correspondents.items
                    .first()
                    .map { it.name },
            )
        }

    @Test
    fun `writes reach the cache only when they succeed`() =
        runTest {
            signIn("alice")
            writes["POST /api/correspondents/"] = json(201, named(7, "New"))

            val created = labels.correspondents.create(CorrespondentDraft("New"))
            assertEquals(listOf(created), labels.correspondents.items.first())

            writes["DELETE /api/correspondents/7/"] = json(403, """{"detail": "no"}""")
            assertTrue(runCatching { labels.correspondents.delete(7) }.isFailure)
            assertEquals(listOf(created), labels.correspondents.items.first())

            writes["DELETE /api/correspondents/7/"] = MockResponse.Builder().code(204).build()
            labels.correspondents.delete(7)
            assertEquals(emptyList<Any>(), labels.correspondents.items.first())
        }

    @Test
    fun `tag writes reload all tags`() =
        runTest {
            signIn("alice")
            writes["POST /api/tags/"] = json(201, """{"id": 2, "name": "Child", "parent": 1}""")
            // The server also updated the parent's children.
            serverState(
                "alice",
                "/api/tags/" to
                    listOf(
                        """{"id": 1, "name": "Parent", "children": [{"id": 2, "name": "Child"}]}""",
                        """{"id": 2, "name": "Child", "parent": 1}""",
                    ),
            )

            labels.tags.create(TagDraft("Child", parentId = 1))

            assertEquals(
                listOf(listOf(), listOf(2L)),
                labels.tags.items
                    .first()
                    .map { it.childIds },
            )
        }

    @Test
    fun `items follow the active account and sign-out deletes its cache`() =
        runTest {
            serverState("alice", "/api/tags/" to listOf(named(1, "Alice's")))
            serverState("bob", "/api/tags/" to listOf(named(1, "Bob's")))
            val alice = signIn("alice")
            labels.tags.refresh()
            val bob = signIn("bob")
            labels.tags.refresh()

            assertEquals(
                listOf("Bob's"),
                labels.tags.items
                    .first()
                    .map { it.name },
            )
            accounts.switchTo(alice.id)
            assertEquals(
                listOf("Alice's"),
                labels.tags.items
                    .first()
                    .map { it.name },
            )

            accounts.signOut(bob.id)
            accounts.signOut(alice.id)
            assertEquals(emptyList<Any>(), cache.tags.observe(bob.id).first())
            assertEquals(emptyList<Any>(), cache.tags.observe(alice.id).first())
            assertEquals(emptyList<Any>(), labels.tags.items.first())
        }

    @Test
    fun `without an account nothing can be refreshed`() =
        runTest {
            assertTrue(runCatching { labels.refreshAll() }.exceptionOrNull() is NotSignedInException)
            assertEquals(emptyList<Any>(), labels.tags.items.first())
        }
}
