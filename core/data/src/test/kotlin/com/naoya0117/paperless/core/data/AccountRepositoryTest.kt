package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.Proxy

class AccountRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var repository: AccountRepository
    private val clearedAccounts = mutableListOf<String>()

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    @Before
    fun setUp() {
        server.start()
        val store = AccountStore.create(folder.root.resolve("accounts.json"), XorCipher)
        repository =
            AccountRepository(
                store,
                PaperlessClientFactory(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()),
                { error("no client certificates in this test") },
                { clearedAccounts += it },
            )
    }

    @After
    fun tearDown() = server.close()

    private fun serverUrl() = server.url("/paperless/").toString()

    private fun tokenResponse(token: String) =
        MockResponse
            .Builder()
            .code(200)
            .body("""{"token": "$token"}""")
            .build()

    private fun uiSettings(
        userId: Long = 3,
        lastName: String = "",
    ) = MockResponse
        .Builder()
        .code(200)
        .addHeader("X-Api-Version", "10")
        .addHeader("X-Version", "3.4.2")
        .body(
            """{"user": {"id": $userId, "username": "alice", "first_name": "Alice", "last_name": "$lastName"}, "settings": {}}""",
        ).build()

    @Test
    fun `signs in stores the account and provides its client`() =
        runTest {
            server.enqueue(tokenResponse("abc123"))
            server.enqueue(uiSettings())
            server.enqueue(uiSettings())

            val account =
                repository.signIn(
                    serverUrl(),
                    "alice",
                    "pw",
                    customHeaders = listOf("X-Proxy" to "secret"),
                    tls = AccountTls(),
                )

            assertEquals("alice", account.username)
            assertEquals("Alice", account.displayName)
            assertEquals(ApiVersion.V10, account.apiVersion)
            assertEquals("3.4.2", account.serverVersion)
            assertEquals(listOf("X-Proxy"), account.customHeaderNames)
            assertEquals(account, repository.state.first().activeAccount)

            val client = repository.activeClient.first()!!
            client.server.fetchServerInfo()
            server.takeRequest() // token
            server.takeRequest() // sign-in check
            val request = server.takeRequest()
            assertEquals("Token abc123", request.headers["Authorization"])
            assertEquals("secret", request.headers["X-Proxy"])
            assertEquals("/paperless/api/ui_settings/", request.url.encodedPath)
        }

    @Test
    fun `signing in again as the same user updates the account and its client`() =
        runTest {
            server.enqueue(tokenResponse("first"))
            server.enqueue(uiSettings())
            server.enqueue(uiSettings(lastName = "Smith"))

            val first = repository.signIn(serverUrl(), "alice", "pw", tls = AccountTls())
            val firstClient = repository.activeClient.first()!!
            assertTrue("clients are shared, not rebuilt per call", firstClient === repository.activeClient.first())
            val second = repository.signInWithToken(serverUrl(), AuthToken("second"), tls = AccountTls())

            assertEquals(first.id, second.id)
            assertEquals("Alice", first.displayName)
            assertEquals("Alice Smith", second.displayName)
            assertEquals(
                1,
                repository.state
                    .first()
                    .accounts.size,
            )
            assertEquals(
                AuthToken("second"),
                repository.activeClient
                    .first()!!
                    .config.token,
            )
        }

    @Test
    fun `keeps several accounts and switches between them`() =
        runTest {
            server.enqueue(tokenResponse("a"))
            server.enqueue(uiSettings(userId = 3))
            server.enqueue(tokenResponse("b"))
            server.enqueue(uiSettings(userId = 4))

            val alice = repository.signIn(serverUrl(), "alice", "pw", tls = AccountTls())
            val other = repository.signIn(serverUrl(), "bob", "pw", tls = AccountTls())
            assertNotEquals(alice.id, other.id)
            assertEquals(other.id, repository.state.first().activeAccountId)

            repository.switchTo(alice.id)
            assertEquals(AuthToken("a"), repository.currentClient()?.config?.token)

            repository.signOut(alice.id)
            assertEquals(
                listOf(other.id),
                repository.state
                    .first()
                    .accounts
                    .map { it.id },
            )
            assertEquals(AuthToken("b"), repository.currentClient()?.config?.token)

            repository.signOut(other.id)
            assertNull(repository.activeClient.first())
            assertEquals("cached data is deleted on sign-out", listOf(alice.id, other.id), clearedAccounts)
        }

    @Test
    fun `failed sign in stores nothing`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(
                        400,
                    ).body("""{"non_field_errors": ["Unable to log in with provided credentials."]}""")
                    .build(),
            )

            val result = runCatching { repository.signIn(serverUrl(), "alice", "wrong", tls = AccountTls()) }

            assertTrue(result.exceptionOrNull() is PaperlessException.Auth.InvalidCredentials)
            assertTrue(
                repository.state
                    .first()
                    .accounts
                    .isEmpty(),
            )
        }

    @Test
    fun `invalid input is rejected before any request`() =
        runTest {
            assertTrue(
                runCatching {
                    repository.signIn("ftp://x", "a", "b", tls = AccountTls())
                }.exceptionOrNull() is IllegalArgumentException,
            )
            assertTrue(
                runCatching {
                    repository.signIn(
                        serverUrl(),
                        "a",
                        "b",
                        customHeaders = listOf("Authorization" to "x"),
                        tls = AccountTls(),
                    )
                }.exceptionOrNull() is IllegalArgumentException,
            )
            assertEquals(0, server.requestCount)
        }
}
